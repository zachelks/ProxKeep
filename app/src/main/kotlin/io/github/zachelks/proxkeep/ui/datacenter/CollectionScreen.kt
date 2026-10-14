@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.datacenter

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text

@Composable
fun collectionViewModel(spec: CollectionSpec, args: Map<String, String>): CollectionViewModel {
    val container = appContainer()
    return viewModel(key = "collection:${spec.id}:$args") { CollectionViewModel(container.repository, container.schemas, container.refresh, spec, args) }
}

/**
 * One datacenter collection: its items, with forms built from the server's
 * schema to edit and create them.
 *
 * @param subtitle names the parent an item list belongs to, such as the IP set whose entries these are
 */
@Composable
fun CollectionScreen(specId: String, args: Map<String, String>, subtitle: String?, onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val spec = Collections.get(specId) ?: run {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val viewModel = collectionViewModel(spec, args)
    val items by viewModel.items.collectAsStateWithLifecycle()
    val abilities by viewModel.abilities.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmDelete by remember { mutableStateOf<CollectionItem?>(null) }
    var createMenu by remember { mutableStateOf(false) }
    var askId by remember { mutableStateOf(false) }
    val title = stringResource(spec.title)
    val newTitle = stringResource(R.string.dc_new, title)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val base = resources.getString(message.id)
            snackbar.showSnackbar(if (message.detail.isNullOrBlank()) base else "$base\n${message.detail.trim()}")
        }
    }

    val create: (String?) -> Unit = { type ->
        val label = type?.let { "$newTitle: $it" } ?: newTitle
        val presets = type?.let { mapOf("type" to it) }.orEmpty()
        onOpenRoute(
            if (spec.aclStyle) {
                Route.SchemaEdit(label, "/access/acl", "/access/acl", "PUT", read = false)
            } else {
                Route.SchemaEdit(label, spec.list(args), spec.listPath, "POST", exclude = spec.exclude, presets = presets)
            },
        )
    }
    val edit: (CollectionItem) -> Unit = { item ->
        spec.item(args, item.id)?.let { path ->
            onOpenRoute(Route.SchemaEdit("$title: ${item.id}", path, spec.itemPath!!, "PUT", exclude = spec.exclude + listOfNotNull("type".takeIf { spec.types.isNotEmpty() })))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text(title)
                            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        IconButton(onClick = viewModel::retry) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                )
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            if (abilities.create) {
                Box {
                    ExtendedFloatingActionButton(
                        onClick = {
                            when {
                                spec.createWithId -> askId = true
                                spec.types.isNotEmpty() -> createMenu = true
                                else -> create(null)
                            }
                        },
                        text = { Text(stringResource(R.string.action_create_guest)) },
                        icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                    )
                    DropdownMenu(expanded = createMenu, onDismissRequest = { createMenu = false }) {
                        spec.types.forEach { type ->
                            DropdownMenuItem(text = { Text(type) }, onClick = {
                                createMenu = false
                                create(type)
                            })
                        }
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val state = items) {
            Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(state.message.text(), Modifier.padding(innerPadding), stringResource(R.string.action_retry), viewModel::retry)
            is Loadable.Loaded -> {
                if (state.value.isEmpty()) {
                    MessageWithAction(stringResource(R.string.dc_empty), Modifier.padding(innerPadding))
                    return@Scaffold
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.value, key = { it.id }) { item ->
                        val readOnly = viewModel.isReadOnly(item)
                        ItemRow(
                            spec = spec,
                            item = item,
                            onOpen = when {
                                spec.child != null -> {
                                    {
                                        val childArgs = args + (spec.itemParam.orEmpty() to item.id)
                                        onOpenRoute(Route.Collection(spec.child, childArgs, item.id))
                                    }
                                }
                                abilities.edit && !readOnly -> { { edit(item) } }
                                else -> null
                            },
                            menu = buildList {
                                if (spec.child != null && abilities.edit && !readOnly) add(stringResource(R.string.action_edit) to { edit(item) })
                                spec.actions.forEach { action -> add(stringResource(action.label) to { viewModel.run(item, action) }) }
                                if (abilities.delete && !readOnly) add(stringResource(R.string.action_delete) to { confirmDelete = item })
                            },
                            enabled = !busy,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmDelete?.let { item ->
        ConfirmDialog(
            title = stringResource(R.string.dc_confirm_delete_title, item.id),
            body = stringResource(R.string.dc_confirm_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = null
                viewModel.delete(item)
            },
            onDismiss = { confirmDelete = null },
        )
    }
    if (askId) {
        IdDialog(onDismiss = { askId = false }) { id ->
            askId = false
            spec.item(args, id)?.let { path -> onOpenRoute(Route.SchemaEdit("$newTitle: $id", path, spec.itemPath!!, "POST", exclude = spec.exclude)) }
        }
    }
}

@Composable
private fun ItemRow(spec: CollectionSpec, item: CollectionItem, onOpen: (() -> Unit)?, menu: List<Pair<String, () -> Unit>>, enabled: Boolean) {
    var expanded by remember { mutableStateOf(false) }
    val headline = spec.titleKeys.mapNotNull { item.values[it]?.takeIf { value -> value.isNotBlank() } }.joinToString(" · ").ifEmpty { item.id }
    val details = spec.detailKeys.filter { it !in spec.titleKeys }.mapNotNull { key -> item.values[key]?.takeIf { it.isNotBlank() }?.let { "$key: $it" } }
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = details.takeIf { it.isNotEmpty() }?.let { { Text(details.joinToString(" · "), maxLines = 3) } },
        trailingContent = if (menu.isEmpty()) null else {
            {
                Box {
                    IconButton(onClick = { expanded = true }, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, item.id))
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
        modifier = if (onOpen != null && enabled) Modifier.clickable(onClick = onOpen) else Modifier,
    )
}

/** Asks for a new item's ID, for collections that create items at a path naming them. */
@Composable
private fun IdDialog(onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var id by rememberSaveable { mutableStateOf("") }
    val valid = id.isNotBlank() && id.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dc_new_id)) },
        text = {
            OutlinedTextField(
                value = id,
                onValueChange = { id = it.trim() },
                singleLine = true,
                label = { Text(stringResource(R.string.dc_id)) },
                isError = id.isNotEmpty() && !valid,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(id) }, enabled = valid) { Text(stringResource(R.string.action_next)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
