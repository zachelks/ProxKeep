package io.github.zachelks.proxkeep.ui.storage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.awaitTask
import io.github.zachelks.proxkeep.core.api.model.ApplianceTemplate
import io.github.zachelks.proxkeep.core.api.model.ChecksumAlgorithm
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.core.api.model.UrlMetadata
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.StorageTransfers
import io.github.zachelks.proxkeep.data.Transfer
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import java.io.InputStream

/** A storage's status and everything on it. */
data class StorageOverview(val status: NodeStorage, val volumes: List<StorageVolume>) {
    /** The kinds of content on the storage or allowed on it, in a fixed order. */
    val contentTypes: List<String>
        get() = CONTENT_ORDER.filter { type -> type in status.contentTypes || volumes.any { it.content == type } }

    companion object {
        val CONTENT_ORDER = listOf("iso", "vztmpl", "backup", "images", "rootdir", "import", "snippets")

        /** Content that can be uploaded or downloaded from a URL. */
        val TRANSFERABLE = listOf("iso", "vztmpl", "import")
    }
}

/** One storage on one node: its content, deleting volumes, and uploads and downloads onto it. */
class StorageViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    private val transfers: StorageTransfers,
    private val refreshSignal: RefreshSignal,
    val node: String,
    val storage: String,
) : ViewModel() {

    private val clusterState = clusterRepository.state

    val overview: StateFlow<Loadable<StorageOverview>> = follow { client ->
        val status = client.storageStatus(node, storage)
        val volumes = client.storageContent(node, storage).sortedWith(compareBy({ it.content }, { -(it.ctime ?: 0L) }, { it.volid }))
        StorageOverview(status, volumes)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), Loadable.Loading)

    /** Uploads and downloads onto this storage that are still running. */
    val activeTransfers: StateFlow<List<Transfer>> = transfers.transfers
        .map { list -> list.filter { it.node == node && it.storage == storage } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _urlCheck = MutableStateFlow<Loadable<UrlMetadata>?>(null)

    /** What the URL last checked with [checkUrl] would fetch; null before a check. */
    val urlCheck: StateFlow<Loadable<UrlMetadata>?> = _urlCheck.asStateFlow()

    private val _templates = MutableStateFlow<Loadable<List<ApplianceTemplate>>?>(null)

    /** The template catalog; null until [loadTemplates] is called. */
    val templates: StateFlow<Loadable<List<ApplianceTemplate>>?> = _templates.asStateFlow()

    fun retry() = refreshSignal.request()

    private fun profileId(): String? = (clusterState.value as? ClusterState.Loaded)?.profileId

    fun deleteVolume(volume: StorageVolume) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            try {
                val upid = servers.request { it.deleteVolume(node, storage, volume.volid) }
                if (upid != null) {
                    val status = servers.request { it.awaitTask(node, upid, pollIntervalMs = POLL_MS) }
                    if (!status.succeeded) {
                        _messages.emit(UiMessage(R.string.storage_delete_failed, status.exitstatus))
                        return@launch
                    }
                }
                _messages.emit(UiMessage(R.string.storage_deleted))
                refreshSignal.request()
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _busy.value = false
            }
        }
    }

    /** @return false when the server is not loaded, and nothing started */
    fun upload(content: String, fileName: String, length: Long, open: () -> InputStream): Boolean {
        val profileId = profileId() ?: return false
        transfers.upload(profileId, node, storage, content, fileName, length, open)
        return true
    }

    fun checkUrl(url: String, verifyCertificates: Boolean) {
        _urlCheck.value = Loadable.Loading
        viewModelScope.launch {
            _urlCheck.value = try {
                Loadable.Loaded(servers.request { it.queryUrl(node, url.trim(), verifyCertificates) })
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    fun clearUrlCheck() {
        _urlCheck.value = null
    }

    fun download(content: String, url: String, fileName: String, checksum: Pair<ChecksumAlgorithm, String>?, verifyCertificates: Boolean): Boolean {
        val profileId = profileId() ?: return false
        val size = (_urlCheck.value as? Loadable.Loaded)?.value?.size
        transfers.download(profileId, node, storage, content, url.trim(), fileName.trim(), checksum, verifyCertificates, size)
        _urlCheck.value = null
        return true
    }

    fun loadTemplates() {
        if (_templates.value is Loadable.Loaded) return
        _templates.value = Loadable.Loading
        viewModelScope.launch {
            _templates.value = try {
                Loadable.Loaded(servers.request { it.applianceTemplates(node) }.sortedWith(compareBy({ it.section != "system" }, { it.template })))
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    fun downloadTemplate(template: String): Boolean {
        val profileId = profileId() ?: return false
        transfers.downloadTemplate(profileId, node, storage, template)
        return true
    }

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

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val MESSAGE_BUFFER = 8
        private const val POLL_MS = 1_000L

        /** A volume's name without its storage and content folder: `local:iso/debian.iso` becomes `debian.iso`. */
        fun displayName(volid: String): String {
            val path = volid.substringAfter(':')
            return if ('/' in path && path.substringBefore('/') in StorageOverview.CONTENT_ORDER + "backup") path.substringAfter('/') else path
        }

        /** Whether [name] is a file name the server accepts for [content]. */
        fun isAllowedFileName(name: String, content: String): Boolean {
            val trimmed = name.trim()
            if (trimmed.isEmpty() || '/' in trimmed || trimmed.startsWith('.')) return false
            val lower = trimmed.lowercase()
            return when (content) {
                "iso" -> lower.endsWith(".iso") || lower.endsWith(".img")
                "vztmpl" -> lower.endsWith(".tar.gz") || lower.endsWith(".tar.xz") || lower.endsWith(".tar.zst")
                "import" -> listOf(".ova", ".ovf", ".qcow2", ".raw", ".vmdk", ".img").any { lower.endsWith(it) }
                else -> true
            }
        }
    }
}
