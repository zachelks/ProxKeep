package io.github.zachelks.proxkeep.ui.lifecycle

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.RadioChoices
import io.github.zachelks.proxkeep.ui.components.WholeNumberField
import io.github.zachelks.proxkeep.ui.create.isFreeId
import io.github.zachelks.proxkeep.ui.text

/** What restoring a backup as a new guest offers: a free ID and the storages its disks can go to. */
data class RestoreChoices(val nextId: Int, val storages: List<String>, val takenIds: Set<Int>)

/** Reads a free ID and the storages on [node] that hold [kind]'s disks. */
suspend fun readRestoreChoices(servers: ServerRepository, node: String, kind: ResourceKind, takenIds: Set<Int>): RestoreChoices {
    val content = if (kind == ResourceKind.Qemu) "images" else "rootdir"
    val nextId = servers.request { it.nextId() }
    val storages = servers.request { it.nodeStorage(node) }.filter { it.isUsable && content in it.contentTypes }.map { it.storage }.sorted()
    return RestoreChoices(nextId, storages, takenIds)
}

/**
 * Restores a backup as a new guest beside the one it was taken from. The
 * new guest gets its own ID and, unless asked otherwise, its own network
 * addresses, so the two can run side by side.
 *
 * @param onRestore the new ID, the storage for its disks (null for where they were), whether to make it unique, and whether to start it
 */
@Composable
fun RestoreAsNewDialog(
    archiveName: String,
    choices: Loadable<RestoreChoices>?,
    onLoad: () -> Unit,
    onDismiss: () -> Unit,
    onRestore: (vmid: Int, storage: String?, unique: Boolean, start: Boolean) -> Unit,
) {
    LaunchedEffect(Unit) { onLoad() }
    var vmid by rememberSaveable { mutableStateOf("") }
    var storage by rememberSaveable { mutableStateOf<String?>(null) }
    var unique by rememberSaveable { mutableStateOf(true) }
    var start by rememberSaveable { mutableStateOf(false) }
    val loaded = (choices as? Loadable.Loaded)?.value
    LaunchedEffect(loaded) { if (loaded != null && vmid.isEmpty()) vmid = loaded.nextId.toString() }
    val valid = loaded != null && isFreeId(vmid, loaded.takenIds)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.restore_new_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(archiveName, style = MaterialTheme.typography.bodyMedium)
                when (choices) {
                    null, Loadable.Loading -> CircularProgressIndicator(modifier = Modifier.padding(16.dp))
                    is Loadable.Failed -> Text(choices.message.text(), color = MaterialTheme.colorScheme.error)
                    is Loadable.Loaded -> {
                        val taken = vmid.toIntOrNull()?.let { it in choices.value.takenIds } == true
                        WholeNumberField(
                            stringResource(R.string.create_id), vmid, { vmid = it }, 100, 999_999_999,
                            error = stringResource(R.string.create_id_range),
                            supportingText = if (taken) stringResource(R.string.create_id_taken) else null,
                        )
                        Text(stringResource(R.string.restore_storage), style = MaterialTheme.typography.titleSmall)
                        val same = stringResource(R.string.restore_same_storage)
                        RadioChoices(listOf<String?>(null) + choices.value.storages, storage, label = { it ?: same }) { storage = it }
                        CheckRow(stringResource(R.string.restore_unique), unique) { unique = it }
                        CheckRow(stringResource(R.string.restore_start_after), start) { start = it }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onRestore(vmid.toInt(), storage, unique, start) }, enabled = valid) { Text(stringResource(R.string.action_restore)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
