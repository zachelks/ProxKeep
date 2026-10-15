@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.cluster.ClusterContent
import io.github.zachelks.proxkeep.ui.cluster.statusText
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel

/**
 * Full-screen search over the signed-in server's guests, nodes and storage.
 *
 * @param onOpen called with the resource the person picked
 */
@Composable
fun SearchScreen(viewModel: SearchViewModel, onClose: () -> Unit, onOpen: (ClusterResource) -> Unit) {
    val results by viewModel.results.collectAsStateWithLifecycle()
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = {
                    TextField(
                        value = viewModel.query,
                        onValueChange = { viewModel.query = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                },
                actions = {
                    if (viewModel.query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.query = "" }) {
                            Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.action_clear_search))
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        ClusterContent(state = results.cluster, onRetry = viewModel::retry, modifier = modifier) {
            val hits = results.hits.orEmpty()
            when {
                results.query.isBlank() -> MessageWithAction(message = stringResource(R.string.search_prompt), modifier = modifier)
                hits.isEmpty() -> MessageWithAction(
                    message = stringResource(R.string.search_no_results, results.query.trim()),
                    modifier = modifier,
                )
                else -> LazyColumn(modifier = modifier.fillMaxSize()) {
                    items(hits, key = { it.id }) { resource ->
                        SearchRow(resource, onClick = { onOpen(resource) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchRow(resource: ClusterResource, onClick: () -> Unit) {
    val status = resource.statusText()
    val kind = stringResource(
        when (resource.kind) {
            ResourceKind.Lxc -> R.string.kind_container
            ResourceKind.Node -> R.string.kind_node
            ResourceKind.Storage -> R.string.kind_storage
            else -> R.string.kind_vm
        },
    )
    val details = when (resource.kind) {
        ResourceKind.Node -> listOf(kind)
        else -> listOfNotNull(kind, resource.vmid?.toString(), resource.node)
    }
    ListItem(
        headlineContent = { Text(resource.title) },
        supportingContent = { Text(details.joinToString(" · ")) },
        trailingContent = { StatusLabel(text = status.text, tone = status.tone) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}
