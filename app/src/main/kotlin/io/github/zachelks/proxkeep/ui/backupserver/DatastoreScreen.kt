@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.backupserver

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.encodePathValue
import io.github.zachelks.proxkeep.core.api.pbs.BackupGroup
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.CheckRow
import io.github.zachelks.proxkeep.ui.components.MenuField
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text
import kotlinx.coroutines.flow.Flow

/** Shows each message of [messages] in [snackbar], with its detail on a second line. */
@Composable
fun ShowMessages(messages: Flow<UiMessage>, snackbar: SnackbarHostState) {
    val resources = LocalResources.current
    LaunchedEffect(messages, resources) {
        messages.collect { message ->
            val base = resources.getString(message.id)
            snackbar.showSnackbar(if (message.detail.isNullOrBlank()) base else "$base\n${message.detail.trim()}")
        }
    }
}

/** One datastore: its space, its backup groups by namespace, and garbage collection, verification and pruning. */
@Composable
fun DatastoreScreen(store: String, onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val viewModel = viewModel(key = "datastore:$store") { DatastoreViewModel(container.repository, container.refresh, store) }
    val usage by viewModel.usage.collectAsStateWithLifecycle()
    val namespaces by viewModel.namespaces.collectAsStateWithLifecycle()
    val namespace by viewModel.namespace.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    ShowMessages(viewModel.messages, snackbar)

    var menu by remember { mutableStateOf(false) }
    var askVerify by rememberSaveable { mutableStateOf(false) }
    var askPrune by rememberSaveable { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<BackupGroup?>(null) }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(store) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::retry) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                        Box {
                            IconButton(onClick = { menu = true }, enabled = !busy) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, store))
                            }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.pbs_action_gc)) }, onClick = {
                                    menu = false
                                    viewModel.collectGarbage()
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pbs_action_verify_all)) }, onClick = {
                                    menu = false
                                    askVerify = true
                                })
                                DropdownMenuItem(text = { Text(stringResource(R.string.pbs_action_prune_all)) }, onClick = {
                                    menu = false
                                    askPrune = true
                                })
                                val settings = stringResource(R.string.pbs_datastore_settings, store)
                                DropdownMenuItem(text = { Text(stringResource(R.string.pbs_action_datastore_settings)) }, onClick = {
                                    menu = false
                                    onOpenRoute(Route.SchemaEdit(settings, "/config/datastore/${encodePathValue(store)}", "/config/datastore/{name}", "PUT"))
                                })
                            }
                        }
                    },
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(bottom = 24.dp)) {
            (usage as? Loadable.Loaded)?.value?.let { current ->
                item(key = "usage") { Box(modifier = Modifier.padding(16.dp)) { DatastoreCard(current, onClick = null) } }
            }
            if (namespaces.size > 1) {
                item(key = "namespace") {
                    MenuField(
                        label = stringResource(R.string.pbs_namespace),
                        options = namespaces.map { it.ns },
                        selected = namespace,
                        optionLabel = { it.ifEmpty { "/" } },
                        onSelect = viewModel::selectNamespace,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            when (val state = groups) {
                Loadable.Loading -> item(key = "loading") { CenteredProgress(Modifier.padding(32.dp)) }
                is Loadable.Failed -> item(key = "failed") {
                    MessageWithAction(state.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = viewModel::retry)
                }
                is Loadable.Loaded -> {
                    if (state.value.isEmpty()) {
                        item(key = "empty") { Text(stringResource(R.string.pbs_no_groups), modifier = Modifier.padding(16.dp)) }
                    }
                    items(state.value, key = { it.key }) { group ->
                        GroupRow(
                            group = group,
                            enabled = !busy,
                            onOpen = { onOpenRoute(Route.BackupGroup(store, namespace, group.type, group.id)) },
                            onDelete = { deleting = group },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (askVerify) {
        var skipVerified by rememberSaveable { mutableStateOf(true) }
        ConfirmDialog(
            title = stringResource(R.string.pbs_verify_all_title, store),
            body = stringResource(R.string.pbs_verify_all_body),
            confirmLabel = stringResource(R.string.pbs_action_verify),
            destructive = false,
            onConfirm = {
                askVerify = false
                viewModel.verifyAll(skipVerified)
            },
            onDismiss = { askVerify = false },
            extra = { CheckRow(stringResource(R.string.pbs_skip_verified), skipVerified, Modifier.padding(top = 8.dp)) { skipVerified = it } },
        )
    }
    if (askPrune) {
        PruneDialog(
            title = stringResource(R.string.pbs_prune_all_title, store),
            body = stringResource(R.string.pbs_prune_all_body),
            confirmLabel = stringResource(R.string.pbs_action_prune),
            onDismiss = { askPrune = false },
            onConfirm = { options ->
                askPrune = false
                viewModel.pruneAll(options)
            },
        )
    }
    deleting?.let { group ->
        ConfirmDialog(
            title = stringResource(R.string.pbs_delete_group_title, group.key),
            body = stringResource(R.string.pbs_delete_group_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                deleting = null
                viewModel.deleteGroup(group)
            },
            onDismiss = { deleting = null },
        )
    }
}

/** What a backup type is, such as a virtual machine. */
fun backupTypeLabel(type: String): Int = when (type) {
    "vm" -> R.string.pbs_type_vm
    "ct" -> R.string.pbs_type_ct
    else -> R.string.pbs_type_host
}

@Composable
private fun GroupRow(group: BackupGroup, enabled: Boolean, onOpen: () -> Unit, onDelete: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val details = listOfNotNull(
        stringResource(backupTypeLabel(group.type)),
        group.count?.let { pluralStringResource(R.plurals.pbs_snapshot_count, it.toInt(), it.toInt()) },
        group.lastBackup?.let {
            stringResource(R.string.pbs_last_backup, DateUtils.formatDateTime(context, it * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME))
        },
    )
    ListItem(
        headlineContent = { Text(listOfNotNull(group.key, group.comment?.takeIf { it.isNotBlank() }).joinToString(" · ")) },
        supportingContent = { Text(details.joinToString(" · "), style = MaterialTheme.typography.bodyMedium) },
        trailingContent = {
            Box {
                IconButton(onClick = { expanded = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, group.key))
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = {
                        expanded = false
                        onDelete()
                    })
                }
            }
        },
        modifier = Modifier.clickable(onClick = onOpen),
    )
}
