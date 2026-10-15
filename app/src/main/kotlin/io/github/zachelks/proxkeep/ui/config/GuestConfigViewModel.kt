package io.github.zachelks.proxkeep.ui.config

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.awaitIfTask
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.PendingChange
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.StorageVolume
import io.github.zachelks.proxkeep.core.api.schema.ApiMethod
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.schema.SchemaForm
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * A guest's configuration as last read.
 *
 * @property pending changes that wait for the guest's next start, by key
 */
data class GuestSettings(
    val node: String,
    val kind: ResourceKind,
    val vmid: Int,
    val config: GuestConfig,
    val pending: Map<String, PendingChange> = emptyMap(),
) {
    val hardware: Hardware
        get() = Hardware.from(kind, config)

    /** Whether the guest has a cloud-init drive, so its cloud-init settings matter. */
    val hasCloudInit: Boolean
        get() = kind == ResourceKind.Qemu && config.disks.any { "cloudinit" in it.value }
}

/** What the hardware dialogs offer, read when one opens. */
data class HardwareChoices(
    val diskStorages: List<NodeStorage>,
    val isos: List<StorageVolume>,
    val bridges: List<String>,
)

/**
 * One guest's configuration: hand-designed hardware editing and the
 * schema-driven options. The configuration is read when the screen opens and
 * after every change, not on a timer, so a form being edited is not
 * replaced under the person editing it.
 */
class GuestConfigViewModel(
    private val servers: ServerRepository,
    clusterRepository: ClusterRepository,
    private val schemas: SchemaRepository,
    private val refreshSignal: RefreshSignal,
    val resourceId: String,
) : ViewModel() {

    val guest: StateFlow<ClusterResource?> = clusterRepository.state
        .map { state -> (state as? ClusterState.Loaded)?.resources?.firstOrNull { it.id == resourceId } }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _settings = MutableStateFlow<Loadable<GuestSettings>>(Loadable.Loading)
    val settings: StateFlow<Loadable<GuestSettings>> = _settings.asStateFlow()

    private val _schema = MutableStateFlow<Loadable<ApiMethod>>(Loadable.Loading)

    /** The method that changes this guest's configuration, which the options form is built from. */
    val schema: StateFlow<Loadable<ApiMethod>> = _schema.asStateFlow()

    private val _options = MutableStateFlow<SchemaForm?>(null)

    /** Null until both the configuration and the schema are read. */
    val options: StateFlow<SchemaForm?> = _options.asStateFlow()

    private val _saving = MutableStateFlow(false)
    val saving: StateFlow<Boolean> = _saving.asStateFlow()

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = MESSAGE_BUFFER)

    /** How each change went. */
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    private val _choices = MutableStateFlow<Loadable<HardwareChoices>?>(null)

    /** Null until [loadChoices] is called. */
    val choices: StateFlow<Loadable<HardwareChoices>?> = _choices.asStateFlow()

    init {
        viewModelScope.launch {
            val resource = guest.filterNotNull().first()
            launch { load(resource) }
            launch { loadSchema(resource.kind) }
        }
    }

    fun reload() {
        val resource = guest.value ?: return
        viewModelScope.launch {
            if (_settings.value is Loadable.Failed) _settings.value = Loadable.Loading
            load(resource)
        }
        if (_schema.value is Loadable.Failed) {
            _schema.value = Loadable.Loading
            viewModelScope.launch { loadSchema(resource.kind) }
        }
    }

    private suspend fun load(resource: ClusterResource) {
        val node = resource.node ?: return
        val vmid = resource.vmid ?: return
        try {
            val config = servers.request { it.guestConfig(node, resource.kind, vmid) }
            val pending = try {
                servers.request { it.pendingConfig(node, resource.kind, vmid) }.filter { it.isPending }.associateBy { it.key }
            } catch (e: PveApiException) {
                // Older servers have no pending list for containers; the configuration is enough.
                emptyMap()
            }
            _settings.value = Loadable.Loaded(GuestSettings(node, resource.kind, vmid, config, pending))
            rebuildOptions()
        } catch (e: IOException) {
            val previous = _settings.value
            _settings.value = if (previous is Loadable.Loaded) previous.copy(refreshError = e.toUiMessage()) else Loadable.Failed(e.toUiMessage())
        }
    }

    private suspend fun loadSchema(kind: ResourceKind) {
        _schema.value = try {
            val type = if (kind == ResourceKind.Qemu) "qemu" else "lxc"
            val method = schemas.schema().method("/nodes/{node}/$type/{vmid}/config", if (kind == ResourceKind.Qemu) "POST" else "PUT")
            if (method == null) Loadable.Failed(UiMessage(R.string.error_schema_unavailable)) else Loadable.Loaded(method)
        } catch (e: IOException) {
            Loadable.Failed(e.toUiMessage())
        }
        rebuildOptions()
    }

    /** Builds the options form from the latest configuration, keeping edits not yet saved. */
    private fun rebuildOptions() {
        val settings = (_settings.value as? Loadable.Loaded)?.value ?: return
        val method = (_schema.value as? Loadable.Loaded)?.value ?: return
        val values = settings.config.values.mapValues { (key, value) -> if (key == SSH_KEYS) decodeKeys(value) else value }
        // Each network device can have an IP configuration for cloud-init.
        val ipConfigs = if (settings.kind == ResourceKind.Qemu) settings.config.networks.mapNotNull { it.index?.let { index -> "ipconfig$index" } } else emptyList()
        val fields = SchemaForm.fieldsFor(method, values, exclude = hardwareKeys(settings.kind), extraKeys = ipConfigs)
        _options.update { previous ->
            val edits = previous?.let { form -> form.fields.map { it.key }.filter(form::isChanged).associateWith(form::value) }.orEmpty()
            SchemaForm(fields, values, values + edits, previous?.serverErrors.orEmpty())
        }
    }

    fun editOption(key: String, value: String) {
        _options.update { it?.with(key, value) }
    }

    /** Puts every option back to its saved value. */
    fun discardOptions() {
        _options.update { form -> form?.let { SchemaForm(it.fields, it.initial) } }
    }

    fun saveOptions() {
        val form = _options.value ?: return
        if (!form.isValid || form.changes.isEmpty) return
        val changes = form.changes
        val set = changes.set.mapValues { (key, value) -> if (key == SSH_KEYS) encodeKeys(value) else value }
        apply(set, changes.delete) { _options.update { it?.saved() } }
    }

    /** Sends a hardware change and reads the configuration again. */
    fun applyHardware(set: Map<String, String>, delete: List<String> = emptyList()) = apply(set, delete) {}

    /** Grows [disk] by [addGiB] gibibytes. */
    fun resize(disk: String, addGiB: Int) {
        val settings = (_settings.value as? Loadable.Loaded)?.value ?: return
        if (addGiB <= 0) return
        run { servers.request { it.resizeDisk(settings.node, settings.kind, settings.vmid, disk, "+${addGiB}G") } }
    }

    /** Writes the cloud-init drive again, so changed settings reach the guest at its next start. */
    fun regenerateCloudInit() {
        val settings = (_settings.value as? Loadable.Loaded)?.value ?: return
        run {
            servers.request { it.regenerateCloudInit(settings.node, settings.vmid) }
            null
        }
    }

    private fun apply(set: Map<String, String>, delete: List<String>, onSaved: () -> Unit) {
        val settings = (_settings.value as? Loadable.Loaded)?.value ?: return
        run(onSaved) {
            servers.request { it.updateConfig(settings.node, settings.kind, settings.vmid, set, delete, settings.config.digest) }
        }
    }

    /** Runs a change that may start a task, follows the task, and reads the configuration again. */
    private fun run(onSaved: () -> Unit = {}, start: suspend () -> String?) {
        if (_saving.value) return
        _saving.value = true
        viewModelScope.launch {
            try {
                val reply = start()
                val status = servers.request { it.awaitIfTask(reply, pollIntervalMs = POLL_MS) }
                if (status != null && !status.succeeded) {
                    _messages.emit(UiMessage(R.string.config_save_failed, status.exitstatus))
                    return@launch
                }
                onSaved()
                guest.value?.let { load(it) }
                val after = (_settings.value as? Loadable.Loaded)?.value
                val waits = after?.pending?.isNotEmpty() == true
                _messages.emit(UiMessage(if (waits) R.string.config_saved_pending else R.string.config_saved))
                refreshSignal.request()
            } catch (e: PveApiException) {
                if (e.parameterErrors.isNotEmpty()) _options.update { it?.copy(serverErrors = it.serverErrors + e.parameterErrors) }
                _messages.emit(e.toUiMessage())
                // A stale digest means someone else changed the guest: show their version.
                guest.value?.let { load(it) }
            } catch (e: IOException) {
                _messages.emit(e.toUiMessage())
            } finally {
                _saving.value = false
            }
        }
    }

    /** Reads the storages, ISO images and bridges the hardware dialogs offer. */
    fun loadChoices() {
        val settings = (_settings.value as? Loadable.Loaded)?.value ?: return
        if (_choices.value is Loadable.Loaded) return
        _choices.value = Loadable.Loading
        viewModelScope.launch {
            _choices.value = try {
                val storages = servers.request { it.nodeStorage(settings.node) }.filter { it.isUsable }.sortedBy { it.storage }
                val diskContent = if (settings.kind == ResourceKind.Qemu) "images" else "rootdir"
                val isos = if (settings.kind == ResourceKind.Qemu) {
                    storages.filter { "iso" in it.contentTypes }.flatMap { storage ->
                        try {
                            servers.request { it.storageContent(settings.node, storage.storage, content = "iso") }
                        } catch (e: PveApiException) {
                            emptyList()
                        }
                    }.sortedBy { it.volid }
                } else {
                    emptyList()
                }
                val bridges = try {
                    servers.request { it.nodeNetworks(settings.node, type = "any_bridge") }.map { it.iface }
                } catch (e: PveApiException) {
                    emptyList()
                }
                Loadable.Loaded(HardwareChoices(storages.filter { diskContent in it.contentTypes }, isos, bridges))
            } catch (e: IOException) {
                Loadable.Failed(e.toUiMessage())
            }
        }
    }

    companion object {
        private const val MESSAGE_BUFFER = 8
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 1_000L
        const val SSH_KEYS = "sshkeys"

        /** Keys the hardware tab edits by hand, and numbered keys by their base name. */
        fun hardwareKeys(kind: ResourceKind): Set<String> = if (kind == ResourceKind.Qemu) {
            setOf("sockets", "cores", "cpu", "vcpus", "memory", "balloon", "scsi", "virtio", "sata", "ide", "efidisk", "tpmstate", "unused", "net")
        } else {
            setOf("cores", "cpulimit", "memory", "swap", "rootfs", "mp", "unused", "net")
        }

        /** The server stores SSH keys percent-encoded, with `%20` for spaces. */
        fun encodeKeys(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

        fun decodeKeys(text: String): String = try {
            URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")
        } catch (e: IllegalArgumentException) {
            text
        }
    }
}
