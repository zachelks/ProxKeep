@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.addserver

import android.os.Handler
import android.os.Looper
import android.security.KeyChain
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.Product
import io.github.zachelks.proxkeep.core.api.ServerAddress
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.tls.CertificateInfo
import io.github.zachelks.proxkeep.data.AuthMethod
import io.github.zachelks.proxkeep.ui.addserver.AddServerState.Step
import io.github.zachelks.proxkeep.ui.components.RadioChoices
import io.github.zachelks.proxkeep.ui.text
import java.text.DateFormat
import java.util.Date

/** Full-screen flow for adding a server or signing in to a saved one. */
@Composable
fun AddServerFlow(state: AddServerState, onClose: () -> Unit) {
    val goBack = { if (!state.back()) onClose() }
    BackHandler(onBack = goBack)
    LaunchedEffect(state.step) {
        if (state.step == Step.Done) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(titleFor(state))) },
                navigationIcon = {
                    val atStart = state.step == Step.Address
                    IconButton(onClick = goBack) {
                        Icon(
                            painter = painterResource(if (atStart) R.drawable.ic_close else R.drawable.ic_arrow_back),
                            contentDescription = stringResource(if (atStart) R.string.action_close else R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        val url = state.webSignInUrl
        if (state.step == Step.WebSignIn && url != null) {
            // The page needs the whole screen, and scrolls by itself.
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding).imePadding()) {
                if (state.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                WebSignInPage(
                    url = url,
                    pinnedFingerprint = state.pinnedFingerprint,
                    clientCertAlias = state.clientCertAlias,
                    onTicket = state::completeWebSignIn,
                    onPageLost = state::webPageLost,
                    modifier = Modifier.fillMaxSize(),
                    cookieName = state.product.cookieName,
                )
            }
            return@Scaffold
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (state.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                when (state.step) {
                    Step.Address -> AddressStep(state)
                    Step.Certificate -> CertificateStep(state)
                    Step.SignIn -> SignInStep(state)
                    Step.SecondFactor -> SecondFactorStep(state)
                    Step.WebSignIn, Step.Done -> Unit
                }
                state.error?.let { message ->
                    Text(
                        text = message.text(),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun titleFor(state: AddServerState): Int = when (state.step) {
    Step.Address -> if (state.isNewServer) R.string.add_server_title else R.string.sign_in_title
    Step.Certificate -> R.string.certificate_title
    Step.SignIn, Step.Done -> R.string.sign_in_title
    Step.SecondFactor -> R.string.second_factor_title
    Step.WebSignIn -> R.string.web_sign_in_title
}

@Composable
private fun AddressStep(state: AddServerState) {
    Text(
        text = stringResource(R.string.address_intro),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (state.isNewServer) {
        Text(stringResource(R.string.field_product), style = MaterialTheme.typography.titleSmall)
        val labels = Product.entries.associateWith { stringResource(it.label) }
        RadioChoices(Product.entries, state.product, label = { labels.getValue(it) }, onSelect = { state.product = it })
    }
    OutlinedTextField(
        value = state.host,
        onValueChange = { state.host = it },
        label = { Text(stringResource(R.string.field_address)) },
        placeholder = { Text(stringResource(R.string.field_address_placeholder)) },
        supportingText = { Text(stringResource(R.string.field_address_hint, state.product.defaultPort)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = state.name,
        onValueChange = { state.name = it },
        label = { Text(stringResource(R.string.field_name)) },
        supportingText = { Text(stringResource(R.string.field_name_hint)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
        keyboardActions = KeyboardActions(onGo = { state.submitAddress() }),
        modifier = Modifier.fillMaxWidth(),
    )
    var showProxy by rememberSaveable { mutableStateOf(state.headers.isNotEmpty() || state.clientCertAlias != null) }
    TextButton(onClick = { showProxy = !showProxy }) {
        Text(stringResource(if (showProxy) R.string.proxy_hide else R.string.proxy_show))
    }
    if (showProxy) ReverseProxySettings(state)
    Button(
        onClick = state::submitAddress,
        enabled = !state.busy && state.host.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_continue))
    }
}

/** Extra headers and a client certificate, for a reverse proxy that needs them. */
@Composable
private fun ReverseProxySettings(state: AddServerState) {
    Text(
        text = stringResource(R.string.proxy_intro),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(stringResource(R.string.proxy_headers), style = MaterialTheme.typography.titleSmall)
    state.headers.forEachIndexed { index, header ->
        OutlinedCard(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = header.name,
                    onValueChange = { state.setHeader(index, header.copy(name = it)) },
                    label = { Text(stringResource(R.string.field_header_name)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = header.value,
                    onValueChange = { state.setHeader(index, header.copy(value = it)) },
                    label = { Text(stringResource(R.string.field_header_value)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { state.removeHeader(index) }) {
                    Text(stringResource(R.string.action_remove_header))
                }
            }
        }
    }
    TextButton(onClick = state::addHeader) { Text(stringResource(R.string.action_add_header)) }

    Text(stringResource(R.string.proxy_client_certificate), style = MaterialTheme.typography.titleSmall)
    Text(
        text = state.clientCertAlias ?: stringResource(R.string.proxy_no_certificate),
        style = MaterialTheme.typography.bodyMedium,
    )
    val activity = LocalActivity.current
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (activity != null) {
            TextButton(
                onClick = {
                    val address = ServerAddress.parse(state.host)
                    val main = Handler(Looper.getMainLooper())
                    // The system chooser also grants the app access to the key the person picks.
                    KeyChain.choosePrivateKeyAlias(
                        activity,
                        { alias -> if (alias != null) main.post { state.clientCertAlias = alias } },
                        null,
                        null,
                        address?.host,
                        address?.port ?: -1,
                        state.clientCertAlias,
                    )
                },
            ) {
                Text(stringResource(R.string.action_choose_certificate))
            }
        }
        if (state.clientCertAlias != null) {
            TextButton(onClick = { state.clientCertAlias = null }) {
                Text(stringResource(R.string.action_remove_certificate))
            }
        }
    }
}

@Composable
private fun CertificateStep(state: AddServerState) {
    val certificate = state.certificate ?: return
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            painter = painterResource(R.drawable.ic_security),
            contentDescription = null,
            tint = if (state.certificateChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Text(
            text = stringResource(
                if (state.certificateChanged) R.string.certificate_changed_heading else R.string.certificate_unknown_heading,
            ),
            style = MaterialTheme.typography.titleMedium,
            color = if (state.certificateChanged) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
    Text(
        text = stringResource(
            if (state.certificateChanged) R.string.certificate_changed_body else R.string.certificate_unknown_body,
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    CertificateCard(certificate)
    Text(
        text = stringResource(R.string.certificate_where_to_find),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(
        onClick = state::trustCertificate,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_trust_certificate))
    }
    TextButton(onClick = { state.back() }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.action_cancel))
    }
}

@Composable
private fun CertificateCard(certificate: CertificateInfo) {
    val dateFormat = DateFormat.getDateInstance(DateFormat.MEDIUM)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            CertificateField(stringResource(R.string.certificate_fingerprint)) {
                SelectionContainer {
                    Text(
                        text = certificate.sha256Fingerprint,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            CertificateField(stringResource(R.string.certificate_subject)) { Text(certificate.subject) }
            if (!certificate.isSelfSigned) {
                CertificateField(stringResource(R.string.certificate_issuer)) { Text(certificate.issuer) }
            }
            if (certificate.subjectAltNames.isNotEmpty()) {
                CertificateField(stringResource(R.string.certificate_names)) {
                    Text(certificate.subjectAltNames.joinToString(", "))
                }
            }
            CertificateField(stringResource(R.string.certificate_valid_until)) {
                Text(dateFormat.format(Date(certificate.notAfter)))
            }
        }
    }
}

@Composable
private fun CertificateField(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value()
    }
}

@Composable
private fun SignInStep(state: AddServerState) {
    val methods = listOf(AuthMethod.Password, AuthMethod.ApiToken, AuthMethod.Web)
    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
        methods.forEachIndexed { index, method ->
            SegmentedButton(
                selected = state.method == method,
                onClick = { state.method = method },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = methods.size),
            ) {
                Text(
                    stringResource(
                        when (method) {
                            AuthMethod.Password -> R.string.method_password
                            AuthMethod.ApiToken -> R.string.method_token
                            AuthMethod.Web -> R.string.method_web
                        },
                    ),
                )
            }
        }
    }

    when (state.method) {
        AuthMethod.Password -> {
            OutlinedTextField(
                value = state.username,
                onValueChange = { state.username = it },
                label = { Text(stringResource(R.string.field_username)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            if (state.realms.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = stringResource(R.string.field_realm),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.realms.forEach { realm ->
                            FilterChip(
                                selected = state.realm == realm.realm,
                                onClick = { state.realm = realm.realm },
                                label = { Text(realm.comment?.takeIf { it.isNotBlank() } ?: realm.realm) },
                            )
                        }
                    }
                }
            }
            OutlinedTextField(
                value = state.password,
                onValueChange = { state.password = it },
                label = { Text(stringResource(R.string.field_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { state.submitSignIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(value = state.savePassword, role = Role.Checkbox, onValueChange = { state.savePassword = it }),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = state.savePassword, onCheckedChange = null)
                Text(stringResource(R.string.save_password), modifier = Modifier.padding(start = 12.dp))
            }
            Text(
                text = stringResource(if (state.savePassword) R.string.password_saved_explained else R.string.password_not_stored),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AuthMethod.Web -> {
            Text(text = stringResource(R.string.web_sign_in_explained), style = MaterialTheme.typography.bodyMedium)
            if (state.hasWebRealms) {
                Text(text = stringResource(R.string.web_sign_in_realms), style = MaterialTheme.typography.bodyMedium)
            }
            Text(
                text = stringResource(R.string.web_sign_in_security_keys),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        AuthMethod.ApiToken -> {
            OutlinedTextField(
                value = state.tokenId,
                onValueChange = { state.tokenId = it },
                label = { Text(stringResource(R.string.field_token_id)) },
                placeholder = { Text(stringResource(R.string.field_token_id_placeholder)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Email,
                    imeAction = ImeAction.Next,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = state.tokenSecret,
                onValueChange = { state.tokenSecret = it },
                label = { Text(stringResource(R.string.field_token_secret)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(
                    autoCorrectEnabled = false,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { state.submitSignIn() }),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                text = stringResource(R.string.token_stored_encrypted),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    Button(
        onClick = state::submitSignIn,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(if (state.method == AuthMethod.Web) R.string.action_open_sign_in_page else R.string.action_sign_in))
    }
}

@Composable
private fun SecondFactorStep(state: AddServerState) {
    Text(
        text = stringResource(R.string.second_factor_intro),
        style = MaterialTheme.typography.bodyLarge,
    )
    if (state.factorKinds.size > 1) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.factorKinds.forEach { kind ->
                FilterChip(
                    selected = state.factorKind == kind,
                    onClick = {
                        state.factorKind = kind
                        state.code = ""
                    },
                    label = { Text(stringResource(labelFor(kind))) },
                )
            }
        }
    }
    OutlinedTextField(
        value = state.code,
        onValueChange = { state.code = it },
        label = { Text(stringResource(labelFor(state.factorKind))) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            keyboardType = if (state.factorKind == SecondFactorKind.Totp) KeyboardType.NumberPassword else KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { state.submitSecondFactor() }),
        modifier = Modifier.fillMaxWidth(),
    )
    Button(
        onClick = state::submitSecondFactor,
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(stringResource(R.string.action_verify))
    }
}

private fun labelFor(kind: SecondFactorKind): Int = when (kind) {
    SecondFactorKind.Totp -> R.string.factor_totp
    SecondFactorKind.Recovery -> R.string.factor_recovery
    SecondFactorKind.Yubico -> R.string.factor_yubico
}
