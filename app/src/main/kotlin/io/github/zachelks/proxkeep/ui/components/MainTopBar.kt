@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.ui.MainViewModel

/**
 * The app bar of every top-level screen: a title, a refresh button while
 * signed in, and the server menu.
 */
@Composable
fun MainTopBar(title: String, viewModel: MainViewModel) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    val servers by viewModel.servers.collectAsStateWithLifecycle()

    TopAppBar(
        title = { Text(title) },
        actions = {
            if (session is Session.Active) {
                IconButton(onClick = viewModel::refreshNow) {
                    Icon(
                        painter = painterResource(R.drawable.ic_refresh),
                        contentDescription = stringResource(R.string.action_refresh),
                    )
                }
            }
            ServerMenu(
                session = session,
                servers = servers,
                onAddServer = viewModel::startAddServer,
                onSwitch = viewModel::activate,
                onSignOut = viewModel::signOut,
                onRemove = viewModel::remove,
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
) {
    var expanded by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf<ServerProfile?>(null) }

    val current: ServerProfile? = when (session) {
        is Session.Active -> session.profile
        is Session.SignedOut -> session.profile
        Session.None -> null
    }
    val canSignOut = session is Session.Active && session.client.credentials is TicketCredentials

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
