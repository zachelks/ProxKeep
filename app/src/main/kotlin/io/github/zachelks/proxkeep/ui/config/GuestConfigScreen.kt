@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.config

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.schema.SchemaFieldEditor
import io.github.zachelks.proxkeep.ui.schema.SchemaForm
import io.github.zachelks.proxkeep.ui.schema.problemText
import io.github.zachelks.proxkeep.ui.text

@Composable
fun guestConfigViewModel(resourceId: String): GuestConfigViewModel {
    val container = appContainer()
    return viewModel(key = "config:$resourceId") {
        GuestConfigViewModel(container.repository, container.cluster, container.schemas, container.refresh, resourceId)
    }
}

private enum class ConfigTab(val label: Int) { Hardware(R.string.config_tab_hardware), Options(R.string.config_tab_options) }

/** The hardware dialog on screen, if any. */
private sealed interface HardwareEdit {
    data object Processor : HardwareEdit
    data object Memory : HardwareEdit
    data object AddDisk : HardwareEdit
    data class Grow(val disk: DiskInfo) : HardwareEdit
    data class Move(val disk: DiskInfo) : HardwareEdit
    data class Detach(val disk: DiskInfo) : HardwareEdit
    data class Cdrom(val disk: DiskInfo?) : HardwareEdit
    data class Nic(val nic: NicInfo?) : HardwareEdit
    data class RemoveNic(val nic: NicInfo) : HardwareEdit
}

/** A guest's configuration: its hardware, edited by hand, and its other settings, from the server's schema. */
@Composable
fun GuestConfigScreen(resourceId: String, onClose: () -> Unit, viewModel: GuestConfigViewModel = guestConfigViewModel(resourceId)) {
    val guest by viewModel.guest.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val access by appContainer().access.access.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(ConfigTab.Hardware) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    val name = guest?.name ?: guest?.vmid?.toString() ?: resourceId

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message -> snackbar.showSnackbar(message.format(resources)) }
    }

    val paths = guest?.vmid?.let { Permissions.guestPaths(it, guest?.pool) }.orEmpty()
    val allows = { privilege: String -> access?.allows(paths, privilege) ?: true }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.config_title, name)) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::reload, enabled = !saving) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                )
                PrimaryTabRow(selectedTabIndex = tab.ordinal) {
                    ConfigTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(stringResource(entry.label)) })
                    }
                }
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (tab == ConfigTab.Options) OptionsSaveBar(viewModel, saving) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (val state = settings) {
                Loadable.Loading -> CenteredProgress()
                is Loadable.Failed -> MessageWithAction(state.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload)
                is Loadable.Loaded -> when (tab) {
                    ConfigTab.Hardware -> HardwareTab(state.value, viewModel, enabled = !saving, allows = allows)
                    ConfigTab.Options -> OptionsTab(state.value, viewModel, enabled = !saving && allows(Privilege.VM_CONFIG_OPTIONS))
                }
            }
        }
    }
}

private fun UiMessage.format(resources: android.content.res.Resources): String {
    val base = resources.getString(id)
    return if (detail.isNullOrBlank()) base else "$base\n${detail.trim()}"
}

@Composable
private fun HardwareTab(settings: GuestSettings, viewModel: GuestConfigViewModel, enabled: Boolean, allows: (String) -> Boolean) {
    val hardware = remember(settings) { settings.hardware }
    val qemu = settings.kind == ResourceKind.Qemu
    val choices by viewModel.choices.collectAsStateWithLifecycle()
    var edit by remember { mutableStateOf<HardwareEdit?>(null) }
    val canCpu = enabled && allows(Privilege.VM_CONFIG_CPU)
    val canMemory = enabled && allows(Privilege.VM_CONFIG_MEMORY)
    val canDisk = enabled && allows(Privilege.VM_CONFIG_DISK)
    val canCdrom = enabled && allows(Privilege.VM_CONFIG_CDROM)
    val canNet = enabled && allows(Privilege.VM_CONFIG_NETWORK)

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (settings.pending.isNotEmpty()) {
            item { PendingBanner(settings.pending.keys.sorted()) }
        }
        section(R.string.hardware_processor)
        item {
            val detail = if (qemu) {
                stringResource(
                    R.string.hardware_cpu_detail,
                    resourcesPlural(R.plurals.hardware_sockets_count, hardware.sockets),
                    resourcesPlural(R.plurals.hardware_cores_count, hardware.cores),
                    hardware.cpuType ?: stringResource(R.string.hardware_cpu_default),
                )
            } else {
                hardware.cpuLimit?.takeIf { it != "0" }?.let { stringResource(R.string.hardware_cpu_limited, it) } ?: stringResource(R.string.hardware_cpu_unlimited)
            }
            val cores = if (qemu) hardware.totalCores else hardware.cores
            SettingRow(
                title = resourcesPlural(R.plurals.hardware_cores_count, cores),
                detail = detail,
                pending = settings.isPending("cores", "sockets", "cpu", "cpulimit"),
                onClick = { edit = HardwareEdit.Processor }.takeIf { canCpu },
            )
        }
        section(R.string.hardware_memory)
        item {
            val second = if (qemu) {
                hardware.balloonMiB?.let { if (it == 0L) stringResource(R.string.hardware_balloon_off) else stringResource(R.string.hardware_balloon_min, formatBytes(it * MIB)) }
            } else {
                hardware.swapMiB?.let { stringResource(R.string.hardware_swap, formatBytes(it * MIB)) }
            }
            SettingRow(
                title = formatBytes(hardware.memoryMiB * MIB),
                detail = second,
                pending = settings.isPending("memory", "balloon", "swap"),
                onClick = { edit = HardwareEdit.Memory }.takeIf { canMemory },
            )
        }
        section(R.string.hardware_disks)
        items(hardware.disks, key = { it.key }) { disk ->
            DiskRow(
                disk,
                settings,
                canDisk,
                onGrow = { edit = HardwareEdit.Grow(disk) },
                onMove = { edit = HardwareEdit.Move(disk) },
                onDetach = { edit = HardwareEdit.Detach(disk) },
            )
        }
        item {
            AddButton(stringResource(if (qemu) R.string.hardware_add_disk else R.string.hardware_add_mount_point), canDisk) { edit = HardwareEdit.AddDisk }
        }
        if (qemu) {
            section(R.string.hardware_cdroms)
            items(hardware.cdroms, key = { it.key }) { drive ->
                val label = if (drive.isEmptyDrive) stringResource(R.string.hardware_no_media) else drive.volume.substringAfter('/')
                SettingRow(
                    title = label,
                    detail = drive.key,
                    pending = settings.isPending(drive.key),
                    onClick = { edit = HardwareEdit.Cdrom(drive) }.takeIf { canCdrom },
                    menu = if (canDisk) listOf(stringResource(R.string.hardware_remove_drive) to { edit = HardwareEdit.Detach(drive) }) else emptyList(),
                )
            }
            item { AddButton(stringResource(R.string.hardware_add_cdrom), canCdrom) { edit = HardwareEdit.Cdrom(null) } }
        }
        section(R.string.hardware_network)
        items(hardware.nics, key = { it.key }) { nic ->
            SettingRow(
                title = if (qemu) "${nic.key} · ${nic.model.orEmpty()}" else "${nic.name ?: nic.key} · ${nic.key}",
                detail = nicDetail(nic),
                pending = settings.isPending(nic.key),
                onClick = { edit = HardwareEdit.Nic(nic) }.takeIf { canNet },
                menu = if (canNet) listOf(stringResource(R.string.action_remove) to { edit = HardwareEdit.RemoveNic(nic) }) else emptyList(),
            )
        }
        item { AddButton(stringResource(R.string.hardware_add_nic), canNet) { edit = HardwareEdit.Nic(null) } }
    }

    val dismiss = { edit = null }
    when (val current = edit) {
        null -> Unit
        HardwareEdit.Processor -> ProcessorDialog(hardware, dismiss) { sockets, cores, type, limit ->
            edit = null
            viewModel.applyHardware(HardwareChanges.processor(settings.kind, sockets, cores, type, limit, settings.config))
        }
        HardwareEdit.Memory -> MemoryDialog(hardware, dismiss) { memory, balloon, swap ->
            edit = null
            viewModel.applyHardware(HardwareChanges.memory(settings.kind, memory, balloon, swap, settings.config))
        }
        HardwareEdit.AddDisk -> AddDiskDialog(settings.kind, settings.config, choices, viewModel::loadChoices, dismiss) { key, value ->
            edit = null
            viewModel.applyHardware(mapOf(key to value))
        }
        is HardwareEdit.Grow -> ResizeDialog(current.disk, dismiss) { gib ->
            edit = null
            viewModel.resize(current.disk.key, gib)
        }
        is HardwareEdit.Move -> MoveDiskDialog(settings.kind, current.disk, choices, viewModel::loadChoices, dismiss) { storage, deleteSource ->
            edit = null
            viewModel.moveDisk(current.disk.key, storage, deleteSource)
        }
        is HardwareEdit.Detach -> {
            val unused = current.disk.isUnused
            ConfirmDialog(
                title = stringResource(if (unused) R.string.hardware_destroy_title else R.string.hardware_detach_title, current.disk.key),
                body = stringResource(
                    when {
                        unused -> R.string.hardware_destroy_body
                        current.disk.isCdrom -> R.string.hardware_remove_drive_body
                        else -> R.string.hardware_detach_body
                    },
                ),
                confirmLabel = stringResource(if (unused) R.string.action_destroy else R.string.action_detach),
                destructive = true,
                onConfirm = {
                    edit = null
                    viewModel.applyHardware(emptyMap(), delete = listOf(current.disk.key))
                },
                onDismiss = dismiss,
            )
        }
        is HardwareEdit.Cdrom -> CdromDialog(current.disk?.key, current.disk?.volume, settings.config, choices, viewModel::loadChoices, dismiss) { key, value ->
            edit = null
            viewModel.applyHardware(mapOf(key to value))
        }
        is HardwareEdit.Nic -> NicDialog(settings.kind, current.nic, settings.config, choices, viewModel::loadChoices, dismiss) { key, value ->
            edit = null
            viewModel.applyHardware(mapOf(key to value))
        }
        is HardwareEdit.RemoveNic -> ConfirmDialog(
            title = stringResource(R.string.hardware_remove_nic_title, current.nic.key),
            body = stringResource(R.string.hardware_remove_nic_body),
            confirmLabel = stringResource(R.string.action_remove),
            destructive = true,
            onConfirm = {
                edit = null
                viewModel.applyHardware(emptyMap(), delete = listOf(current.nic.key))
            },
            onDismiss = dismiss,
        )
    }
}

private fun GuestSettings.isPending(vararg keys: String): Boolean = keys.any { it in pending }

@Composable
private fun resourcesPlural(id: Int, count: Int): String = pluralStringResource(id, count, count)

@Composable
private fun DiskRow(disk: DiskInfo, settings: GuestSettings, canEdit: Boolean, onGrow: () -> Unit, onMove: () -> Unit, onDetach: () -> Unit) {
    val title = when {
        disk.isRoot -> stringResource(R.string.hardware_root_disk)
        disk.mountPath != null -> "${disk.mountPath} · ${disk.key}"
        else -> disk.key
    }
    val parts = listOfNotNull(disk.storage, disk.sizeBytes?.let(::formatBytes))
    val menu = buildList {
        if (canEdit && disk.canResize) add(stringResource(R.string.action_grow) to onGrow)
        if (canEdit && disk.canMove) add(stringResource(R.string.action_move_disk) to onMove)
        if (canEdit && !disk.isRoot && !disk.isFirmwareState) {
            add(stringResource(if (disk.isUnused) R.string.action_destroy else R.string.action_detach) to onDetach)
        }
    }
    SettingRow(
        title = title,
        detail = parts.joinToString(" · ").ifEmpty { disk.volume },
        pending = settings.isPending(disk.key),
        onClick = null,
        menu = menu,
        badge = if (disk.isUnused) stringResource(R.string.hardware_unused) else null,
    )
}

@Composable
private fun nicDetail(nic: NicInfo): String = listOfNotNull(
    nic.bridge,
    nic.tag?.let { stringResource(R.string.hardware_vlan_value, it) },
    nic.ip?.let { if (it == "dhcp") stringResource(R.string.hardware_ip_dhcp) else it },
    stringResource(R.string.hardware_firewall).takeIf { nic.firewall },
).joinToString(" · ")

@Composable
private fun SettingRow(
    title: String,
    detail: String?,
    pending: Boolean,
    onClick: (() -> Unit)?,
    menu: List<Pair<String, () -> Unit>> = emptyList(),
    badge: String? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                detail?.takeIf { it.isNotEmpty() }?.let { Text(it) }
                if (pending) Text(stringResource(R.string.config_after_restart), color = MaterialTheme.colorScheme.tertiary)
            }
        },
        overlineContent = badge?.let { { Text(it) } },
        trailingContent = if (menu.isEmpty()) null else {
            {
                Box {
                    IconButton(onClick = { expanded = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, title))
                    }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        menu.forEach { (label, action) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                expanded = false
                                action()
                            })
                        }
                    }
                }
            }
        },
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
    )
}

private fun LazyListScope.section(title: Int) {
    item(key = "section:$title") {
        Text(
            stringResource(title),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        )
    }
}

@Composable
private fun AddButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    if (!enabled) return
    TextButton(onClick = onClick, modifier = Modifier.padding(horizontal = 8.dp)) { Text(label) }
}

@Composable
private fun PendingBanner(keys: List<String>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.config_pending_title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(R.string.config_pending_body, keys.joinToString(", ")), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// --- Options -------------------------------------------------------------

@Composable
private fun OptionsTab(settings: GuestSettings, viewModel: GuestConfigViewModel, enabled: Boolean) {
    val schema by viewModel.schema.collectAsStateWithLifecycle()
    val form by viewModel.options.collectAsStateWithLifecycle()
    var showAll by rememberSaveable { mutableStateOf(false) }

    when (val state = schema) {
        Loadable.Loading -> CenteredProgress()
        is Loadable.Failed -> MessageWithAction(state.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::reload)
        is Loadable.Loaded -> {
            val current = form
            if (current == null) {
                CenteredProgress()
                return
            }
            val groups = optionGroups(settings, current, showAll)
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (groups.cloudInit.isNotEmpty()) {
                    section(R.string.config_cloud_init)
                    items(groups.cloudInit, key = { "ci:${it.key}" }) { field -> OptionEditor(current, field.key, viewModel, enabled) }
                    item {
                        OutlinedButton(onClick = viewModel::regenerateCloudInit, enabled = enabled, modifier = Modifier.padding(horizontal = 16.dp)) {
                            Text(stringResource(R.string.action_regenerate_cloud_init))
                        }
                    }
                }
                section(R.string.config_options)
                items(groups.options, key = { "opt:${it.key}" }) { field -> OptionEditor(current, field.key, viewModel, enabled) }
                if (groups.hidden > 0) {
                    item {
                        TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                            Text(resourcesPlural(R.plurals.config_show_all, groups.hidden))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionEditor(form: SchemaForm, key: String, viewModel: GuestConfigViewModel, enabled: Boolean) {
    val field = form.field(key) ?: return
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        SchemaFieldEditor(
            key = key,
            schema = field.schema,
            value = form.value(key),
            onValueChange = { viewModel.editOption(key, it) },
            problem = form.problem(key),
            serverError = form.serverErrors[key],
            enabled = enabled,
        )
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}

@Composable
private fun OptionsSaveBar(viewModel: GuestConfigViewModel, saving: Boolean) {
    val form by viewModel.options.collectAsStateWithLifecycle()
    val current = form ?: return
    val changes = current.changes
    if (changes.isEmpty) return
    BottomAppBar {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val firstProblem = current.fields.firstNotNullOfOrNull { field -> current.problem(field.key)?.let { field.key to it } }
            Text(
                firstProblem?.let { (key, problem) -> "$key: ${problemText(problem)}" }
                    ?: resourcesPlural(R.plurals.config_changes, changes.set.size + changes.delete.size),
                modifier = Modifier.weight(1f),
                color = if (firstProblem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
            )
            TextButton(onClick = viewModel::discardOptions, enabled = !saving) { Text(stringResource(R.string.action_discard)) }
            Button(onClick = viewModel::saveOptions, enabled = !saving && current.isValid) { Text(stringResource(R.string.action_save)) }
        }
    }
}
