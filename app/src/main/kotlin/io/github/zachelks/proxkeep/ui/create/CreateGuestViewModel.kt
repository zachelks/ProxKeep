package io.github.zachelks.proxkeep.ui.create

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.LifecycleOperation
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException

/** What the cluster offers a new guest: its online nodes and the IDs already used. */
data class ClusterChoices(val nodes: List<String>, val takenIds: Set<Int>)

/**
 * What one node offers a new guest.
 *
 * @property diskStorages storages that hold disks, or containers' root disks
 * @property isos installation images, for virtual machines
 * @property templates operating system templates, for containers
 */
data class NodeChoices(
    val diskStorages: List<NodeStorage>,
    val isos: List<StorageVolume>,
    val templates: List<StorageVolume>,
    val bridges: List<String>,
)

/**
 * The new virtual machine or container wizard. Creation runs in
 * [GuestLifecycle], so the wizard can close at once and the result is
 * reported however the person moves on.
 */
class CreateGuestViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    private val lifecycle: GuestLifecycle,
    val kind: ResourceKind,
) : ViewModel() {

    private val clusterState = clusterRepository.state

    val cluster: StateFlow<ClusterChoices?> = clusterState
        .map { state ->
            (state as? ClusterState.Loaded)?.let { loaded ->
                ClusterChoices(
                    nodes = loaded.resources.filter { it.kind == ResourceKind.Node && it.status == "online" }.mapNotNull { it.node }.sorted(),
                    takenIds = loaded.resources.mapNotNull { resource -> resource.vmid.takeIf { resource.kind == ResourceKind.Qemu || resource.kind == ResourceKind.Lxc } }.toSet(),
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _vm = MutableStateFlow(VmDraft())
    val vm: StateFlow<VmDraft> = _vm.asStateFlow()

    private val _ct = MutableStateFlow(CtDraft())
    val ct: StateFlow<CtDraft> = _ct.asStateFlow()

    private val _node = MutableStateFlow<Loadable<NodeChoices>>(Loadable.Loading)
    val node: StateFlow<Loadable<NodeChoices>> = _node.asStateFlow()

    private var nodeJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val id = servers.request { it.nextId() }.toString()
                _vm.update { if (it.vmid.isEmpty()) it.copy(vmid = id) else it }
                _ct.update { if (it.vmid.isEmpty()) it.copy(vmid = id) else it }
            } catch (e: IOException) {
                // The person types an ID instead.
            }
        }
        viewModelScope.launch {
            val first = cluster.first { it != null && it.nodes.isNotEmpty() }!!.nodes.first()
            if (currentNode().isEmpty()) setNode(first)
        }
    }

    private fun currentNode(): String = if (kind == ResourceKind.Qemu) _vm.value.node else _ct.value.node

    fun updateVm(change: (VmDraft) -> VmDraft) {
        val before = _vm.value.node
        _vm.update(change)
        if (_vm.value.node != before) loadNode(_vm.value.node)
    }

    fun updateCt(change: (CtDraft) -> CtDraft) {
        val before = _ct.value.node
        _ct.update(change)
        if (_ct.value.node != before) loadNode(_ct.value.node)
    }

    fun setNode(node: String) {
        if (kind == ResourceKind.Qemu) updateVm { it.copy(node = node) } else updateCt { it.copy(node = node) }
    }

    fun retryNode() = loadNode(currentNode())

    /** Reads what [node] offers, and picks its first storage, template and bridge where the draft has none it offers. */
    private fun loadNode(node: String) {
        nodeJob?.cancel()
        _node.value = Loadable.Loading
        nodeJob = viewModelScope.launch {
            _node.value = try {
                val storages = servers.request { it.nodeStorage(node) }.filter { it.isUsable }.sortedBy { it.storage }
                val content = if (kind == ResourceKind.Qemu) "iso" else "vztmpl"
                val volumes = storages.filter { content in it.contentTypes }.flatMap { storage ->
                    try {
                        servers.request { it.storageContent(node, storage.storage, content = content) }
                    } catch (e: PveApiException) {
                        // One storage that cannot be read, such as an offline share, does not hide the rest.
                        emptyList()
                    }
                }.sortedBy { it.volid }
                val bridges = try {
                    servers.request { it.nodeNetworks(node, type = "any_bridge") }.map { it.iface }
                } catch (e: PveApiException) {
                    emptyList()
                }
                val diskContent = if (kind == ResourceKind.Qemu) "images" else "rootdir"
                val choices = NodeChoices(
                    diskStorages = storages.filter { diskContent in it.contentTypes },
                    isos = if (kind == ResourceKind.Qemu) volumes else emptyList(),
                    templates = if (kind == ResourceKind.Lxc) volumes else emptyList(),
                    bridges = bridges,
                )
                applyDefaults(choices)
                Loadable.Loaded(choices)
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    private fun applyDefaults(choices: NodeChoices) {
        val storages = choices.diskStorages.map { it.storage }
        val firstStorage = storages.firstOrNull().orEmpty()
        val firstBridge = choices.bridges.firstOrNull()
        _vm.update { draft ->
            draft.copy(
                storage = draft.storage.takeIf { it in storages } ?: firstStorage,
                iso = draft.iso?.takeIf { iso -> choices.isos.any { it.volid == iso } },
                bridge = draft.bridge?.let { bridge -> bridge.takeIf { it in choices.bridges || choices.bridges.isEmpty() } ?: firstBridge },
            )
        }
        _ct.update { draft ->
            draft.copy(
                storage = draft.storage.takeIf { it in storages } ?: firstStorage,
                template = draft.template.takeIf { template -> choices.templates.any { it.volid == template } } ?: choices.templates.firstOrNull()?.volid.orEmpty(),
                bridge = draft.bridge.takeIf { it in choices.bridges || choices.bridges.isEmpty() } ?: firstBridge.orEmpty(),
            )
        }
    }

    /**
     * Starts creating the guest.
     *
     * @return false when the draft is not complete or the server is not loaded, and nothing started
     */
    fun create(): Boolean {
        val profileId = (clusterState.value as? ClusterState.Loaded)?.profileId ?: return false
        val taken = cluster.value?.takenIds.orEmpty()
        val (node, vmid, name, parameters) = if (kind == ResourceKind.Qemu) {
            val draft = _vm.value
            if (!draft.isValid(VmStep.Confirm, taken)) return false
            Request(draft.node, draft.vmid.toInt(), draft.name.trim().ifEmpty { draft.vmid }, draft.parameters())
        } else {
            val draft = _ct.value
            if (!draft.isValid(CtStep.Confirm, taken)) return false
            Request(draft.node, draft.vmid.toInt(), draft.hostname.trim(), draft.parameters())
        }
        val type = if (kind == ResourceKind.Qemu) "qemu" else "lxc"
        val resource = ClusterResource(id = "$type/$vmid", type = type, node = node, vmid = vmid, name = name)
        return lifecycle.run(profileId, resource, LifecycleOperation.Create) { client, guest ->
            if (kind == ResourceKind.Qemu) client.createVm(guest.node, parameters) else client.createContainer(guest.node, parameters)
        }
    }

    private data class Request(val node: String, val vmid: Int, val name: String, val parameters: Map<String, String>)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
