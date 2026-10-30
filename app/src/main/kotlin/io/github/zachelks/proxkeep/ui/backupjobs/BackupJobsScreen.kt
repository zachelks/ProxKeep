@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.backupjobs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.BackupJob
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

/** Settings a backup job's form does not offer: its ID is fixed, and the type is always vzdump. */
private val JOB_FORM_EXCLUDE = listOf("id", "type")

/** The datacenter's scheduled backup jobs. Jobs are edited and created with forms built from the server's schema. */
@Composable
fun BackupJobsScreen(onClose: () -> Unit, onOpenRoute: (Route) -> Unit) {
    val container = appContainer()
    val viewModel = viewModel { BackupJobsViewModel(container.repository, container.cluster, container.refresh) }
    val jobs by viewModel.jobs.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val access by container.access.access.collectAsStateWithLifecycle()
    val canModify = access?.allows(listOf("/"), Privilege.SYS_MODIFY) ?: true
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current
    var confirmDelete by remember { mutableStateOf<BackupJob?>(null) }
    var confirmRun by remember { mutableStateOf<BackupJob?>(null) }
    val editTitle = stringResource(R.string.backup_job_edit)
    val newTitle = stringResource(R.string.backup_job_new)

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            val base = resources.getString(message.id)
            snackbar.showSnackbar(if (message.detail.isNullOrBlank()) base else "$base\n${message.detail.trim()}")
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text(stringResource(R.string.backup_jobs_title)) },
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
            if (canModify) {
                ExtendedFloatingActionButton(
                    onClick = { onOpenRoute(Route.SchemaEdit(newTitle, "/cluster/backup", "/cluster/backup", "POST", exclude = listOf("type"))) },
                    text = { Text(stringResource(R.string.action_create_guest)) },
                    icon = { Icon(painterResource(R.drawable.ic_add), contentDescription = null) },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val state = jobs) {
            Loadable.Loading -> CenteredProgress(modifier = Modifier.padding(innerPadding))
            is Loadable.Failed -> MessageWithAction(state.message.text(), Modifier.padding(innerPadding), stringResource(R.string.action_retry), viewModel::retry)
            is Loadable.Loaded -> {
                if (state.value.isEmpty()) {
                    MessageWithAction(stringResource(R.string.backup_jobs_empty), Modifier.padding(innerPadding))
                    return@Scaffold
                }
                LazyColumn(modifier = Modifier.fillMaxSize().padding(innerPadding), contentPadding = PaddingValues(bottom = 96.dp)) {
                    items(state.value, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            canModify = canModify && !busy,
                            onEdit = {
                                onOpenRoute(Route.SchemaEdit("$editTitle: ${job.id}", "/cluster/backup/${job.id}", "/cluster/backup/{id}", "PUT", exclude = JOB_FORM_EXCLUDE))
                            },
                            onRun = { confirmRun = job },
                            onToggle = { viewModel.setEnabled(job, !job.enabled) },
                            onDelete = { confirmDelete = job },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    confirmRun?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_run_backup_title),
            body = stringResource(R.string.confirm_run_backup_body, describeSelection(job)),
            confirmLabel = stringResource(R.string.action_run_now),
            destructive = false,
            onConfirm = {
                confirmRun = null
                viewModel.runNow(job)
            },
            onDismiss = { confirmRun = null },
        )
    }
    confirmDelete?.let { job ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_delete_backup_job_title),
            body = stringResource(R.string.confirm_delete_backup_job_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirmDelete = null
                viewModel.delete(job)
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun describeSelection(job: BackupJob): String = when {
    job.all && job.excluded.isNotEmpty() -> stringResource(R.string.backup_job_all_except, job.excluded.joinToString(", "))
    job.all -> stringResource(R.string.backup_job_all)
    job.pool != null -> stringResource(R.string.backup_job_pool, job.pool!!)
    else -> stringResource(R.string.backup_job_guests, job.guests.joinToString(", "))
}

@Composable
private fun JobRow(job: BackupJob, canModify: Boolean, onEdit: () -> Unit, onRun: () -> Unit, onToggle: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(job.comment?.takeIf { it.isNotBlank() } ?: job.schedule ?: job.id) },
        supportingContent = {
            Column {
                Text(listOfNotNull(job.schedule, job.storage, job.node).joinToString(" · "))
                Text(describeSelection(job), style = MaterialTheme.typography.bodySmall)
            }
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!job.enabled) StatusLabel(stringResource(R.string.backup_job_off), StatusTone.Neutral)
                if (canModify) {
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, job.id))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_run_now)) }, onClick = {
                                expanded = false
                                onRun()
                            })
                            DropdownMenuItem(text = { Text(stringResource(if (job.enabled) R.string.action_disable else R.string.action_enable)) }, onClick = {
                                expanded = false
                                onToggle()
                            })
                            HorizontalDivider()
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }, onClick = {
                                expanded = false
                                onDelete()
                            })
                        }
                    }
                }
            }
        },
        modifier = if (canModify) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}
