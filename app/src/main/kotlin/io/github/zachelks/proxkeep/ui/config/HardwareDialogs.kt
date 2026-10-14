package io.github.zachelks.proxkeep.ui.config

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.MenuField
import io.github.zachelks.proxkeep.ui.components.RadioChoices
import io.github.zachelks.proxkeep.ui.components.SuggestField
import io.github.zachelks.proxkeep.ui.components.WholeNumberField
import io.github.zachelks.proxkeep.ui.components.isWholeNumberIn
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.text

/** A dialog with a form, a confirm button that is enabled only while the form is valid, and Cancel. */
@Composable
private fun FormDialog(
    title: String,
    confirmLabel: String,
    canConfirm: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    content: @Composable () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                content()
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, enabled = canConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shows [content] once the choices are read, and a spinner or the reason they could not be until then. */
@Composable
private fun WithChoices(choices: Loadable<HardwareChoices>?, onLoad: () -> Unit, content: @Composable (HardwareChoices) -> Unit) {
    LaunchedEffect(Unit) { onLoad() }
    when (choices) {
        null, Loadable.Loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
        is Loadable.Failed -> Text(choices.message.text(), color = MaterialTheme.colorScheme.error)
        is Loadable.Loaded -> content(choices.value)
    }
}

@Composable
fun ProcessorDialog(hardware: Hardware, onDismiss: () -> Unit, onSave: (sockets: Int, cores: Int, cpuType: String?, cpuLimit: String?) -> Unit) {
    val qemu = hardware.kind == ResourceKind.Qemu
    var sockets by rememberSaveable { mutableStateOf(hardware.sockets.toString()) }
    var cores by rememberSaveable { mutableStateOf(hardware.cores.toString()) }
    var type by rememberSaveable { mutableStateOf(hardware.cpuType.orEmpty()) }
    var limit by rememberSaveable { mutableStateOf(hardware.cpuLimit.orEmpty()) }
    val limitValid = limit.isEmpty() || limit.toDoubleOrNull()?.let { it in 0.0..MAX_CPU_LIMIT } == true
    val valid = isWholeNumberIn(cores, 1, MAX_CORES) && (!qemu || isWholeNumberIn(sockets, 1, MAX_SOCKETS)) && limitValid
    FormDialog(
        title = stringResource(R.string.hardware_processor),
        confirmLabel = stringResource(R.string.action_save),
        canConfirm = valid,
        onConfirm = { onSave(sockets.toInt(), cores.toInt(), type.takeIf { qemu }, limit.takeIf { !qemu }) },
        onDismiss = onDismiss,
    ) {
        val error = stringResource(R.string.problem_range, 1, if (qemu) MAX_SOCKETS else MAX_CORES)
        if (qemu) WholeNumberField(stringResource(R.string.hardware_sockets), sockets, { sockets = it }, 1, MAX_SOCKETS, stringResource(R.string.problem_range, 1, MAX_SOCKETS))
        WholeNumberField(stringResource(R.string.hardware_cores), cores, { cores = it }, 1, MAX_CORES, error)
        if (qemu) {
            SuggestField(stringResource(R.string.hardware_cpu_type), type, HardwareFormats.CPU_TYPES, { type = it.trim() })
        } else {
            OutlinedTextField(
                value = limit,
                onValueChange = { limit = it },
                singleLine = true,
                label = { Text(stringResource(R.string.hardware_cpu_limit)) },
                isError = !limitValid,
                supportingText = { Text(stringResource(R.string.hardware_cpu_limit_help)) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun MemoryDialog(hardware: Hardware, onDismiss: () -> Unit, onSave: (memoryMiB: Long, balloonMiB: Long?, swapMiB: Long?) -> Unit) {
    val qemu = hardware.kind == ResourceKind.Qemu
    var memory by rememberSaveable { mutableStateOf(hardware.memoryMiB.toString()) }
    var second by rememberSaveable { mutableStateOf((if (qemu) hardware.balloonMiB else hardware.swapMiB)?.toString().orEmpty()) }
    val memoryValue = memory.toLongOrNull()
    val valid = isWholeNumberIn(memory, MIN_MEMORY_MIB, MAX_MEMORY_MIB) &&
        (second.isEmpty() || isWholeNumberIn(second, 0, if (qemu) memoryValue ?: 0 else MAX_MEMORY_MIB))
    FormDialog(
        title = stringResource(R.string.hardware_memory),
        confirmLabel = stringResource(R.string.action_save),
        canConfirm = valid,
        onConfirm = {
            val extra = second.toLongOrNull()
            onSave(memory.toLong(), extra.takeIf { qemu }, extra.takeIf { !qemu })
        },
        onDismiss = onDismiss,
    ) {
        WholeNumberField(
            stringResource(R.string.hardware_memory_mib), memory, { memory = it }, MIN_MEMORY_MIB, MAX_MEMORY_MIB,
            error = stringResource(R.string.problem_range, MIN_MEMORY_MIB, MAX_MEMORY_MIB),
            supportingText = memoryValue?.let { formatBytes(it * MIB) },
        )
        if (qemu) {
            WholeNumberField(
                stringResource(R.string.hardware_balloon_mib), second, { second = it }, 0, memoryValue ?: 0,
                error = stringResource(R.string.hardware_balloon_error),
                supportingText = stringResource(R.string.hardware_balloon_help),
            )
        } else {
            WholeNumberField(
                stringResource(R.string.hardware_swap_mib), second, { second = it }, 0, MAX_MEMORY_MIB,
                error = stringResource(R.string.problem_range, 0L, MAX_MEMORY_MIB),
            )
        }
    }
}

/** Adds a disk, or for a container a mount point. */
@Composable
fun AddDiskDialog(
    kind: ResourceKind,
    config: GuestConfig,
    choices: Loadable<HardwareChoices>?,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onAdd: (key: String, value: String) -> Unit,
) {
    val qemu = kind == ResourceKind.Qemu
    var bus by rememberSaveable { mutableStateOf(DiskBus.Scsi) }
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableStateOf(DEFAULT_DISK_GIB.toString()) }
    var path by rememberSaveable { mutableStateOf("") }
    val key = if (qemu) HardwareChanges.nextKey(bus.key, bus.slots, config) else HardwareChanges.nextKey("mp", MAX_MOUNT_POINTS, config)
    val loaded = (choices as? Loadable.Loaded)?.value
    val chosen = storage ?: loaded?.diskStorages?.firstOrNull()?.storage
    val valid = key != null && chosen != null && isWholeNumberIn(size, 1, MAX_DISK_GIB) && (qemu || path.trim().startsWith("/"))
    FormDialog(
        title = stringResource(if (qemu) R.string.hardware_add_disk else R.string.hardware_add_mount_point),
        confirmLabel = stringResource(R.string.action_add),
        canConfirm = valid,
        onConfirm = {
            val sizeGiB = size.toInt()
            val value = if (qemu) HardwareChanges.newDisk(chosen!!, sizeGiB) else HardwareChanges.newMountPoint(chosen!!, sizeGiB, path)
            onAdd(key!!, value)
        },
        onDismiss = onDismiss,
    ) {
        WithChoices(choices, onLoad) { value ->
            if (value.diskStorages.isEmpty()) {
                Text(stringResource(R.string.hardware_no_disk_storage))
                return@WithChoices
            }
            if (qemu) {
                MenuField(stringResource(R.string.hardware_bus), DiskBus.entries, bus, { it.key.uppercase() }, { bus = it })
                if (key == null) Text(stringResource(R.string.hardware_bus_full), color = MaterialTheme.colorScheme.error)
            }
            val resources = LocalResources.current
            MenuField(
                stringResource(R.string.hardware_storage), value.diskStorages, value.diskStorages.firstOrNull { it.storage == chosen },
                { option -> option.avail?.let { resources.getString(R.string.hardware_storage_free, option.storage, formatBytes(it)) } ?: option.storage },
                { storage = it.storage },
            )
            WholeNumberField(
                stringResource(R.string.hardware_size_gib), size, { size = it }, 1, MAX_DISK_GIB,
                error = stringResource(R.string.problem_range, 1L, MAX_DISK_GIB),
            )
            if (!qemu) {
                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.hardware_mount_path)) },
                    placeholder = { Text("/srv/data") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun ResizeDialog(disk: DiskInfo, onDismiss: () -> Unit, onGrow: (Int) -> Unit) {
    var add by rememberSaveable { mutableStateOf("") }
    FormDialog(
        title = stringResource(R.string.hardware_grow_title, disk.key),
        confirmLabel = stringResource(R.string.action_grow),
        canConfirm = isWholeNumberIn(add, 1, MAX_DISK_GIB),
        onConfirm = { onGrow(add.toInt()) },
        onDismiss = onDismiss,
    ) {
        disk.sizeBytes?.let { Text(stringResource(R.string.hardware_current_size, formatBytes(it))) }
        WholeNumberField(
            stringResource(R.string.hardware_grow_by_gib), add, { add = it }, 1, MAX_DISK_GIB,
            error = stringResource(R.string.problem_range, 1L, MAX_DISK_GIB),
            supportingText = stringResource(R.string.hardware_grow_help),
        )
    }
}

/** Chooses the ISO image in a CD drive, or adds a drive when [key] is null. */
@Composable
fun CdromDialog(
    key: String?,
    current: String?,
    config: GuestConfig,
    choices: Loadable<HardwareChoices>?,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String) -> Unit,
) {
    var iso by rememberSaveable { mutableStateOf(current?.takeIf { it != "none" }) }
    val target = key ?: HardwareChanges.nextKey("ide", DiskBus.Ide.slots, config) ?: HardwareChanges.nextKey("sata", DiskBus.Sata.slots, config)
    FormDialog(
        title = stringResource(if (key == null) R.string.hardware_add_cdrom else R.string.hardware_cdrom_title),
        confirmLabel = stringResource(if (key == null) R.string.action_add else R.string.action_save),
        canConfirm = target != null && choices is Loadable.Loaded,
        onConfirm = { onSave(target!!, HardwareChanges.cdrom(iso)) },
        onDismiss = onDismiss,
    ) {
        WithChoices(choices, onLoad) { value ->
            val none = stringResource(R.string.hardware_no_media)
            RadioChoices(listOf<String?>(null) + value.isos.map { it.volid }, iso, label = { it?.substringAfter('/') ?: none }) { iso = it }
        }
    }
}

/** Edits a network device, or adds one when [nic] is null. */
@Composable
fun NicDialog(
    kind: ResourceKind,
    nic: NicInfo?,
    config: GuestConfig,
    choices: Loadable<HardwareChoices>?,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onSave: (key: String, value: String) -> Unit,
) {
    val qemu = kind == ResourceKind.Qemu
    val key = nic?.key ?: HardwareChanges.nextKey("net", MAX_NICS, config)
    var model by rememberSaveable { mutableStateOf(nic?.model ?: "virtio") }
    var bridge by rememberSaveable { mutableStateOf(nic?.bridge.orEmpty()) }
    var tag by rememberSaveable { mutableStateOf(nic?.tag?.toString().orEmpty()) }
    var firewall by rememberSaveable { mutableStateOf(nic?.firewall ?: true) }
    var name by rememberSaveable { mutableStateOf(nic?.name ?: "eth${key?.removePrefix("net") ?: 0}") }
    var ipMode by rememberSaveable { mutableStateOf(IpMode.of(nic?.ip, nic == null)) }
    var ip by rememberSaveable { mutableStateOf(nic?.ip?.takeIf { IpMode.of(it, false) == IpMode.Static }.orEmpty()) }
    var gateway by rememberSaveable { mutableStateOf(nic?.gateway.orEmpty()) }
    val loaded = (choices as? Loadable.Loaded)?.value
    val chosenBridge = bridge.ifEmpty { loaded?.bridges?.firstOrNull().orEmpty() }
    val valid = key != null && chosenBridge.isNotEmpty() && (tag.isEmpty() || isWholeNumberIn(tag, 1, MAX_VLAN)) &&
        (qemu || (name.isNotBlank() && (ipMode != IpMode.Static || '/' in ip)))
    FormDialog(
        title = stringResource(if (nic == null) R.string.hardware_add_nic else R.string.hardware_nic_title, nic?.key.orEmpty()),
        confirmLabel = stringResource(if (nic == null) R.string.action_add else R.string.action_save),
        canConfirm = valid,
        onConfirm = {
            val existing = config[key!!]
            val vlan = tag.toIntOrNull()
            val value = if (qemu) {
                HardwareChanges.qemuNic(existing, model, chosenBridge, vlan, firewall)
            } else {
                HardwareChanges.lxcNic(
                    existing,
                    NicInfo(key, name = name.trim(), bridge = chosenBridge, tag = vlan, firewall = firewall, ip = ipMode.value(ip.trim()), gateway = gateway.trim().ifEmpty { null }, ip6 = nic?.ip6, gateway6 = nic?.gateway6),
                )
            }
            onSave(key, value)
        },
        onDismiss = onDismiss,
    ) {
        if (key == null) {
            Text(stringResource(R.string.hardware_nics_full), color = MaterialTheme.colorScheme.error)
            return@FormDialog
        }
        WithChoices(choices, onLoad) { value ->
            if (qemu) MenuField(stringResource(R.string.hardware_nic_model), HardwareFormats.NIC_MODELS, model, { it }, { model = it })
            if (!qemu) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text(stringResource(R.string.hardware_nic_name)) }, modifier = Modifier.fillMaxWidth(),
                )
            }
            SuggestField(stringResource(R.string.hardware_bridge), chosenBridge, value.bridges, { bridge = it.trim() })
            WholeNumberField(
                stringResource(R.string.hardware_vlan), tag, { tag = it }, 1, MAX_VLAN,
                error = stringResource(R.string.problem_range, 1, MAX_VLAN),
                supportingText = stringResource(R.string.hardware_vlan_help),
            )
            CheckRow(stringResource(R.string.hardware_firewall), firewall) { firewall = it }
            if (!qemu) {
                Text(stringResource(R.string.hardware_ipv4), style = MaterialTheme.typography.titleSmall)
                val modeLabels = IpMode.entries.associateWith { ipModeLabel(it) }
                RadioChoices(IpMode.entries, ipMode, label = { modeLabels.getValue(it) }) { ipMode = it }
                if (ipMode == IpMode.Static) {
                    OutlinedTextField(
                        value = ip, onValueChange = { ip = it }, singleLine = true,
                        label = { Text(stringResource(R.string.hardware_ip_address)) }, placeholder = { Text("192.0.2.20/24") },
                        isError = ip.isNotEmpty() && '/' !in ip,
                        supportingText = { Text(stringResource(R.string.hardware_ip_help)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gateway, onValueChange = { gateway = it }, singleLine = true,
                        label = { Text(stringResource(R.string.hardware_gateway)) }, modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/** How a container's interface gets its IPv4 address. */
enum class IpMode {
    Dhcp,
    Static,
    None,
    ;

    fun value(address: String): String? = when (this) {
        Dhcp -> "dhcp"
        Static -> address
        None -> null
    }

    companion object {
        fun of(ip: String?, isNew: Boolean): IpMode = when {
            ip == null -> if (isNew) Dhcp else None
            ip == "dhcp" -> Dhcp
            ip == "manual" -> None
            else -> Static
        }
    }
}

@Composable
private fun ipModeLabel(mode: IpMode): String = stringResource(
    when (mode) {
        IpMode.Dhcp -> R.string.hardware_ip_dhcp
        IpMode.Static -> R.string.hardware_ip_static
        IpMode.None -> R.string.hardware_ip_none
    },
)

private const val MAX_SOCKETS = 4L
private const val MAX_CORES = 8192L
private const val MAX_CPU_LIMIT = 8192.0
private const val MIN_MEMORY_MIB = 16L
private const val MAX_MEMORY_MIB = 64L * 1024 * 1024
private const val MAX_DISK_GIB = 128L * 1024
private const val DEFAULT_DISK_GIB = 32
private const val MAX_MOUNT_POINTS = 256
private const val MAX_NICS = 32
private const val MAX_VLAN = 4094L
internal const val MIB = 1024L * 1024L
