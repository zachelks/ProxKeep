@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.backupserver

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.BackupGroup
import io.github.zachelks.proxkeep.core.api.pbs.BackupSnapshot
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

private fun snapshotTime(context: Context, snapshot: BackupSnapshot): String =
    DateUtils.formatDateTime(context, snapshot.time * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME)

/** One backup group's snapshots: verifying, protecting, deleting and pruning them. */
@Composable
fun BackupGroupScreen(store: String, ns: String, type: String, id: String, onClose: () -> Unit) {
    val container = appContainer()
    val group = BackupGroup(type, id)
    val viewModel = viewModel(key = "group:$store:$ns:${group.key}") { BackupGroupViewModel(container.repository, container.refresh, store, ns, group) }
    val snapshots by viewModel.snapshots.collectAsStateWithLifecycle()
    val preview by viewModel.preview.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ShowMessages(viewModel.messages, snackbar)
    val context = LocalContext.current

    var askPrune by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BackupSnapshot?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(group.key)
                            Text(listOf(store, ns).filter { it.isNotEmpty() }.joinToString(" / "), style = MaterialTheme.typography.bodySmall)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = { askPrune = true }, enabled = !busy) { Text(stringResource(R.string.pbs_action_prune)) }
                        IconButton(onClick = viewModel::retry) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val state = snapshots) {
            Loadable.Loading -> CenteredProgress(Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(state.message.text(), Modifier.padding(innerPadding), stringResource(R.string.action_retry), viewModel::retry)
            is Loadable.Loaded -> {
                if (state.value.isEmpty()) {
                    MessageWithAction(stringResource(R.string.pbs_no_snapshots), Modifier.padding(innerPadding))
                    return@Scaffold
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(state.value, key = { it.time }) { snapshot ->
                        SnapshotRow(
                            snapshot = snapshot,
                            time = snapshotTime(context, snapshot),
                            enabled = !busy,
                            onVerify = { viewModel.verify(snapshot) },
                            onProtect = { viewModel.setProtected(snapshot, !snapshot.protected) },
                            onDelete = { deleting = snapshot },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (askPrune) {
        PruneDialog(
            title = stringResource(R.string.pbs_prune_group_title, group.key),
            body = stringResource(R.string.pbs_prune_group_body),
            confirmLabel = stringResource(R.string.pbs_action_preview),
            onDismiss = { askPrune = false },
            onConfirm = { options ->
                askPrune = false
                viewModel.previewPrune(options)
            },
        )
    }
    preview?.let { current ->
        val removed = current.results.filter { !it.keep }
        ConfirmDialog(
            title = stringResource(R.string.pbs_prune_group_title, group.key),
            body = if (removed.isEmpty()) {
                stringResource(R.string.pbs_prune_nothing)
            } else {
                pluralStringResource(R.plurals.pbs_prune_removes, removed.size, removed.size, current.results.size - removed.size)
            },
            confirmLabel = stringResource(R.string.pbs_action_prune),
            destructive = true,
            confirmEnabled = removed.isNotEmpty(),
            onConfirm = viewModel::confirmPrune,
            onDismiss = viewModel::cancelPrune,
            extra = {
                Column(modifier = Modifier.heightIn(max = 240.dp).padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    removed.take(PREVIEW_ROWS).forEach { result ->
                        Text(
                            DateUtils.formatDateTime(context, result.time * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_SHOW_TIME),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (removed.size > PREVIEW_ROWS) {
                        Text(pluralStringResource(R.plurals.pbs_prune_more, removed.size - PREVIEW_ROWS, removed.size - PREVIEW_ROWS))
                    }
                }
            },
        )
    }
    deleting?.let { snapshot ->
        ConfirmDialog(
            title = stringResource(R.string.pbs_delete_snapshot_title, snapshotTime(context, snapshot)),
            body = stringResource(R.string.pbs_delete_snapshot_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                deleting = null
                viewModel.delete(snapshot)
            },
            onDismiss = { deleting = null },
        )
    }
}

private const val PREVIEW_ROWS = 8

@Composable
private fun SnapshotRow(
    snapshot: BackupSnapshot,
    time: String,
    enabled: Boolean,
    onVerify: () -> Unit,
    onProtect: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val (verification, tone) = when {
        snapshot.verification == null -> R.string.pbs_not_verified to StatusTone.Neutral
        snapshot.verification!!.passed -> R.string.pbs_verified_ok to StatusTone.Good
        else -> R.string.pbs_verify_failed to StatusTone.Bad
    }
    val details = listOfNotNull(
        snapshot.size?.let(::formatBytes),
        stringResource(R.string.pbs_protected_label).takeIf { snapshot.protected },
        stringResource(R.string.pbs_encrypted).takeIf { snapshot.encrypted },
        snapshot.comment?.takeIf { it.isNotBlank() },
    )
    ListItem(
        headlineContent = { Text(time) },
        supportingContent = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) { StatusLabel(stringResource(verification), tone) }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, time))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.pbs_action_verify)) }, onClick = {
                        expanded = false
                        onVerify()
                    })
                    DropdownMenuItem(
                        text = { Text(stringResource(if (snapshot.protected) R.string.pbs_action_unprotect else R.string.pbs_action_protect)) },
                        onClick = {
                            expanded = false
                            onProtect()
                        },
                    )
                    // A protected snapshot cannot be deleted until its protection is removed.
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, enabled = !snapshot.protected, onClick = {
                        expanded = false
                        onDelete()
                    })
                }
            }
        },
    )
}
