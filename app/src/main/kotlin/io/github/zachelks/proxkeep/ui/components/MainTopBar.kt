@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.ui.ShellViewModel

/**
 * The app bar of every top-level screen: a title, search and a refresh
 * button while signed in, a way back to open consoles, and the server menu.
 *
 * @param openConsoles how many consoles are running
 */
@Composable
fun MainTopBar(
    title: String,
    viewModel: ShellViewModel,
    openConsoles: Int,
    onOpenConsoles: () -> Unit,
    desktopOpen: Boolean,
    onOpenDesktop: () -> Unit,
    onSearch: (() -> Unit)?,
    onAddServer: () -> Unit,
    onSettings: () -> Unit,
) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()

    TopAppBar(
        title = { Text(title) },
        actions = {
            if (desktopOpen) {
                IconButton(onClick = onOpenDesktop) {
                    Icon(
                        painter = painterResource(R.drawable.ic_desktop),
                        contentDescription = stringResource(R.string.action_return_to_display),
                    )
                }
            }
            if (openConsoles > 0) {
                IconButton(onClick = onOpenConsoles) {
                    BadgedBox(badge = { Badge { Text(openConsoles.toString()) } }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_terminal),
                            contentDescription = pluralStringResource(R.plurals.open_consoles_description, openConsoles, openConsoles),
                        )
                    }
                }
            }
            if (session is Session.Active && onSearch != null) {
                IconButton(onClick = onSearch) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = stringResource(R.string.action_search),
                    )
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }
            ServerMenu(
                session = session,
                servers = servers,
                onAddServer = onAddServer,
                onSwitch = viewModel::activate,
                onSignOut = viewModel::signOut,
                onRemove = viewModel::remove,
                onSettings = onSettings,
            )
        },
    )
}

/** Overflow menu for switching, adding and removing servers. */
@Composable
private fun ServerMenu(
    session: Session,
    servers: StoredServers,
    onAddServer: () -> Unit,
    onSwitch: (String) -> Unit,
    onSignOut: () -> Unit,
    onRemove: (String) -> Unit,
    onSettings: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ServerProfile?>(null) }

    val current: ServerProfile? = when (session) {
        is Session.Active -> session.profile
        is Session.SignedOut -> session.profile
        is Session.SigningIn -> session.profile
        Session.None -> null
    }
    val canSignOut = session is Session.Active && session.client.credentials is TicketCredentials ||
        session is Session.SigningIn

    // The Box anchors the menu to the button that opens it.
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                painter = painterResource(R.drawable.ic_more_vert),
                contentDescription = stringResource(R.string.action_more),
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            val others = servers.profiles.filter { it.id != current?.id }
            others.forEach { profile ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_switch_to, profile.name)) },
                    onClick = {
                        expanded = false
                        onSwitch(profile.id)
                    },
                )
            }
            if (others.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_server)) },
                onClick = {
                    expanded = false
                    onAddServer()
                },
            )
            if (canSignOut) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_sign_out)) },
                    onClick = {
                        expanded = false
                        onSignOut()
                    },
                )
            }
            if (current != null) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.action_remove_server, current.name)) },
                    onClick = {
                        expanded = false
                        confirmRemove = current
                    },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(stringResource(R.string.settings_title)) },
                onClick = {
                    expanded = false
                    onSettings()
                },
            )
        }
    }

    confirmRemove?.let { profile ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(stringResource(R.string.remove_server_title, profile.name)) },
            text = { Text(stringResource(R.string.remove_server_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = null
                        onRemove(profile.id)
                    },
                ) {
                    Text(stringResource(R.string.action_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}
