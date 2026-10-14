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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.Snapshot
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.tasks.formatTaskTime
import io.github.zachelks.proxkeep.ui.text

/** A guest's snapshots: take one, roll back to one, or delete one. */
@Composable
fun SnapshotsScreen(resourceId: String, onClose: () -> Unit) {
    val viewModel = lifecycleViewModel(resourceId)
    val guest by viewModel.guest.collectAsStateWithLifecycle()
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val access by appContainer().access.access.collectAsStateWithLifecycle()
    val paths = guest?.vmid?.let { Permissions.guestPaths(it, guest?.pool) }.orEmpty()
    val canTake = access?.allows(paths, Privilege.VM_SNAPSHOT) ?: true
    val canRollBack = access?.allows(paths, Privilege.VM_SNAPSHOT_ROLLBACK) ?: true
    var taking by rememberSaveable { mutableStateOf(false) }
    var confirmRollBack by remember { mutableStateOf<Snapshot?>(null) }
    var confirmDelete by remember { mutableStateOf<Snapshot?>(null) }
    val name = guest?.name ?: guest?.vmid?.toString() ?: resourceId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.snapshots_title, name)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
        floatingActionButton = {
            if (canTake && guest?.isTemplate == false) {
                ExtendedFloatingActionButton(
                    onClick = { if (busy == null) taking = true },
                    text = { Text(stringResource(R.string.action_take_snapshot)) },
                    icon = {},
                )
            }
        },
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            busy?.let { operation ->
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(operation.progressLabel),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (val state = snapshots) {
                Loadable.Loading -> CenteredProgress()
                is Loadable.Failed -> MessageWithAction(
                    message = state.message.text(),
                    actionLabel = stringResource(R.string.action_retry),
                    onAction = viewModel::retry,
                )
                is Loadable.Loaded -> {
                    val taken = state.value.filterNot { it.isCurrent }.sortedByDescending { it.snaptime ?: 0L }
                    val current = state.value.firstOrNull { it.isCurrent }
                    if (taken.isEmpty()) {
                        MessageWithAction(message = stringResource(R.string.snapshots_empty))
                    } else {
                        LazyColumn {
                            items(taken, key = { it.name }) { snapshot ->
                                SnapshotRow(
                                    snapshot = snapshot,
                                    isBase = snapshot.name == current?.parent,
                                    enabled = busy == null,
                                    canRollBack = canRollBack,
                                    canDelete = canTake,
                                    onRollBack = { confirmRollBack = snapshot },
                                    onDelete = { confirmDelete = snapshot },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    }

    if (taking) {
        TakeSnapshotDialog(
            offerMemory = guest?.kind == ResourceKind.Qemu && guest?.status == "running",
            onDismiss = { taking = false },
            onTake = { snapshotName, description, memory ->
                taking = false
                viewModel.takeSnapshot(snapshotName, description, memory)
            },
        )
    }
    confirmRollBack?.let { snapshot ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_rollback_title, name, snapshot.name),
            body = stringResource(R.string.confirm_rollback_body),
            confirmLabel = stringResource(R.string.action_roll_back),
            destructive = true,
            onConfirm = {
                confirmRollBack = null
                viewModel.rollBack(snapshot.name)
            },
            onDismiss = { confirmRollBack = null },
        )
    }
    confirmDelete?.let { snapshot ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_snapshot_title, snapshot.name),
            body = stringResource(R.string.confirm_delete_snapshot_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = null
                viewModel.deleteSnapshot(snapshot.name)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun SnapshotRow(
    snapshot: Snapshot,
    isBase: Boolean,
    enabled: Boolean,
    canRollBack: Boolean,
    canDelete: Boolean,
    onRollBack: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val details = listOfNotNull(
        snapshot.snaptime?.let { formatTaskTime(it) },
        if (snapshot.vmstate == true) stringResource(R.string.snapshot_with_memory) else null,
        if (isBase) stringResource(R.string.snapshot_current_base) else null,
    )
    ListItem(
        headlineContent = { Text(snapshot.name) },
        supportingContent = {
            Column {
                if (details.isNotEmpty()) Text(details.joinToString(" · "))
                snapshot.description?.takeIf { it.isNotBlank() }?.let { Text(it.trim(), style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            if (canRollBack || canDelete) {
                Box {
                    IconButton(onClick = { menuOpen = true }, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.snapshot_actions, snapshot.name))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (canRollBack) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_roll_back)) },
                                onClick = {
                                    menuOpen = false
                                    onRollBack()
                                },
                            )
                        }
                        if (canDelete) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_delete)) },
                                onClick = {
                                    menuOpen = false
                                    onDelete()
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun TakeSnapshotDialog(offerMemory: Boolean, onDismiss: () -> Unit, onTake: (String, String, Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var memory by rememberSaveable { mutableStateOf(false) }
    val valid = Snapshot.isValidName(name)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.action_take_snapshot)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text(stringResource(R.string.field_snapshot_name)) },
                    supportingText = { Text(stringResource(R.string.field_snapshot_name_hint)) },
                    isError = name.isNotEmpty() && !valid,
                    singleLine = true,
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.field_description)) },
                )
                if (offerMemory) {
                    Row(
                        modifier = Modifier.toggleable(value = memory, role = Role.Checkbox, onValueChange = { memory = it }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = memory, onCheckedChange = null)
                        Text(stringResource(R.string.snapshot_include_memory), modifier = Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onTake(name, description, memory) }, enabled = valid) {
                Text(stringResource(R.string.action_take_snapshot))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Asks before something that cannot be undone. A destructive confirmation is shown in the error color. */
@Composable
fun ConfirmDialog(
    title: String,
    body: String,
    confirmLabel: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(body)
                extra()
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = if (destructive) ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error) else ButtonDefaults.textButtonColors(),
            ) {
                Text(confirmLabel)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
