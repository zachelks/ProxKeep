@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.schema

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.text

@Composable
fun schemaEditViewModel(request: SchemaEditRequest): SchemaEditViewModel {
    val container = appContainer()
    return viewModel(key = "schema:${request.method}:${request.path}") {
        SchemaEditViewModel(container.repository, container.schemas, container.refresh, request)
    }
}

/**
 * Edits or creates one API object with a form built from the server's
 * schema. Settings that are required or set come first; the rest are one
 * tap away.
 */
@Composable
fun SchemaEditScreen(
    title: String,
    request: SchemaEditRequest,
    onClose: () -> Unit,
    viewModel: SchemaEditViewModel = schemaEditViewModel(request),
) {
    val form by viewModel.form.collectAsStateWithLifecycle()
    val saving by viewModel.saving.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var showAll by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(viewModel) { viewModel.saved.collect { onClose() } }
    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val base = resources.getString(message.id)
            snackbar.showSnackbar(if (message.detail.isNullOrBlank()) base else "$base\n${message.detail.trim()}")
        }
    }

    val loaded = (form as? Loadable.Loaded)?.value
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_cancel))
                        }
                    },
                    actions = {
                        val canSave = loaded != null && loaded.isValid && (request.method == "POST" || !loaded.changes.isEmpty) && !saving
                        Button(onClick = viewModel::save, enabled = canSave, modifier = Modifier.padding(end = 8.dp)) {
                            Text(stringResource(if (request.method == "POST") R.string.action_create else R.string.action_save))
                        }
                    },
                )
                if (saving) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val state = form) {
            Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(
                state.message.text(),
                modifier = Modifier.padding(innerPadding),
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::load,
            )
            is Loadable.Loaded -> {
                val current = state.value
                val (visible, hidden) = visibleFields(current, showAll)
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).imePadding(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    items(visible, key = { it.key }) { field ->
                        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            SchemaFieldEditor(
                                key = field.key,
                                schema = field.schema,
                                value = current.value(field.key),
                                onValueChange = { viewModel.edit(field.key, it) },
                                problem = current.problem(field.key),
                                serverError = current.serverErrors[field.key],
                                enabled = !saving,
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                    }
                    if (hidden > 0) {
                        item {
                            TextButton(onClick = { showAll = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                                Text(pluralStringResource(R.plurals.config_show_all, hidden, hidden))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Required settings, those with a value and those changed, unless [showAll]; and how many are left out. */
fun visibleFields(form: SchemaForm, showAll: Boolean): Pair<List<FormField>, Int> {
    val ordered = form.fields.sortedBy { if (!it.schema.optional && it.schema.default == null) 0 else 1 }
    if (showAll) return ordered to 0
    val visible = ordered.filter { field ->
        (!field.schema.optional && field.schema.default == null) || form.initial[field.key].orEmpty().isNotEmpty() || form.isChanged(field.key)
    }
    // A form with nothing set and nothing required would be empty; show everything instead.
    if (visible.isEmpty()) return ordered to 0
    return visible to ordered.size - visible.size
}
