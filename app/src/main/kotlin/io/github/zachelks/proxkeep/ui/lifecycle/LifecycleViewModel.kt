package io.github.zachelks.proxkeep.ui.lifecycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.model.BackupCompression
import io.github.zachelks.proxkeep.core.api.model.BackupMode
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.Snapshot
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.data.BusyGuest
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.GuestRef
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** Something read from the server for the lifecycle screens. */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>

    /** @property refreshError set when the latest refresh failed and [value] is older than it looks */
    data class Loaded<T>(val value: T, val refreshError: UiMessage? = null) : Loadable<T>

    data class Failed(val message: UiMessage) : Loadable<Nothing>
}

/** A backup of the guest, with the storage it is on. */
data class GuestBackup(val storage: String, val volume: StorageVolume)

/** Where backups of the guest can go and the backups it already has, newest first. */
data class BackupOverview(val storages: List<NodeStorage>, val backups: List<GuestBackup>)

/**
 * Where a guest can move.
 *
 * @property localDisks disks that must be copied because the targets do not share their storage
 * @property blockers devices tied to the current node, which prevent moving at all
 */
data class MigrationOptions(val targets: List<String>, val localDisks: List<String>, val blockers: List<String>)

/** What a clone starts with: a free ID, the nodes it can go to and the storages a full copy can use. */
data class CloneOptions(val nextId: Int, val nodes: List<String>, val storages: List<String>)

/**
 * Snapshots, backups, migration, cloning, templates and deletion for one
 * guest. The guest is named by its cluster resource ID, so the screens
 * survive it changing node.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LifecycleViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    private val lifecycle: GuestLifecycle,
    private val refreshSignal: RefreshSignal,
    val resourceId: String,
) : ViewModel() {

    private val cluster: StateFlow<ClusterState> = clusterRepository.state

    /** The guest, while the cluster lists it. */
    val guest: StateFlow<ClusterResource?> = cluster
        .map { state -> (state as? ClusterState.Loaded)?.resources?.firstOrNull { it.id == resourceId } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The operation running on the guest, if any. */
    val busy: StateFlow<LifecycleOperation?> = combine(lifecycle.busy, servers.activeProfileId) { busy, profileId ->
        profileId?.let { busy[BusyGuest(it, resourceId)] }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** Where the guest is: the node and kind lifecycle calls address. Changes restart the loaders. */
    private val location: Flow<Triple<String, ResourceKind, Int>?> = guest
        .map { guest -> guest?.let { g -> g.node?.let { node -> g.vmid?.let { Triple(node, g.kind, it) } } } }
        .distinctUntilChanged()

    val snapshots: StateFlow<Loadable<List<Snapshot>>> = location
        .flatMapLatest { at -> if (at == null) flowOf(Loadable.Loading) else follow { client -> client.snapshots(at.first, at.second, at.third) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    val backups: StateFlow<Loadable<BackupOverview>> = location
        .flatMapLatest { at -> if (at == null) flowOf(Loadable.Loading) else follow { client -> readBackups(client, at.first, at.third) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    private val _migration = MutableStateFlow<Loadable<MigrationOptions>?>(null)

    /** Null until [loadMigration] is called. */
    val migration: StateFlow<Loadable<MigrationOptions>?> = _migration.asStateFlow()

    private val _clone = MutableStateFlow<Loadable<CloneOptions>?>(null)

    /** Null until [loadClone] is called. */
    val clone: StateFlow<Loadable<CloneOptions>?> = _clone.asStateFlow()

    /** Reads again on the shared schedule, and after every finished operation. */
    private fun <T> follow(read: suspend (PveClient) -> T): Flow<Loadable<T>> = flow {
        var current: Loadable<T> = Loadable.Loading
        emit(current)
        refreshSignal.ticks().collect {
            current = try {
                Loadable.Loaded(servers.request(read))
            } catch (e: IOException) {
                val previous = current
                if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
            }
            emit(current)
        }
    }

    private suspend fun readBackups(client: PveClient, node: String, vmid: Int): BackupOverview {
        val storages = client.nodeStorage(node, content = "backup").filter { it.isUsable }.sortedBy { it.storage }
        val backups = storages.flatMap { storage ->
            try {
                client.storageContent(node, storage.storage, content = "backup", vmid = vmid).map { GuestBackup(storage.storage, it) }
            } catch (e: PveApiException) {
                // One unreachable storage, such as a backup server that is down, does not hide the rest.
                emptyList()
            }
        }
        return BackupOverview(storages, backups.sortedByDescending { it.volume.ctime ?: 0L })
    }

    fun retry() = refreshSignal.request()

    // --- Operations -----------------------------------------------------------

    private fun run(operation: LifecycleOperation, start: suspend (PveClient, GuestRef) -> String?): Boolean {
        val resource = guest.value ?: return false
        val profileId = (cluster.value as? ClusterState.Loaded)?.profileId ?: return false
        return lifecycle.run(profileId, resource, operation, start)
    }

    fun takeSnapshot(name: String, description: String, includeMemory: Boolean) =
        run(LifecycleOperation.TakeSnapshot) { client, g -> client.createSnapshot(g.node, g.kind, g.vmid, name, description, includeMemory) }

    fun rollBack(snapshot: String) =
        run(LifecycleOperation.RollBack) { client, g -> client.rollbackSnapshot(g.node, g.kind, g.vmid, snapshot) }

    fun deleteSnapshot(snapshot: String) =
        run(LifecycleOperation.DeleteSnapshot) { client, g -> client.deleteSnapshot(g.node, g.kind, g.vmid, snapshot) }

    fun backUp(storage: String, mode: BackupMode, compression: BackupCompression, notes: String?) =
        run(LifecycleOperation.Backup) { client, g -> client.backupNow(g.node, g.vmid, storage, mode, compression, notes) }

    /** Replaces the guest with a backup. The guest must be stopped. */
    fun restore(backup: GuestBackup, start: Boolean) =
        run(LifecycleOperation.Restore) { client, g -> client.restore(g.node, g.kind, g.vmid, backup.volume.volid, overwrite = true, start = start) }

    fun migrate(target: String, withLocalDisks: Boolean) = run(LifecycleOperation.Migrate) { client, g ->
        client.migrate(g.node, g.kind, g.vmid, target, running = guest.value?.status == "running", withLocalDisks = withLocalDisks)
    }

    fun cloneTo(newId: Int, name: String, full: Boolean, targetNode: String?, storage: String?) =
        run(LifecycleOperation.Clone) { client, g -> client.clone(g.node, g.kind, g.vmid, newId, name, full, targetNode, storage) }

    fun convertToTemplate() =
        run(LifecycleOperation.ConvertToTemplate) { client, g -> client.convertToTemplate(g.node, g.kind, g.vmid) }

    fun delete(purge: Boolean) =
        run(LifecycleOperation.Delete) { client, g -> client.deleteGuest(g.node, g.kind, g.vmid, purge) }

    // --- Dialog data ----------------------------------------------------------

    /** Reads where the guest can move, for the migration dialog. */
    fun loadMigration() {
        val resource = guest.value ?: return
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        val loaded = cluster.value as? ClusterState.Loaded ?: return
        _migration.value = Loadable.Loading
        viewModelScope.launch {
            _migration.value = try {
                val otherNodes = onlineNodes(loaded).filter { it != node }
                if (resource.kind == ResourceKind.Qemu) {
                    val check = servers.request { it.migrationCheck(node, vmid) }
                    val allowed = check.allowedNodes?.toSet()
                    Loadable.Loaded(
                        MigrationOptions(
                            targets = otherNodes.filter { allowed == null || it in allowed },
                            localDisks = check.localDisks.orEmpty().map { it.driveName ?: it.volid },
                            blockers = check.localResources.orEmpty(),
                        ),
                    )
                } else {
                    Loadable.Loaded(MigrationOptions(otherNodes, emptyList(), emptyList()))
                }
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    /** Reads a free ID and the storages a full copy can use, for the clone dialog. */
    fun loadClone() {
        val node = guest.value?.node ?: return
        val loaded = cluster.value as? ClusterState.Loaded ?: return
        _clone.value = Loadable.Loading
        viewModelScope.launch {
            _clone.value = try {
                val nextId = servers.request { it.nextId() }
                val storages = servers.request { it.nodeStorage(node) }
                    .filter { it.isUsable && ("images" in it.contentTypes || "rootdir" in it.contentTypes) }
                    .map { it.storage }
                    .sorted()
                Loadable.Loaded(CloneOptions(nextId, onlineNodes(loaded), storages))
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    private fun onlineNodes(loaded: ClusterState.Loaded): List<String> = loaded.resources
        .filter { it.kind == ResourceKind.Node && it.status == "online" }
        .mapNotNull { it.node }
        .sorted()

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
