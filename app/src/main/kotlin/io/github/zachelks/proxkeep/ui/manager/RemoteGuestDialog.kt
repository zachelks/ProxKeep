package io.github.zachelks.proxkeep.ui.manager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.pdm.PdmResource
import io.github.zachelks.proxkeep.core.api.pdm.remoteGuestPath
import io.github.zachelks.proxkeep.core.api.pdm.remoteGuestTemplate
import io.github.zachelks.proxkeep.ui.cluster.statusTextOf
import io.github.zachelks.proxkeep.ui.components.StatusLabel
import io.github.zachelks.proxkeep.ui.components.UsageBar
import io.github.zachelks.proxkeep.ui.format.formatBytes
import io.github.zachelks.proxkeep.ui.format.usageFraction
import io.github.zachelks.proxkeep.ui.lifecycle.ConfirmDialog
import io.github.zachelks.proxkeep.ui.navigation.Route

/** The migrations a manager offers for a guest, as schema forms: within its cluster, or to another remote. */
fun migrationRoutes(remote: String, guest: PdmResource, withinTitle: String, acrossTitle: String): List<Route.SchemaEdit> {
    val kind = guest.guestKind ?: return emptyList()
    val vmid = guest.vmid ?: return emptyList()
    val path = remoteGuestPath(remote, kind, vmid)
    return listOf(
        Route.SchemaEdit(withinTitle, "$path/migrate", remoteGuestTemplate(kind, "/migrate"), "POST"),
        Route.SchemaEdit(acrossTitle, "$path/remote-migrate", remoteGuestTemplate(kind, "/remote-migrate"), "POST"),
    )
}

/**
 * A guest on a remote: its state, power actions and migration. Shutting
 * down asks the guest; stopping, which does not, is set apart and asks for
 * confirmation first.
 */
@Composable
fun RemoteGuestDialog(
    remote: String,
    guest: PdmResource,
    busy: Boolean,
    onAction: (GuestAction) -> Unit,
    onOpenRoute: (Route) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmStop by rememberSaveable { mutableStateOf(false) }
    val title = guestTitle(guest)
    val running = guest.status == "running"
    val status = statusTextOf(guest.status, guest.template)
    val (within, across) = migrationRoutes(
        remote,
        guest,
        withinTitle = stringResource(R.string.pdm_migrate_within_title, title),
        acrossTitle = stringResource(R.string.pdm_migrate_across_title, title),
    ).let { it.getOrNull(0) to it.getOrNull(1) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(listOfNotNull(remote, guest.node).joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
                StatusLabel(status.text, status.tone)
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                guest.cpu?.takeIf { running }?.let { cpu ->
                    UsageBar(stringResource(R.string.usage_cpu), cpu.coerceIn(0.0, 1.0), stringResource(R.string.pbs_cpu_detail))
                }
                usageFraction(guest.mem, guest.maxmem)?.takeIf { running }?.let { fraction ->
                    UsageBar(stringResource(R.string.usage_memory), fraction, stringResource(R.string.used_of_total, formatBytes(guest.mem ?: 0), formatBytes(guest.maxmem ?: 0)))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) {
                        Button(onClick = { onAction(GuestAction.Shutdown) }, enabled = !busy) { Text(stringResource(R.string.action_shutdown)) }
                    } else {
                        Button(onClick = { onAction(GuestAction.Start) }, enabled = !busy) { Text(stringResource(R.string.action_start)) }
                    }
                }
                Text(stringResource(R.string.pdm_migrate), style = MaterialTheme.typography.titleSmall)
                within?.let { route ->
                    OutlinedButton(onClick = { onOpenRoute(route) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pdm_migrate_within))
                    }
                }
                across?.let { route ->
                    OutlinedButton(onClick = { onOpenRoute(route) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.pdm_migrate_across))
                    }
                }
                if (running) {
                    // Kept apart from shutting down, since it is forceful.
                    HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
                    TextButton(
                        onClick = { confirmStop = true },
                        enabled = !busy,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                    ) { Text(stringResource(R.string.action_stop)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )

    if (confirmStop) {
        ConfirmDialog(
            title = stringResource(R.string.confirm_stop_title, title),
            body = stringResource(R.string.confirm_stop_body),
            confirmLabel = stringResource(R.string.action_stop),
            destructive = true,
            onConfirm = {
                confirmStop = false
                onAction(GuestAction.Stop)
            },
            onDismiss = { confirmStop = false },
        )
    }
}
