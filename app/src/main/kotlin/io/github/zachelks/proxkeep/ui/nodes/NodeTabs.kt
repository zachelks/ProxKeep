package io.github.zachelks.proxkeep.ui.nodes

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.NodeCertificate
import io.github.zachelks.proxkeep.core.api.model.NodeDns
import io.github.zachelks.proxkeep.core.api.model.NodeNetwork
import io.github.zachelks.proxkeep.core.api.model.PhysicalDisk
import io.github.zachelks.proxkeep.core.api.model.SmartData
import io.github.zachelks.proxkeep.ui.components.CenteredProgress
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.SuggestField
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone
import java.util.TimeZone

// --- Network --------------------------------------------------------------

/** Interface types a node can add, as the API names them. */
private val INTERFACE_TYPES = listOf("bridge", "bond", "vlan", "OVSBridge", "OVSBond", "OVSIntPort")

@Composable
fun NetworkTab(viewModel: NodeViewModel, enabled: Boolean, canModify: Boolean, onOpenRoute: (Route) -> Unit) {
    val networks by viewModel.networks.collectAsStateWithLifecycle()
    var confirmApply by remember { mutableStateOf(false) }
    var addMenu by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<String?>(null) }
    val node = viewModel.node
    val editTitle = stringResource(R.string.node_edit_interface)
    val addTitle = stringResource(R.string.node_add_interface)

    LoadableList(networks, viewModel::retry) { interfaces ->
        if (canModify) {
            item(key = "actions") {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.node_network_help), style = MaterialTheme.typography.bodyMedium)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box {
                            OutlinedButton(onClick = { addMenu = true }, enabled = enabled) { Text(stringResource(R.string.action_add)) }
                            DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                                INTERFACE_TYPES.forEach { type ->
                                    DropdownMenuItem(text = { Text(type) }, onClick = {
                                        addMenu = false
                                        onOpenRoute(
                                            Route.SchemaEdit(
                                                title = "$addTitle: $type",
                                                path = "/nodes/$node/network",
                                                schemaPath = "/nodes/{node}/network",
                                                method = "POST",
                                                presets = mapOf("type" to type),
                                            ),
                                        )
                                    })
                                }
                            }
                        }
                        OutlinedButton(onClick = { confirmApply = true }, enabled = enabled) { Text(stringResource(R.string.action_apply_network)) }
                        TextButton(onClick = viewModel::revertNetwork, enabled = enabled) { Text(stringResource(R.string.action_revert_network)) }
                    }
                }
            }
        }
        items(interfaces, key = { it.iface }) { network ->
            InterfaceRow(network, onRemove = { confirmRemove = network.iface }.takeIf { canModify && enabled }, onEdit = {
                onOpenRoute(
                    Route.SchemaEdit(
                        title = "$editTitle: ${network.iface}",
                        path = "/nodes/$node/network/${network.iface}",
                        schemaPath = "/nodes/{node}/network/{iface}",
                        method = "PUT",
                    ),
                )
            }.takeIf { canModify && enabled })
            HorizontalDivider()
        }
    }

    confirmRemove?.let { iface ->
        ConfirmDialog(
            title = stringResource(R.string.confirm_remove_interface_title, iface),
            body = stringResource(R.string.confirm_remove_interface_body),
            confirmLabel = stringResource(R.string.action_remove),
            destructive = true,
            onConfirm = {
                confirmRemove = null
                viewModel.removeInterface(iface)
            },
            onDismiss = { confirmRemove = null },
        )
    }
    if (confirmApply) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_apply_network_title, node),
            body = stringResource(R.string.confirm_apply_network_body),
            confirmLabel = stringResource(R.string.action_apply_network),
            destructive = true,
            onConfirm = {
                confirmApply = false
                viewModel.applyNetwork()
            },
            onDismiss = { confirmApply = false },
        )
    }
}

@Composable
private fun InterfaceRow(network: NodeNetwork, onEdit: (() -> Unit)?, onRemove: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    val (label, tone) = if (network.active == true) R.string.interface_active to StatusTone.Good else R.string.interface_inactive to StatusTone.Neutral
    ListItem(
        headlineContent = { Text(network.iface) },
        supportingContent = {
            Text(listOfNotNull(network.type, network.cidr, network.comments?.trim()?.takeIf { it.isNotEmpty() }).joinToString(" · "))
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusLabel(stringResource(label), tone)
                if (onRemove != null) {
                    Box {
                        IconButton(onClick = { expanded = true }) {
                            Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, network.iface))
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_remove)) }, onClick = {
                                expanded = false
                                onRemove()
                            })
                        }
                    }
                }
            }
        },
        modifier = if (onEdit != null) Modifier.clickable(onClick = onEdit) else Modifier,
    )
}

// --- System ---------------------------------------------------------------

@Composable
fun SystemTab(viewModel: NodeViewModel, enabled: Boolean, canModify: Boolean) {
    val system by viewModel.system.collectAsStateWithLifecycle()
    var editDns by remember { mutableStateOf(false) }
    var editTime by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LoadableList(system, viewModel::retry) { value ->
        sectionTitle(R.string.node_dns)
        item(key = "dns") {
            ListItem(
                headlineContent = { Text(value.dns.servers.joinToString(", ").ifEmpty { stringResource(R.string.node_dns_none) }) },
                supportingContent = { Text(stringResource(R.string.node_dns_search, value.dns.search.orEmpty())) },
                trailingContent = if (canModify) {
                    { TextButton(onClick = { editDns = true }, enabled = enabled) { Text(stringResource(R.string.action_edit)) } }
                } else null,
            )
        }
        sectionTitle(R.string.node_time)
        item(key = "time") {
            ListItem(
                headlineContent = { Text(value.time.timezone.orEmpty()) },
                supportingContent = value.time.time?.let { seconds ->
                    { Text(DateUtils.formatDateTime(context, seconds * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME)) }
                },
                trailingContent = if (canModify) {
                    { TextButton(onClick = { editTime = true }, enabled = enabled) { Text(stringResource(R.string.action_edit)) } }
                } else null,
            )
        }
        sectionTitle(R.string.node_certificates)
        items(value.certificates, key = { it.filename }) { certificate ->
            CertificateRow(certificate)
            HorizontalDivider()
        }
    }

    val loaded = (system as? Loadable.Loaded)?.value
    if (editDns && loaded != null) {
        DnsDialog(loaded.dns, onDismiss = { editDns = false }) { search, servers ->
            editDns = false
            viewModel.saveDns(search, servers)
        }
    }
    if (editTime && loaded != null) {
        TimezoneDialog(loaded.time.timezone.orEmpty(), onDismiss = { editTime = false }) { timezone ->
            editTime = false
            viewModel.saveTimezone(timezone)
        }
    }
}

@Composable
private fun CertificateRow(certificate: NodeCertificate) {
    val context = LocalContext.current
    val now = System.currentTimeMillis() / 1000
    val expired = certificate.notafter?.let { it < now } == true
    val soon = NodeViewModel.expiresSoon(certificate, now)
    ListItem(
        headlineContent = { Text(certificate.filename) },
        supportingContent = {
            Column {
                certificate.subject?.let { Text(it) }
                certificate.issuer?.let { Text(stringResource(R.string.node_certificate_issuer, it), style = MaterialTheme.typography.bodySmall) }
                if (certificate.san.isNotEmpty()) Text(certificate.san.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                certificate.notafter?.let {
                    Text(stringResource(R.string.certificate_expires, DateUtils.formatDateTime(context, it * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)))
                }
            }
        },
        trailingContent = when {
            expired -> { { StatusLabel(stringResource(R.string.certificate_expired), StatusTone.Bad) } }
            soon -> { { StatusLabel(stringResource(R.string.certificate_expires_soon), StatusTone.Warning) } }
            else -> null
        },
    )
}

@Composable
private fun DnsDialog(dns: NodeDns, onDismiss: () -> Unit, onSave: (String, List<String>) -> Unit) {
    var search by rememberSaveable { mutableStateOf(dns.search.orEmpty()) }
    var first by rememberSaveable { mutableStateOf(dns.dns1.orEmpty()) }
    var second by rememberSaveable { mutableStateOf(dns.dns2.orEmpty()) }
    var third by rememberSaveable { mutableStateOf(dns.dns3.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.node_dns)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(search, { search = it }, singleLine = true, label = { Text(stringResource(R.string.setting_searchdomain)) }, modifier = Modifier.fillMaxWidth())
                listOf(first to { v: String -> first = v }, second to { v: String -> second = v }, third to { v: String -> third = v })
                    .forEachIndexed { index, (value, set) ->
                        OutlinedTextField(value, set, singleLine = true, label = { Text(stringResource(R.string.node_dns_server, index + 1)) }, modifier = Modifier.fillMaxWidth())
                    }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(search, listOf(first, second, third)) }, enabled = search.isNotBlank()) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun TimezoneDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var zone by rememberSaveable { mutableStateOf(current) }
    val zones = remember { TimeZone.getAvailableIDs().toList() }
    val suggestions = zones.filter { it.contains(zone, ignoreCase = true) }.take(MAX_SUGGESTIONS)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.node_time)) },
        text = { SuggestField(stringResource(R.string.setting_timezone), zone, suggestions, { zone = it }) },
        confirmButton = { TextButton(onClick = { onSave(zone) }, enabled = zone in zones || zone == "UTC") { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

private const val MAX_SUGGESTIONS = 20

// --- Disks ----------------------------------------------------------------

@Composable
fun DisksTab(viewModel: NodeViewModel) {
    val disks by viewModel.disks.collectAsStateWithLifecycle()
    val smart by viewModel.smart.collectAsStateWithLifecycle()
    LoadableList(disks, viewModel::retry) { list ->
        items(list, key = { it.devpath }) { disk ->
            DiskRow(disk, onClick = { viewModel.loadSmart(disk.devpath) })
            HorizontalDivider()
        }
    }
    smart?.let { (disk, state) -> SmartDialog(disk, state, onDismiss = viewModel::closeSmart) }
}

@Composable
private fun DiskRow(disk: PhysicalDisk, onClick: () -> Unit) {
    val health = disk.health
    ListItem(
        headlineContent = { Text(disk.devpath) },
        supportingContent = {
            Text(
                listOfNotNull(
                    disk.model,
                    disk.size?.let(::formatBytes),
                    disk.type,
                    disk.used,
                    disk.wearout?.takeIf { it != "N/A" }?.let { stringResource(R.string.disk_wearout, it) },
                ).joinToString(" · "),
            )
        },
        trailingContent = health?.let {
            {
                val tone = when {
                    disk.isFailing -> StatusTone.Bad
                    it == "UNKNOWN" -> StatusTone.Neutral
                    else -> StatusTone.Good
                }
                StatusLabel(if (it == "UNKNOWN") stringResource(R.string.disk_health_unknown) else it, tone)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun SmartDialog(disk: String, state: Loadable<SmartData>, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.disk_smart_title, disk)) },
        text = {
            when (state) {
                Loadable.Loading -> CircularProgressIndicator()
                is Loadable.Failed -> Text(state.message.text(), color = MaterialTheme.colorScheme.error)
                is Loadable.Loaded -> {
                    val data = state.value
                    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.health?.let { Text(stringResource(R.string.disk_smart_health, it), style = MaterialTheme.typography.titleSmall) }
                        if (data.attributes.isNotEmpty()) {
                            data.attributes.forEach { attribute ->
                                val failing = attribute.fail != null && attribute.fail != "-"
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(attribute.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                    Text(
                                        stringResource(R.string.disk_smart_values, attribute.value ?: 0, attribute.threshold ?: 0, attribute.raw.orEmpty()),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (failing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                            }
                        } else {
                            SelectionContainer {
                                Text(
                                    data.text.orEmpty(),
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

// --- Log ------------------------------------------------------------------

@Composable
fun LogTab(viewModel: NodeViewModel) {
    val journal by viewModel.journal.collectAsStateWithLifecycle()
    when (val state = journal) {
        Loadable.Loading -> CenteredProgress()
        is Loadable.Failed -> MessageWithAction(
            state.message.text(),
            actionLabel = stringResource(R.string.action_retry),
            onAction = viewModel::retry,
        )
        is Loadable.Loaded -> {
            val lines = state.value
            val listState = rememberLazyListState()
            // The newest lines are at the end, so the log opens there.
            LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex) }
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
                    items(lines.size) { index ->
                        Text(lines[index], fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
