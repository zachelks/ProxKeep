@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.nodes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.NodeService
import io.github.zachelks.proxkeep.core.api.model.NodeStatus
import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.Privilege
import io.github.zachelks.proxkeep.core.api.model.ServiceAction
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatUptime
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.guests.UsageHistoryCard
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

@Composable
fun nodeViewModel(node: String): NodeViewModel {
    val container = appContainer()
    return viewModel(key = "node:$node") { NodeViewModel(container.repository, container.refresh, container.consoles, node) }
}

enum class NodeTab(val label: Int) {
    Summary(R.string.node_tab_summary),
    Updates(R.string.node_tab_updates),
    Services(R.string.node_tab_services),
    Network(R.string.node_tab_network),
    System(R.string.node_tab_system),
    Disks(R.string.node_tab_disks),
    Log(R.string.node_tab_log),
}

/**
 * Everything about one node: status, updates, services, network, DNS and
 * time, certificates, disks and its log.
 *
 * @param onConsoleOpened called after a shell has been opened on the node, to show it
 * @param onOpenRoute opens a full screen, such as a form for a network interface
 */
@Composable
fun NodeScreen(
    node: String,
    onClose: () -> Unit,
    onConsoleOpened: () -> Unit,
    onOpenRoute: (Route) -> Unit,
    viewModel: NodeViewModel = nodeViewModel(node),
) {
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val access by appContainer().access.access.collectAsStateWithLifecycle()
    val allows = { privilege: String -> access?.allows(listOf(Permissions.nodePath(node)), privilege) ?: true }
    var tab by rememberSaveable { mutableStateOf(NodeTab.Summary) }
    val snackbar = remember { SnackbarHostState() }
    val resources = LocalResources.current

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
                    title = { Text(node) },
                    navigationIcon = {
                        IconButton(onClick = onClose) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        if (allows(Privilege.SYS_CONSOLE)) {
                            IconButton(onClick = {
                                viewModel.openShell()
                                onConsoleOpened()
                            }) {
                                Icon(painterResource(R.drawable.ic_terminal), contentDescription = stringResource(R.string.action_open_shell))
                            }
                        }
                        IconButton(onClick = viewModel::retry) {
                            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.action_refresh))
                        }
                    },
                )
                PrimaryScrollableTabRow(selectedTabIndex = tab.ordinal, edgePadding = 8.dp) {
                    NodeTab.entries.forEach { entry ->
                        Tab(selected = tab == entry, onClick = { tab = entry }, text = { Text(stringResource(entry.label)) })
                    }
                }
                if (busy != null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            val enabled = busy == null
            when (tab) {
                NodeTab.Summary -> SummaryTab(viewModel, enabled, canPower = allows(Privilege.SYS_POWER_MGMT))
                NodeTab.Updates -> UpdatesTab(viewModel, enabled, canModify = allows(Privilege.SYS_MODIFY), canUpgrade = allows(Privilege.SYS_CONSOLE), onConsoleOpened)
                NodeTab.Services -> ServicesTab(viewModel, enabled, canManage = allows(Privilege.SYS_MODIFY))
                NodeTab.Network -> NetworkTab(viewModel, enabled, canModify = allows(Privilege.SYS_MODIFY), onOpenRoute)
                NodeTab.System -> SystemTab(viewModel, enabled, canModify = allows(Privilege.SYS_MODIFY))
                NodeTab.Disks -> DisksTab(viewModel)
                NodeTab.Log -> LogTab(viewModel)
            }
        }
    }
}

/** A list tab's loading, failure and refresh-failure states around its rows. */
@Composable
fun <T> LoadableList(state: Loadable<T>, onRetry: () -> Unit, content: LazyListScope.(T) -> Unit) {
    when (state) {
        Loadable.Loading -> CenteredProgress()
        is Loadable.Failed -> MessageWithAction(state.message.text(), actionLabel = stringResource(R.string.action_retry), onAction = onRetry)
        is Loadable.Loaded -> {
            val stale = state.refreshError?.text()
            LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
                if (stale != null) {
                    item(key = "stale") {
                        Text(
                            stale,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
                content(state.value)
            }
        }
    }
}

fun LazyListScope.sectionTitle(text: Int) {
    item(key = "title:$text") {
        Text(
            stringResource(text),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp),
        )
    }
}

// --- Summary --------------------------------------------------------------

@Composable
private fun SummaryTab(viewModel: NodeViewModel, enabled: Boolean, canPower: Boolean) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val timeframe by viewModel.timeframe.collectAsStateWithLifecycle()
    var confirm by remember { mutableStateOf<Boolean?>(null) }

    LoadableList(status, viewModel::retry) { value ->
        item(key = "status") { StatusCard(value) }
        item(key = "history") {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                UsageHistoryCard(history = history, timeframe = timeframe, onTimeframe = viewModel::selectTimeframe)
            }
        }
        if (canPower) {
            // Rebooting and shutting down stay apart from everything else, and both ask first.
            sectionTitle(R.string.node_power)
            item(key = "power") {
                FlowRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { confirm = true }, enabled = enabled, colors = colors) { Text(stringResource(R.string.action_reboot)) }
                    OutlinedButton(onClick = { confirm = false }, enabled = enabled, colors = colors) { Text(stringResource(R.string.action_shutdown_node)) }
                }
            }
        }
    }

    confirm?.let { reboot ->
        ConfirmDialog(
            title = stringResource(if (reboot) R.string.confirm_reboot_node_title else R.string.confirm_shutdown_node_title, viewModel.node),
            body = stringResource(if (reboot) R.string.confirm_reboot_node_body else R.string.confirm_shutdown_node_body),
            confirmLabel = stringResource(if (reboot) R.string.action_reboot else R.string.action_shutdown_node),
            destructive = true,
            onConfirm = {
                confirm = null
                viewModel.power(reboot)
            },
            onDismiss = { confirm = null },
        )
    }
}

@Composable
private fun StatusCard(status: NodeStatus) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status.cpu?.let { cpu ->
                val cpus = status.cpuinfo?.cpus
                UsageBar(
                    label = stringResource(R.string.usage_cpu),
                    fraction = cpu,
                    detail = cpus?.let { pluralStringResource(R.plurals.node_cpus, it, it) }.orEmpty(),
                )
            }
            listOf(
                R.string.usage_memory to status.memory,
                R.string.node_swap to status.swap,
                R.string.node_root_disk to status.rootfs,
            ).forEach { (label, usage) ->
                val fraction = usageFraction(usage?.used, usage?.total) ?: return@forEach
                UsageBar(
                    label = stringResource(label),
                    fraction = fraction,
                    detail = stringResource(R.string.used_of_total, formatBytes(usage?.used ?: 0), formatBytes(usage?.total ?: 0)),
                )
            }
            HorizontalDivider()
            Fact(R.string.node_uptime, status.uptime?.let(::formatUptime))
            Fact(R.string.node_load, status.loadavg?.joinToString(" "))
            Fact(R.string.node_cpu_model, status.cpuinfo?.model)
            Fact(R.string.node_version, status.pveversion)
            Fact(R.string.node_kernel, status.kversion)
            Fact(
                R.string.node_boot_mode,
                status.bootInfo?.let { boot ->
                    when {
                        boot.mode == null -> null
                        boot.mode == "efi" && boot.secureboot == true -> stringResource(R.string.node_boot_secure)
                        boot.mode == "efi" -> stringResource(R.string.node_boot_efi)
                        else -> stringResource(R.string.node_boot_bios)
                    }
                },
            )
        }
    }
}

@Composable
fun Fact(label: Int, value: String?) {
    if (value.isNullOrBlank()) return
    Column {
        Text(stringResource(label), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// --- Updates --------------------------------------------------------------

@Composable
private fun UpdatesTab(viewModel: NodeViewModel, enabled: Boolean, canModify: Boolean, canUpgrade: Boolean, onConsoleOpened: () -> Unit) {
    val updates by viewModel.updates.collectAsStateWithLifecycle()
    val upgradeTitle = stringResource(R.string.node_upgrade_title, viewModel.node)
    LoadableList(updates, viewModel::retry) { packages ->
        item(key = "actions") {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (packages.isEmpty()) stringResource(R.string.node_up_to_date) else pluralStringResource(R.plurals.node_updates, packages.size, packages.size),
                    style = MaterialTheme.typography.titleMedium,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (canModify) {
                        OutlinedButton(onClick = viewModel::refreshPackages, enabled = enabled) { Text(stringResource(R.string.action_refresh_packages)) }
                    }
                    if (canUpgrade && packages.isNotEmpty()) {
                        FilledTonalButton(onClick = {
                            viewModel.openUpgrade(upgradeTitle)
                            onConsoleOpened()
                        }) { Text(stringResource(R.string.action_upgrade)) }
                    }
                }
            }
        }
        items(packages, key = { it.name }) { update ->
            ListItem(
                headlineContent = { Text(update.name) },
                supportingContent = {
                    Column {
                        Text(stringResource(R.string.node_version_change, update.oldVersion.orEmpty(), update.version.orEmpty()))
                        update.title?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                },
            )
            HorizontalDivider()
        }
    }
}

// --- Services -------------------------------------------------------------

@Composable
private fun ServicesTab(viewModel: NodeViewModel, enabled: Boolean, canManage: Boolean) {
    val services by viewModel.services.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf<NodeService?>(null) }
    LoadableList(services, viewModel::retry) { list ->
        items(list, key = { it.service }) { service ->
            ServiceRow(service, enabled && canManage, onAction = { action ->
                if (action == ServiceAction.Stop) confirmStop = service else viewModel.serviceAction(service.service, action)
            })
            HorizontalDivider()
        }
    }
    confirmStop?.let { service ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_stop_service_title, service.service),
            body = stringResource(R.string.confirm_stop_service_body),
            confirmLabel = stringResource(R.string.action_stop_service),
            destructive = true,
            onConfirm = {
                confirmStop = null
                viewModel.serviceAction(service.service, ServiceAction.Stop)
            },
            onDismiss = { confirmStop = null },
        )
    }
}

@Composable
private fun ServiceRow(service: NodeService, canManage: Boolean, onAction: (ServiceAction) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val (label, tone) = when {
        service.isRunning -> R.string.service_running to StatusTone.Good
        service.unitState == "not-found" -> R.string.service_not_installed to StatusTone.Neutral
        service.unitState == "disabled" || service.unitState == "masked" -> R.string.service_disabled to StatusTone.Neutral
        else -> R.string.service_stopped to StatusTone.Warning
    }
    ListItem(
        headlineContent = { Text(service.service) },
        supportingContent = service.desc?.let { { Text(it) } },
        trailingContent = {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusLabel(stringResource(label), tone)
                    if (canManage && service.unitState != "not-found") {
                        IconButton(onClick = { expanded = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, service.service))
                        }
                    }
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    val actions = if (service.isRunning) listOf(ServiceAction.Restart, ServiceAction.Reload, ServiceAction.Stop) else listOf(ServiceAction.Start)
                    actions.forEach { action ->
                        DropdownMenuItem(text = { Text(stringResource(action.label)) }, onClick = {
                            expanded = false
                            onAction(action)
                        })
                    }
                }
            }
        },
    )
}

private val ServiceAction.label: Int
    get() = when (this) {
        ServiceAction.Start -> R.string.action_start_service
        ServiceAction.Stop -> R.string.action_stop_service
        ServiceAction.Restart -> R.string.action_restart_service
        ServiceAction.Reload -> R.string.action_reload_service
    }
