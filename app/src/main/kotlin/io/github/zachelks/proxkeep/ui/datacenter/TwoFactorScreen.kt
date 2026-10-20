@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.datacenter

import android.content.ActivityNotFoundException
import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.access.TfaEntry
import io.github.zachelks.proxkeep.core.api.access.UserTfa
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.appContainer
import io.github.zachelks.proxkeep.ui.nodes.LoadableList
import io.github.zachelks.proxkeep.ui.text

/** Recovery keys come as one set per user, so a user who has them is not offered more. */
private val UserTfa.hasRecoveryKeys: Boolean
    get() = entries.any { it.type == "recovery" }

private val TfaEntry.typeLabel: Int
    get() = when (type) {
        "totp" -> R.string.tfa_type_totp
        "recovery" -> R.string.tfa_type_recovery
        "webauthn" -> R.string.tfa_type_webauthn
        "yubico" -> R.string.tfa_type_yubico
        "u2f" -> R.string.tfa_type_u2f
        else -> R.string.tfa_type_other
    }

@Composable
private fun twoFactorViewModel(): TwoFactorViewModel {
    val container = appContainer()
    return viewModel { TwoFactorViewModel(container.repository, container.refresh) }
}

/**
 * Every user's second factors. Authenticator apps and recovery keys are
 * added here; security keys need the server's web interface.
 */
@Composable
fun TwoFactorScreen(onClose: () -> Unit, viewModel: TwoFactorViewModel = twoFactorViewModel()) {
    val users by viewModel.users.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val dialog by viewModel.dialog.collectAsStateWithLifecycle()
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
                    title = { Text(stringResource(R.string.tfa_title)) },
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
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            LoadableList(users, viewModel::retry) { list ->
                list.forEach { user ->
                    item(key = "user:${user.userid}") { UserHeader(user, viewModel, enabled = !busy) }
                    if (user.entries.isEmpty()) {
                        item(key = "none:${user.userid}") {
                            Text(
                                stringResource(R.string.tfa_none),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    }
                    user.entries.forEach { entry ->
                        item(key = "entry:${user.userid}:${entry.id}") { EntryRow(user.userid, entry, viewModel, enabled = !busy) }
                    }
                }
                item(key = "web-only") {
                    Text(
                        stringResource(R.string.tfa_web_only),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    when (val open = dialog) {
        is TfaDialog.AddTotp -> AddTotpDialog(open, viewModel, busy)
        is TfaDialog.AddRecovery -> AddRecoveryDialog(open, viewModel, busy)
        is TfaDialog.RecoveryKeys -> RecoveryKeysDialog(open.keys, onDone = viewModel::dismiss)
        is TfaDialog.Change -> ChangeDialog(open, viewModel, busy)
        null -> Unit
    }
}

@Composable
private fun UserHeader(user: UserTfa, viewModel: TwoFactorViewModel, enabled: Boolean) {
    var menu by remember { mutableStateOf(false) }
    val locked = user.isLocked(System.currentTimeMillis() / 1000)
    ListItem(
        headlineContent = { Text(user.userid, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) },
        supportingContent = if (locked) {
            { Text(stringResource(R.string.tfa_locked), color = MaterialTheme.colorScheme.error) }
        } else {
            null
        },
        trailingContent = {
            Row {
                if (locked) {
                    TextButton(onClick = { viewModel.unlock(user.userid) }, enabled = enabled) { Text(stringResource(R.string.action_unlock)) }
                }
                Box {
                    IconButton(onClick = { menu = true }, enabled = enabled) {
                        Icon(painterResource(R.drawable.ic_add), contentDescription = stringResource(R.string.tfa_add_for, user.userid))
                    }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.tfa_type_totp)) },
                            onClick = {
                                menu = false
                                viewModel.addTotp(user.userid)
                            },
                        )
                        if (!user.hasRecoveryKeys) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tfa_type_recovery)) },
                                onClick = {
                                    menu = false
                                    viewModel.addRecoveryKeys(user.userid)
                                },
                            )
                        }
                    }
                }
            }
        },
    )
}

@Composable
private fun EntryRow(userid: String, entry: TfaEntry, viewModel: TwoFactorViewModel, enabled: Boolean) {
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    val type = stringResource(entry.typeLabel)
    val title = entry.description?.takeIf { it.isNotBlank() } ?: type
    val details = listOfNotNull(
        type.takeIf { title != it },
        entry.created?.let { stringResource(R.string.tfa_added_on, DateUtils.formatDateTime(context, it * 1000L, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)) },
        stringResource(R.string.tfa_off).takeUnless { entry.enable },
    )
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (details.isEmpty()) null else ({ Text(details.joinToString(" · ")) }),
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }, enabled = enabled) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more_for, title))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val change = if (entry.enable) EntryChange.Disable else EntryChange.Enable
                    DropdownMenuItem(
                        text = { Text(stringResource(if (entry.enable) R.string.action_disable else R.string.action_enable)) },
                        onClick = {
                            menu = false
                            viewModel.change(userid, entry, change)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            menu = false
                            viewModel.change(userid, entry, EntryChange.Delete)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun AddTotpDialog(dialog: TfaDialog.AddTotp, viewModel: TwoFactorViewModel, busy: Boolean) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val error by viewModel.dialogError.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var noApp by rememberSaveable { mutableStateOf(false) }
    val uri = dialog.totp.uri(viewModel.issuer, dialog.userid)
    val ready = code.trim().length == CODE_LENGTH && (!viewModel.needsPassword || password.isNotEmpty())

    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.tfa_totp_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tfa_totp_body, dialog.userid))
                SelectionContainer {
                    Text(dialog.totp.grouped, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyLarge)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, uri.toUri()))
                        } catch (e: ActivityNotFoundException) {
                            noApp = true
                        }
                    }) { Text(stringResource(R.string.tfa_open_app)) }
                    TextButton(onClick = { clipboard.setText(AnnotatedString(dialog.totp.secret)) }) { Text(stringResource(R.string.action_copy)) }
                }
                if (noApp) Text(stringResource(R.string.tfa_no_app), style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = code,
                    onValueChange = { value -> code = value.filter(Char::isDigit).take(CODE_LENGTH) },
                    label = { Text(stringResource(R.string.tfa_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.tfa_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (viewModel.needsPassword) PasswordField(password) { password = it }
                error?.let { DialogError(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = { viewModel.confirmTotp(description, code, password) }, enabled = ready && !busy) {
                Text(stringResource(R.string.action_add))
            }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun AddRecoveryDialog(dialog: TfaDialog.AddRecovery, viewModel: TwoFactorViewModel, busy: Boolean) {
    val error by viewModel.dialogError.collectAsStateWithLifecycle()
    var description by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(R.string.tfa_recovery_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.tfa_recovery_body, dialog.userid))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text(stringResource(R.string.tfa_description)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (viewModel.needsPassword) PasswordField(password) { password = it }
                error?.let { DialogError(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmRecoveryKeys(description, password) },
                enabled = !busy && (!viewModel.needsPassword || password.isNotEmpty()),
            ) { Text(stringResource(R.string.action_create)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

/** Shows new recovery keys once, with a way to copy them all. */
@Composable
private fun RecoveryKeysDialog(keys: List<String>, onDone: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val all = keys.joinToString("\n")
    AlertDialog(
        onDismissRequest = {},
        title = { Text(stringResource(R.string.tfa_recovery_keys_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.tfa_recovery_keys_body))
                SelectionContainer { Text(all, fontFamily = FontFamily.Monospace) }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.action_done)) } },
        dismissButton = { TextButton(onClick = { clipboard.setText(AnnotatedString(all)) }) { Text(stringResource(R.string.action_copy)) } },
    )
}

@Composable
private fun ChangeDialog(dialog: TfaDialog.Change, viewModel: TwoFactorViewModel, busy: Boolean) {
    val error by viewModel.dialogError.collectAsStateWithLifecycle()
    var password by rememberSaveable { mutableStateOf("") }
    val (title, body, confirm) = when (dialog.change) {
        EntryChange.Enable -> Triple(R.string.tfa_enable_title, R.string.tfa_enable_body, R.string.action_enable)
        EntryChange.Disable -> Triple(R.string.tfa_disable_title, R.string.tfa_disable_body, R.string.action_disable)
        EntryChange.Delete -> Triple(R.string.tfa_delete_title, R.string.tfa_delete_body, R.string.action_delete)
    }
    val name = dialog.entry.description?.takeIf { it.isNotBlank() } ?: stringResource(dialog.entry.typeLabel)
    AlertDialog(
        onDismissRequest = viewModel::dismiss,
        title = { Text(stringResource(title, name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(body, dialog.userid))
                if (viewModel.needsPassword) PasswordField(password) { password = it }
                error?.let { DialogError(it) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { viewModel.confirmChange(password) },
                enabled = !busy && (!viewModel.needsPassword || password.isNotEmpty()),
                colors = if (dialog.change == EntryChange.Enable) ButtonDefaults.textButtonColors() else ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(confirm)) }
        },
        dismissButton = { TextButton(onClick = viewModel::dismiss, enabled = !busy) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun PasswordField(value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(R.string.tfa_password)) },
        supportingText = { Text(stringResource(R.string.tfa_password_help)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DialogError(message: UiMessage) {
    Text(message.text(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

private const val CODE_LENGTH = 6
