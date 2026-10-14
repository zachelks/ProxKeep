package io.github.zachelks.proxkeep.ui.lifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.text

/** A lifecycle action offered in a guest's menu. */
enum class LifecycleMenuItem { Snapshots, Backups, Migrate, Clone, ConvertToTemplate, Delete }

/** Shows the dialog for [item]; [onDone] closes it. Snapshots and backups have screens of their own instead. */
@Composable
fun LifecycleDialog(item: LifecycleMenuItem, guest: ClusterResource, viewModel: LifecycleViewModel, onDone: () -> Unit) {
    val name = guest.name ?: guest.vmid?.toString() ?: guest.id
    when (item) {
        LifecycleMenuItem.Migrate -> MigrateDialog(guest, name, viewModel, onDone)
        LifecycleMenuItem.Clone -> CloneDialog(guest, name, viewModel, onDone)
        LifecycleMenuItem.ConvertToTemplate -> ConfirmDialog(
            title = stringResource(R.string.confirm_template_title, name),
            body = stringResource(R.string.confirm_template_body),
            confirmLabel = stringResource(R.string.action_convert_to_template),
            destructive = true,
            onConfirm = {
                viewModel.convertToTemplate()
                onDone()
            },
            onDismiss = onDone,
        )
        LifecycleMenuItem.Delete -> DeleteDialog(guest, name, viewModel, onDone)
        LifecycleMenuItem.Snapshots, LifecycleMenuItem.Backups -> onDone()
    }
}

@Composable
private fun MigrateDialog(guest: ClusterResource, name: String, viewModel: LifecycleViewModel, onDone: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.loadMigration() }
    val options by viewModel.migration.collectAsStateWithLifecycle()
    var target by rememberSaveable { mutableStateOf<String?>(null) }
    var copyDisks by rememberSaveable { mutableStateOf(true) }
    val loaded = (options as? Loadable.Loaded)?.value
    val running = guest.status == "running"

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.migrate_title, name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val state = options) {
                    null, Loadable.Loading -> CircularProgressIndicator()
                    is Loadable.Failed -> Text(state.message.text(), color = MaterialTheme.colorScheme.error)
                    is Loadable.Loaded -> {
                        val value = state.value
                        when {
                            value.blockers.isNotEmpty() ->
                                Text(stringResource(R.string.migrate_blocked, value.blockers.joinToString(", ")))
                            value.targets.isEmpty() -> Text(stringResource(R.string.migrate_no_targets))
                            else -> {
                                Text(
                                    stringResource(
                                        when {
                                            !running -> R.string.migrate_offline
                                            guest.kind == ResourceKind.Qemu -> R.string.migrate_online_vm
                                            else -> R.string.migrate_restart_container
                                        },
                                    ),
                                )
                                Choices(value.targets, target ?: value.targets.first(), label = { it }) { target = it }
                                if (value.localDisks.isNotEmpty()) {
                                    CheckRow(stringResource(R.string.migrate_copy_disks, value.localDisks.joinToString(", ")), copyDisks) { copyDisks = it }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val chosen = target ?: loaded?.targets?.firstOrNull()
            val ready = loaded != null && loaded.blockers.isEmpty() && chosen != null && (loaded.localDisks.isEmpty() || copyDisks)
            TextButton(
                onClick = {
                    if (chosen != null) viewModel.migrate(chosen, withLocalDisks = loaded?.localDisks?.isNotEmpty() == true)
                    onDone()
                },
                enabled = ready,
            ) {
                Text(stringResource(R.string.action_migrate))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun CloneDialog(guest: ClusterResource, name: String, viewModel: LifecycleViewModel, onDone: () -> Unit) {
    LaunchedEffect(Unit) { viewModel.loadClone() }
    val options by viewModel.clone.collectAsStateWithLifecycle()
    val loaded = (options as? Loadable.Loaded)?.value
    var newId by rememberSaveable { mutableStateOf("") }
    var newName by rememberSaveable { mutableStateOf(copyName(name)) }
    // Only a template can be cloned without copying its disks.
    var full by rememberSaveable { mutableStateOf(!guest.isTemplate) }
    var node by rememberSaveable { mutableStateOf<String?>(null) }
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(loaded) { if (loaded != null && newId.isEmpty()) newId = loaded.nextId.toString() }
    val id = newId.toIntOrNull()
    val sameStorage = stringResource(R.string.clone_same_storage)

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.clone_title, name)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (val state = options) {
                    null, Loadable.Loading -> CircularProgressIndicator()
                    is Loadable.Failed -> Text(state.message.text(), color = MaterialTheme.colorScheme.error)
                    is Loadable.Loaded -> {
                        OutlinedTextField(
                            value = newId,
                            onValueChange = { text -> newId = text.filter(Char::isDigit).take(9) },
                            label = { Text(stringResource(R.string.field_guest_id)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            isError = id == null || id < MIN_GUEST_ID,
                            singleLine = true,
                        )
                        OutlinedTextField(
                            value = newName,
                            onValueChange = { newName = it },
                            label = { Text(stringResource(R.string.field_guest_name)) },
                            singleLine = true,
                        )
                        if (guest.isTemplate) {
                            CheckRow(stringResource(R.string.clone_full), full) { full = it }
                        }
                        if (state.value.nodes.size > 1) {
                            Text(stringResource(R.string.clone_target_node), style = MaterialTheme.typography.labelLarge)
                            Choices(state.value.nodes, node ?: guest.node.orEmpty(), label = { it }) { node = it }
                        }
                        if (full && state.value.storages.isNotEmpty()) {
                            Text(stringResource(R.string.clone_target_storage), style = MaterialTheme.typography.labelLarge)
                            Choices(listOf("") + state.value.storages, storage.orEmpty(), label = { it.ifEmpty { sameStorage } }) {
                                storage = it.ifEmpty { null }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (id != null) viewModel.cloneTo(id, newName.trim(), full, node, storage)
                    onDone()
                },
                enabled = loaded != null && id != null && id >= MIN_GUEST_ID,
            ) {
                Text(stringResource(R.string.action_clone))
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** The default name of a copy. Guest names are host names, so the suffix is not translated. */
private fun copyName(name: String): String = "$name-clone"

@Composable
private fun DeleteDialog(guest: ClusterResource, name: String, viewModel: LifecycleViewModel, onDone: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    var purge by rememberSaveable { mutableStateOf(true) }
    val vmid = guest.vmid?.toString().orEmpty()
    val stopped = guest.status == "stopped" || guest.isTemplate
    ConfirmDialog(
        title = stringResource(R.string.confirm_delete_guest_title, name),
        body = stringResource(if (stopped) R.string.confirm_delete_guest_body else R.string.delete_needs_stop),
        confirmLabel = stringResource(R.string.action_delete),
        destructive = true,
        // As in the web interface, the guest's ID must be typed, so a guest is never deleted by one stray tap.
        confirmEnabled = stopped && typed.trim() == vmid,
        onConfirm = {
            viewModel.delete(purge)
            onDone()
        },
        onDismiss = onDone,
        extra = {
            if (stopped) {
                OutlinedTextField(
                    value = typed,
                    onValueChange = { typed = it },
                    label = { Text(stringResource(R.string.delete_type_id, vmid)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                )
                CheckRow(stringResource(R.string.delete_purge), purge) { purge = it }
            }
        },
    )
}

@Composable
private fun <T> Choices(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(12.dp))
                Text(label(option))
            }
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Text(text, modifier = Modifier.padding(start = 12.dp))
    }
}

/** Guest IDs below 100 are reserved. */
private const val MIN_GUEST_ID = 100

/**
 * The lifecycle actions to offer for [guest]: those the credentials allow
 * and the guest's state permits. Deleting is offered for a running guest
 * too; its dialog explains it must be stopped first.
 *
 * @param allows whether the credentials hold a privilege on the guest
 * @param onlineNodes how many nodes are online, since migrating needs another
 */
fun availableLifecycleItems(guest: ClusterResource, allows: (String) -> Boolean, onlineNodes: Int): List<LifecycleMenuItem> = buildList {
    val template = guest.isTemplate
    if (!template && (allows(Privilege.VM_SNAPSHOT) || allows(Privilege.VM_SNAPSHOT_ROLLBACK))) add(LifecycleMenuItem.Snapshots)
    if (allows(Privilege.VM_BACKUP)) add(LifecycleMenuItem.Backups)
    if (onlineNodes > 1 && allows(Privilege.VM_MIGRATE)) add(LifecycleMenuItem.Migrate)
    if (allows(Privilege.VM_CLONE)) add(LifecycleMenuItem.Clone)
    if (!template && guest.status == "stopped" && allows(Privilege.VM_ALLOCATE)) add(LifecycleMenuItem.ConvertToTemplate)
    if (allows(Privilege.VM_ALLOCATE)) add(LifecycleMenuItem.Delete)
}

val LifecycleMenuItem.label: Int
    get() = when (this) {
        LifecycleMenuItem.Snapshots -> R.string.action_snapshots
        LifecycleMenuItem.Backups -> R.string.action_backups
        LifecycleMenuItem.Migrate -> R.string.action_migrate
        LifecycleMenuItem.Clone -> R.string.action_clone
        LifecycleMenuItem.ConvertToTemplate -> R.string.action_convert_to_template
        LifecycleMenuItem.Delete -> R.string.action_delete_guest
    }

/** Items that change or remove the guest for good, shown apart and in the error color. */
val LifecycleMenuItem.isDestructive: Boolean
    get() = this == LifecycleMenuItem.ConvertToTemplate || this == LifecycleMenuItem.Delete
