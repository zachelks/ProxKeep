@file:OptIn(ExperimentalMaterial3AdaptiveApi::class, ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.tasks

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.ui.MainViewModel
import io.github.zachelks.proxkeep.ui.TasksState
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MainTopBar
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

private const val LOG_POLL_MS = 1_500L

/** The server's stand-in for a log with nothing in it yet. */
private const val EMPTY_LOG_PLACEHOLDER = "no content"

/**
 * The Tasks destination: recent and running tasks, and the log of the
 * selected one.
 */
@Composable
fun TasksPane(viewModel: MainViewModel) {
    val navigator = rememberListDetailPaneScaffoldNavigator<String>()
    val scope = rememberCoroutineScope()
    val state by viewModel.tasks.collectAsStateWithLifecycle()

    DisposableEffect(Unit) {
        viewModel.setTasksVisible(true)
        onDispose { viewModel.setTasksVisible(false) }
    }

    val selectedUpid = navigator.currentDestination?.contentKey
    val listIsHidden = navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] == PaneAdaptedValue.Hidden

    NavigableListDetailPaneScaffold(
        navigator = navigator,
        listPane = {
            AnimatedPane {
                TaskList(
                    viewModel = viewModel,
                    state = state,
                    selectedUpid = selectedUpid,
                    onSelect = { upid ->
                        scope.launch { navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, upid) }
                    },
                )
            }
        },
        detailPane = {
            AnimatedPane {
                val task = (state as? TasksState.Loaded)?.tasks?.firstOrNull { it.upid == selectedUpid }
                when {
                    selectedUpid == null -> MessageWithAction(message = stringResource(R.string.task_select_prompt))
                    task == null -> MessageWithAction(message = stringResource(R.string.task_gone))
                    else -> TaskDetailPane(
                        viewModel = viewModel,
                        task = task,
                        showBack = listIsHidden,
                        onBack = { scope.launch { navigator.navigateBack() } },
                    )
                }
            }
        },
    )
}

@Composable
private fun TaskList(
    viewModel: MainViewModel,
    state: TasksState,
    selectedUpid: String?,
    onSelect: (String) -> Unit,
) {
    Scaffold(
        topBar = { MainTopBar(title = stringResource(R.string.destination_tasks), viewModel = viewModel) },
    ) { innerPadding ->
        val modifier = Modifier.fillMaxSize().padding(innerPadding)
        when (state) {
            TasksState.Loading -> CenteredProgress(modifier)
            is TasksState.Failed -> MessageWithAction(
                message = state.message.text(),
                modifier = modifier,
                actionLabel = stringResource(R.string.action_retry),
                onAction = viewModel::refreshNow,
            )
            is TasksState.Loaded -> if (state.tasks.isEmpty()) {
                MessageWithAction(message = stringResource(R.string.no_tasks), modifier = modifier)
            } else {
                LazyColumn(modifier = modifier) {
                    state.refreshError?.let { error ->
                        item(key = "stale") {
                            Text(
                                text = stringResource(R.string.refresh_failed) + "\n" + error.text(),
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    }
                    items(state.tasks, key = { it.upid }) { task ->
                        TaskRow(task = task, selected = task.upid == selectedUpid, onClick = { onSelect(task.upid) })
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskRow(task: TaskSummary, selected: Boolean, onClick: () -> Unit) {
    val status = task.statusText()
    val details = listOfNotNull(
        task.node,
        task.user,
        task.starttime?.let { formatTaskTime(it) },
    ).joinToString(" · ")
    ListItem(
        headlineContent = { Text(taskTitle(task.type, task.id)) },
        supportingContent = { Text(details) },
        trailingContent = {
            if (task.isRunning) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(text = status.text, style = MaterialTheme.typography.labelLarge)
                }
            } else {
                StatusLabel(text = status.text, tone = status.tone)
            }
        },
        colors = if (selected) {
            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        } else {
            ListItemDefaults.colors()
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** One task's outcome and log. The log follows the task while it runs. */
@Composable
private fun TaskDetailPane(
    viewModel: MainViewModel,
    task: TaskSummary,
    showBack: Boolean,
    onBack: () -> Unit,
) {
    val lines = remember(task.upid) { mutableStateListOf<String>() }
    var status by remember(task.upid) { mutableStateOf<TaskStatus?>(null) }
    var error by remember(task.upid) { mutableStateOf<UiMessage?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(task.upid) {
        while (true) {
            var drained = false
            try {
                val progress = viewModel.readTask(task.node, task.upid, lines.size)
                status = progress.status
                error = null
                val fresh = progress.newLines.map { it.t }
                val isPlaceholder = lines.isEmpty() && fresh.size == 1 && fresh.first() == EMPTY_LOG_PLACEHOLDER
                if (!isPlaceholder) lines.addAll(fresh)
                val pageWasFull = progress.newLines.size >= MainViewModel.LOG_PAGE_SIZE
                // More of a long log is waiting: fetch it without a pause.
                if (pageWasFull) continue
                drained = !progress.status.isRunning
            } catch (e: PveApiException) {
                // The server refused; asking again will not help.
                error = e.toUiMessage()
                drained = true
            } catch (e: IOException) {
                error = e.toUiMessage()
            }
            if (drained) break
            delay(LOG_POLL_MS)
        }
    }

    // Keep the newest line in view while the task is still producing output.
    LaunchedEffect(lines.size) {
        if (status?.isRunning == true && lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(taskTitle(task.type, task.id)) },
                navigationIcon = {
                    if (showBack) {
                        IconButton(onClick = onBack) {
                            Icon(
                                painter = painterResource(R.drawable.ic_arrow_back),
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        SelectionContainer(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "summary") { TaskSummaryBlock(task = task, status = status, error = error) }
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskSummaryBlock(task: TaskSummary, status: TaskStatus?, error: UiMessage?) {
    val running = status?.isRunning ?: task.isRunning
    val exitStatus = status?.exitstatus ?: task.status
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = listOfNotNull(
                task.node,
                task.user,
                task.starttime?.let { stringResource(R.string.task_started, formatTaskTime(it)) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (running) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text(text = stringResource(R.string.task_status_running), style = MaterialTheme.typography.labelLarge)
            }
        } else if (exitStatus != null) {
            val succeeded = exitStatus == "OK"
            Surface(
                color = if (succeeded) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer,
                contentColor = if (succeeded) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onErrorContainer,
                shape = MaterialTheme.shapes.small,
            ) {
                Text(
                    text = stringResource(R.string.task_result, exitStatus),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
        error?.let {
            Text(text = it.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        HorizontalDivider()
    }
}
