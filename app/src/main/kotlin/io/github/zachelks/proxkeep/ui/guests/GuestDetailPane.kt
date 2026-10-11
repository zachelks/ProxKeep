@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.guests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.core.api.model.ConfigDevice
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.GuestConfig
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.ui.GuestDetail
import io.github.zachelks.proxkeep.ui.cluster.StatusText
import io.github.zachelks.proxkeep.ui.cluster.statusText
import io.github.zachelks.proxkeep.ui.components.MessageWithAction
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.formatUptime
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.text
import io.github.zachelks.proxkeep.ui.theme.StatusTone

private const val BYTES_PER_MIB = 1024L * 1024L

/**
 * Everything about one guest: status, power actions, live usage and
 * configuration.
 *
 * @param resource the guest's row in the cluster resource list, or null if it is no longer there
 * @param busyAction the power action in progress on this guest, if any
 * @param showBack true when the list is not visible beside this pane
 */
@Composable
fun GuestDetailPane(
    resource: ClusterResource?,
    detail: GuestDetail?,
    busyAction: GuestAction?,
    showBack: Boolean,
    onBack: () -> Unit,
    onAction: (GuestAction) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(resource?.name ?: resource?.id ?: stringResource(R.string.destination_guests))
                        if (resource != null) {
                            Text(text = guestSubtitle(resource), style = MaterialTheme.typography.labelMedium)
                        }
                    }
                },
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
        if (resource == null) {
            MessageWithAction(
                message = stringResource(R.string.guest_gone),
                modifier = Modifier.padding(innerPadding),
            )
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val paused = detail?.status?.isPaused == true
            StatusRow(resource = resource, paused = paused, lock = detail?.status?.lock ?: resource.lock)

            if (busyAction != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(text = stringResource(busyAction.inProgressLabel), style = MaterialTheme.typography.bodyMedium)
                }
            }

            ActionButtons(
                guestName = resource.name ?: resource.id,
                actions = GuestAction.available(
                    kind = resource.kind,
                    isRunning = resource.status == "running",
                    isPaused = paused,
                    isTemplate = resource.isTemplate,
                ),
                enabled = busyAction == null,
                onAction = onAction,
            )

            if (resource.status == "running") UsageCard(resource)

            detail?.error?.let { error ->
                Text(
                    text = error.text(),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            val config = detail?.config
            when {
                config != null -> ConfigCards(resource.kind, config)
                detail?.error == null -> Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
        }
    }
}

@Composable
private fun guestSubtitle(resource: ClusterResource): String {
    val kind = stringResource(if (resource.kind == ResourceKind.Lxc) R.string.kind_container else R.string.kind_vm)
    return listOfNotNull("$kind ${resource.vmid ?: ""}".trim(), resource.node).joinToString(" · ")
}

@Composable
private fun StatusRow(resource: ClusterResource, paused: Boolean, lock: String?) {
    val status = if (paused) {
        StatusText(stringResource(R.string.status_paused), StatusTone.Warning)
    } else {
        resource.statusText()
    }
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        StatusLabel(text = status.text, tone = status.tone)
        if (!lock.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.guest_locked, lock),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The gentle actions are buttons. The rest sit behind "More", and forceful
 * ones are set apart there, so stopping a guest hard is never one stray tap.
 * Every action that interrupts the guest asks first.
 */
@Composable
private fun ActionButtons(
    guestName: String,
    actions: List<GuestAction>,
    enabled: Boolean,
    onAction: (GuestAction) -> Unit,
) {
    if (actions.isEmpty()) return
    var pendingConfirmation by remember { mutableStateOf<GuestAction?>(null) }
    var menuOpen by remember { mutableStateOf(false) }

    val request: (GuestAction) -> Unit = { action ->
        if (action.confirmTitle != null) pendingConfirmation = action else onAction(action)
    }
    val buttons = actions.filterNot { it.isForceful }.take(2)
    val overflow = actions - buttons.toSet()

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        buttons.forEachIndexed { index, action ->
            if (index == 0) {
                Button(onClick = { request(action) }, enabled = enabled) { Text(stringResource(action.label)) }
            } else {
                OutlinedButton(onClick = { request(action) }, enabled = enabled) { Text(stringResource(action.label)) }
            }
        }
        if (overflow.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }, enabled = enabled) {
                    Text(stringResource(R.string.action_more_actions))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val gentle = overflow.filterNot { it.isForceful }
                    val forceful = overflow.filter { it.isForceful }
                    gentle.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            onClick = {
                                menuOpen = false
                                request(action)
                            },
                        )
                    }
                    if (gentle.isNotEmpty() && forceful.isNotEmpty()) HorizontalDivider()
                    forceful.forEach { action ->
                        DropdownMenuItem(
                            text = { Text(stringResource(action.label)) },
                            colors = MenuDefaults.itemColors(textColor = MaterialTheme.colorScheme.error),
                            onClick = {
                                menuOpen = false
                                request(action)
                            },
                        )
                    }
                }
            }
        }
    }

    val confirming = pendingConfirmation
    val confirmTitle = confirming?.confirmTitle
    val confirmBody = confirming?.confirmBody
    if (confirming != null && confirmTitle != null && confirmBody != null) {
        AlertDialog(
            onDismissRequest = { pendingConfirmation = null },
            title = { Text(stringResource(confirmTitle, guestName)) },
            text = { Text(stringResource(confirmBody)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingConfirmation = null
                        onAction(confirming)
                    },
                    colors = if (confirming.isForceful) {
                        ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    } else {
                        ButtonDefaults.textButtonColors()
                    },
                ) {
                    Text(stringResource(confirming.label))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingConfirmation = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun UsageCard(resource: ClusterResource) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            resource.cpu?.let { cpu ->
                val cores = resource.maxcpu?.toInt() ?: 0
                UsageBar(
                    label = stringResource(R.string.usage_cpu),
                    fraction = cpu,
                    detail = pluralStringResource(R.plurals.usage_cpu_cores, cores, cores),
                )
            }
            usageFraction(resource.mem, resource.maxmem)?.let { fraction ->
                UsageBar(
                    label = stringResource(R.string.usage_memory),
                    fraction = fraction,
                    detail = stringResource(
                        R.string.used_of_total,
                        formatBytes(resource.mem ?: 0L),
                        formatBytes(resource.maxmem ?: 0L),
                    ),
                )
            }
            // Virtual machines report no disk usage, only containers do.
            if ((resource.disk ?: 0L) > 0L) {
                usageFraction(resource.disk, resource.maxdisk)?.let { fraction ->
                    UsageBar(
                        label = stringResource(R.string.usage_root_disk),
                        fraction = fraction,
                        detail = stringResource(
                            R.string.used_of_total,
                            formatBytes(resource.disk ?: 0L),
                            formatBytes(resource.maxdisk ?: 0L),
                        ),
                    )
                }
            }
            val facts = listOfNotNull(
                resource.uptime?.takeIf { it > 0 }?.let { stringResource(R.string.uptime_label, formatUptime(it)) },
                if (resource.netin != null && resource.netout != null) {
                    stringResource(R.string.network_totals, formatBytes(resource.netin ?: 0L), formatBytes(resource.netout ?: 0L))
                } else {
                    null
                },
            )
            if (facts.isNotEmpty()) {
                Text(
                    text = facts.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ConfigCards(kind: ResourceKind, config: GuestConfig) {
    val yes = stringResource(R.string.value_yes)
    val no = stringResource(R.string.value_no)
    val rows = buildList {
        config.long("memory")?.let { add(stringResource(R.string.config_memory) to formatBytes(it * BYTES_PER_MIB)) }
        if (kind == ResourceKind.Lxc) {
            config.long("swap")?.let { add(stringResource(R.string.config_swap) to formatBytes(it * BYTES_PER_MIB)) }
        }
        // A virtual machine defaults to one socket with one core. A container
        // with no core count may use every core of its node, so none is shown.
        val cores = when (kind) {
            ResourceKind.Lxc -> config.long("cores")
            else -> (config.long("cores") ?: 1L) * (config.long("sockets") ?: 1L)
        }?.toInt()
        if (cores != null) {
            add(stringResource(R.string.config_processors) to pluralStringResource(R.plurals.usage_cpu_cores, cores, cores))
        }
        config["ostype"]?.let { add(stringResource(R.string.config_os_type) to it) }
        add(stringResource(R.string.config_start_at_boot) to if (config.flag("onboot") == true) yes else no)
        if (kind == ResourceKind.Qemu) {
            add(stringResource(R.string.config_guest_agent) to if (config.agentEnabled) yes else no)
        } else {
            add(stringResource(R.string.config_unprivileged) to if (config.flag("unprivileged") == true) yes else no)
        }
        config.disks.forEach { add(deviceLabel(it) to it.value) }
        config.networks.forEach { add(deviceLabel(it) to it.value) }
    }

    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(text = stringResource(R.string.section_configuration), style = MaterialTheme.typography.titleMedium)
            rows.forEach { (label, value) -> ConfigRow(label, value) }
        }
    }

    config.description?.let { notes ->
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = stringResource(R.string.section_notes), style = MaterialTheme.typography.titleMedium)
                SelectionContainer { Text(text = notes.trim(), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable
private fun deviceLabel(device: ConfigDevice): String = when (device.bus) {
    "rootfs" -> stringResource(R.string.device_root_disk)
    "mp" -> stringResource(R.string.device_mount_point, device.key)
    "efidisk" -> stringResource(R.string.device_efi_disk)
    "tpmstate" -> stringResource(R.string.device_tpm_state)
    "unused" -> stringResource(R.string.device_unused_disk, device.key)
    "net" -> stringResource(R.string.device_network, device.key)
    else -> stringResource(if (device.isCdrom) R.string.device_cdrom else R.string.device_hard_disk, device.key)
}

@Composable
private fun ConfigRow(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer { Text(text = value, style = MaterialTheme.typography.bodyMedium) }
    }
}
