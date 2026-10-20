@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.lifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.BackupCompression
import io.github.zachelks.proxkeep.core.api.model.BackupMode
import io.github.zachelks.proxkeep.core.api.model.NodeStorage
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.tasks.formatTaskTime
import io.github.zachelks.proxkeep.ui.text

/** Back up a guest now, and restore it from one of its backups. */
@Composable
fun BackupsScreen(resourceId: String, onClose: () -> Unit) {
    val viewModel = lifecycleViewModel(resourceId)
    val guest by viewModel.guest.collectAsStateWithLifecycle()
    val backups by viewModel.backups.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val access by appContainer().access.access.collectAsStateWithLifecycle()
    val paths = guest?.vmid?.let { Permissions.guestPaths(it, guest?.pool) }.orEmpty()
    val canBackUp = access?.allows(paths, Privilege.VM_BACKUP) ?: true
    val canAllocate = access?.allows(listOf("/vms"), Privilege.VM_ALLOCATE) ?: true
    var restoring by remember { mutableStateOf<GuestBackup?>(null) }
    var restoringAsNew by remember { mutableStateOf<GuestBackup?>(null) }
    val restoreChoices by viewModel.restoreChoices.collectAsStateWithLifecycle()
    val name = guest?.name ?: guest?.vmid?.toString() ?: resourceId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.backups_title, name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            busy?.let { operation ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(stringResource(operation.progressLabel), modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            }
            when (val state = backups) {
                Loadable.Loading -> CenteredProgress()
                is Loadable.Failed -> MessageWithAction(
                    message = state.message.text(),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::retry,
                )
                is Loadable.Loaded -> LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (canBackUp && guest?.isTemplate == false) {
                        item(key = "now") {
                            BackUpNowCard(
                                storages = state.value.storages,
                                enabled = busy == null,
                                onBackUp = viewModel::backUp,
                            )
                        }
                    }
                    item(key = "title") {
                        Text(
                            stringResource(R.string.backups_existing),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    if (state.value.backups.isEmpty()) {
                        item(key = "none") {
                            Text(stringResource(R.string.backups_empty), modifier = Modifier.padding(16.dp))
                        }
                    }
                    items(state.value.backups, key = { it.volume.volid }) { backup ->
                        ListItem(
                            headlineContent = { Text(backup.volume.ctime?.let { formatTaskTime(it) } ?: backup.volume.volid) },
                            supportingContent = {
                                Column {
                                    Text(listOfNotNull(backup.storage, backup.volume.size?.let(::formatBytes)).joinToString(" · "))
                                    backup.volume.notes?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), style = MaterialTheme.typography.bodySmall) }
                                    if (backup.volume.protected == true) {
                                        Text(stringResource(R.string.backup_protected), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                            },
                            trailingContent = {
                                if (canBackUp || canAllocate) {
                                    Box {
                                        var menu by remember { mutableStateOf(false) }
                                        TextButton(onClick = { menu = true }, enabled = busy == null) {
                                            Text(stringResource(R.string.action_restore))
                                        }
                                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                            if (canBackUp) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.restore_replace)) }, onClick = {
                                                    menu = false
                                                    restoring = backup
                                                })
                                            }
                                            if (canAllocate) {
                                                DropdownMenuItem(text = { Text(stringResource(R.string.restore_new_title)) }, onClick = {
                                                    menu = false
                                                    restoringAsNew = backup
                                                })
                                            }
                                        }
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    restoringAsNew?.let { backup ->
        RestoreAsNewDialog(
            archiveName = backup.volume.volid.substringAfter(':'),
            choices = restoreChoices,
            onLoad = viewModel::loadRestoreChoices,
            onDismiss = { restoringAsNew = null },
        ) { vmid, storage, unique, start ->
            restoringAsNew = null
            viewModel.restoreAsNew(backup, vmid, storage, unique, start)
        }
    }

    restoring?.let { backup ->
        var startAfter by rememberSaveable { mutableStateOf(false) }
        val stopped = guest?.status == "stopped"
        ConfirmDialog(
            title = stringResource(R.string.confirm_restore_title, name),
            body = stringResource(if (stopped) R.string.confirm_restore_body else R.string.restore_needs_stop),
            confirmLabel = stringResource(R.string.action_restore),
            destructive = true,
            confirmEnabled = stopped,
            onConfirm = {
                restoring = null
                viewModel.restore(backup, startAfter)
            },
            onDismiss = { restoring = null },
            extra = {
                if (stopped) {
                    Row(
                        modifier = Modifier.toggleable(value = startAfter, role = Role.Checkbox, onValueChange = { startAfter = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = startAfter, onCheckedChange = null)
                        Text(stringResource(R.string.restore_start_after), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            },
        )
    }
}

@Composable
private fun BackUpNowCard(
    storages: List<NodeStorage>,
    enabled: Boolean,
    onBackUp: (String, BackupMode, BackupCompression, String?) -> Boolean,
) {
    var storage by rememberSaveable { mutableStateOf(storages.firstOrNull()?.storage) }
    var mode by rememberSaveable { mutableStateOf(BackupMode.Snapshot) }
    var compression by rememberSaveable { mutableStateOf(BackupCompression.Zstd) }
    var notes by rememberSaveable { mutableStateOf("") }
    var choosing by remember { mutableStateOf(false) }
    val chosen = storages.firstOrNull { it.storage == storage }
    // A Proxmox Backup Server compresses and deduplicates by itself.
    val compresses = chosen?.type != "pbs"

    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.action_back_up_now), style = MaterialTheme.typography.titleMedium)
            if (storages.isEmpty()) {
                Text(stringResource(R.string.backup_no_storage))
                return@Column
            }
            ExposedDropdownMenuBox(expanded = choosing, onExpandedChange = { choosing = it }) {
                OutlinedTextField(
                    value = chosen?.let { storageLabel(it) } ?: "",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(R.string.field_storage)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = choosing) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                    storages.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(storageLabel(option)) },
                            onClick = {
                                storage = option.storage
                                choosing = false
                            },
                        )
                    }
                }
            }
            Text(stringResource(R.string.field_backup_mode), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BackupMode.entries.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = option == mode,
                        onClick = { mode = option },
                        shape = SegmentedButtonDefaults.itemShape(index, BackupMode.entries.size),
                        icon = {},
                    ) {
                        Text(stringResource(option.label()))
                    }
                }
            }
            Text(
                stringResource(mode.explanation()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (compresses) {
                Text(stringResource(R.string.field_compression), style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    BackupCompression.entries.forEachIndexed { index, option ->
                        SegmentedButton(
                            selected = option == compression,
                            onClick = { compression = option },
                            shape = SegmentedButtonDefaults.itemShape(index, BackupCompression.entries.size),
                            icon = {},
                        ) {
                            Text(stringResource(option.label()))
                        }
                    }
                }
            }
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text(stringResource(R.string.field_backup_notes)) },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { chosen?.let { onBackUp(it.storage, mode, if (compresses) compression else BackupCompression.Zstd, notes) } },
                enabled = enabled && chosen != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.action_back_up_now))
            }
        }
    }
}

@Composable
private fun storageLabel(storage: NodeStorage): String {
    val free = storage.avail?.let { stringResource(R.string.storage_free, formatBytes(it)) }
    return listOfNotNull(storage.storage, free).joinToString(" · ")
}

private fun BackupMode.label(): Int = when (this) {
    BackupMode.Snapshot -> R.string.backup_mode_snapshot
    BackupMode.Suspend -> R.string.backup_mode_suspend
    BackupMode.Stop -> R.string.backup_mode_stop
}

private fun BackupMode.explanation(): Int = when (this) {
    BackupMode.Snapshot -> R.string.backup_mode_snapshot_explained
    BackupMode.Suspend -> R.string.backup_mode_suspend_explained
    BackupMode.Stop -> R.string.backup_mode_stop_explained
}

private fun BackupCompression.label(): Int = when (this) {
    BackupCompression.Zstd -> R.string.compression_zstd
    BackupCompression.Lzo -> R.string.compression_lzo
    BackupCompression.Gzip -> R.string.compression_gzip
    BackupCompression.None -> R.string.compression_none
}
