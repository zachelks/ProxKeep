package io.github.zachelks.proxkeep.ui.addserver

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.ConnectionOptions
import io.github.zachelks.proxkeep.core.api.ProbeResult
import io.github.zachelks.proxkeep.core.api.ServerAddress
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.Credentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TfaChallenge
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.Realm
import io.github.zachelks.proxkeep.core.api.tls.CertificateInfo
import io.github.zachelks.proxkeep.data.AuthMethod
import io.github.zachelks.proxkeep.data.HttpHeader
import io.github.zachelks.proxkeep.data.ServerProfile
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.ui.UiMessage
import io.github.zachelks.proxkeep.ui.toUiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

/**
 * State and logic of the flow that adds a server or signs in to a saved one:
 * address, certificate check, sign-in, second factor.
 *
 * Passwords, token secrets and codes are held here only while the flow is
 * open and are cleared when it finishes or is cancelled.
 *
 * @param existing the saved server being signed in to, or null to add a new one
 * @param existingHeaders the reverse proxy headers saved with [existing]
 * @param existingPassword the password saved for [existing], if the person chose to save it
 */
class AddServerState(
    private val repository: ServerRepository,
    private val scope: CoroutineScope,
    private val existing: ServerProfile?,
    existingHeaders: List<HttpHeader> = emptyList(),
    existingPassword: String? = null,
) {
    enum class Step { Address, Certificate, SignIn, SecondFactor, Done }

    var step by mutableStateOf(Step.Address)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<UiMessage?>(null)
        private set

    val isNewServer: Boolean = existing == null

    // Address step
    var host by mutableStateOf(existing?.address?.display.orEmpty())
    var name by mutableStateOf(existing?.name.orEmpty())

    // Reverse proxy settings, on the address step
    var headers by mutableStateOf(existingHeaders)
        private set
    var clientCertAlias by mutableStateOf(existing?.clientCertAlias)

    // Certificate step
    var certificate by mutableStateOf<CertificateInfo?>(null)
        private set

    /** True when the server had a pinned certificate and now presents a different one. */
    var certificateChanged by mutableStateOf(false)
        private set

    // Sign-in step
    var realms by mutableStateOf<List<Realm>>(emptyList())
        private set
    var method by mutableStateOf(existing?.authMethod ?: AuthMethod.Password)
    var username by mutableStateOf(passwordPrincipal()?.substringBeforeLast('@').orEmpty())
    var realm by mutableStateOf(passwordPrincipal()?.substringAfterLast('@') ?: DEFAULT_REALM)
    var password by mutableStateOf(existingPassword.orEmpty())

    /** Whether to keep the password, encrypted, so the app can sign in again by itself. */
    var savePassword by mutableStateOf(existing?.encryptedPassword != null)
    var tokenId by mutableStateOf(existing?.takeIf { it.authMethod == AuthMethod.ApiToken }?.principal.orEmpty())
    var tokenSecret by mutableStateOf("")

    // Second-factor step
    var factorKinds by mutableStateOf<List<SecondFactorKind>>(emptyList())
        private set
    var factorKind by mutableStateOf(SecondFactorKind.Totp)
    var code by mutableStateOf("")

    private var address: ServerAddress? = null
    private var options = ConnectionOptions()
    private var acceptedHeaders: List<HttpHeader> = emptyList()
    private var passwordToSave: String? = null
    private var pin: String? = existing?.pinnedSha256
    private var challenge: TfaChallenge? = null
    private var challengeUser: String? = null
    private var job: Job? = null

    private fun passwordPrincipal(): String? =
        existing?.takeIf { it.authMethod == AuthMethod.Password }?.principal

    fun addHeader() {
        headers = headers + HttpHeader("", "")
    }

    fun setHeader(index: Int, header: HttpHeader) {
        headers = headers.toMutableList().also { it[index] = header }
    }

    fun removeHeader(index: Int) {
        headers = headers.toMutableList().also { it.removeAt(index) }
    }

    /** Contacts the server at [host] and moves on to the certificate or sign-in step. */
    fun submitAddress() {
        val parsed = ServerAddress.parse(host)
        if (parsed == null) {
            error = UiMessage(R.string.error_invalid_address)
            return
        }
        val wanted = headers
            .map { HttpHeader(it.name.trim(), it.value.trim()) }
            .filterNot { it.name.isEmpty() && it.value.isEmpty() }
        if (wanted.any { !ConnectionOptions.isValidName(it.name) || !ConnectionOptions.isValidValue(it.value) }) {
            error = UiMessage(R.string.error_invalid_header)
            return
        }
        val alias = clientCertAlias
        // A pinned fingerprint belongs to one address and is not carried to another.
        val knownPin = existing?.takeIf { it.address == parsed }?.pinnedSha256
        launch {
            val connection = repository.connectionOptions(wanted, alias)
            if (alias != null && connection.clientKey == null) {
                error = UiMessage(R.string.error_client_certificate_unavailable)
                return@launch
            }
            options = connection
            acceptedHeaders = wanted
            when (val result = repository.probe(parsed, knownPin, connection)) {
                is ProbeResult.Reachable -> {
                    pin = knownPin
                    showSignIn(parsed, result.realms)
                }
                is ProbeResult.UntrustedCertificate -> {
                    address = parsed
                    certificate = result.certificate
                    certificateChanged = knownPin != null
                    step = Step.Certificate
                }
                is ProbeResult.Failed -> {
                    error = result.cause.toUiMessage()
                }
            }
        }
    }

    /** Pins the certificate on screen, after the person confirmed its fingerprint. */
    fun trustCertificate() {
        val target = address ?: return
        val shown = certificate ?: return
        launch {
            when (val result = repository.probe(target, shown.sha256Fingerprint, options)) {
                is ProbeResult.Reachable -> {
                    pin = shown.sha256Fingerprint
                    showSignIn(target, result.realms)
                }
                is ProbeResult.UntrustedCertificate -> {
                    // The server presented yet another certificate. Show it; do not accept it silently.
                    certificate = result.certificate
                    certificateChanged = true
                    error = UiMessage(R.string.error_certificate_changed_again)
                }
                is ProbeResult.Failed -> {
                    error = result.cause.toUiMessage()
                }
            }
        }
    }

    private fun showSignIn(target: ServerAddress, serverRealms: List<Realm>) {
        address = target
        realms = serverRealms.filterNot { it.usesWebLogin }
        if (realms.isNotEmpty() && realms.none { it.realm == realm }) {
            realm = (realms.firstOrNull { it.isDefault } ?: realms.first()).realm
        }
        step = Step.SignIn
    }

    fun submitSignIn() {
        val target = address ?: return
        when (method) {
            AuthMethod.Password -> {
                val user = username.trim()
                val secret = password
                if (user.isEmpty() || secret.isEmpty()) {
                    error = UiMessage(R.string.error_missing_credentials)
                    return
                }
                passwordToSave = secret.takeIf { savePassword }
                launch {
                    val client = repository.newClient(target, pin, options)
                    when (val result = client.login(user, realm, secret)) {
                        is LoginResult.Success -> finish(target, result.credentials, result.clusterName)
                        is LoginResult.SecondFactorRequired -> showSecondFactor(result)
                    }
                }
            }
            AuthMethod.ApiToken -> {
                val id = tokenId.trim()
                val secret = tokenSecret.trim()
                if (!ApiTokenCredentials.isValidTokenId(id) || secret.isEmpty()) {
                    error = UiMessage(R.string.error_invalid_token)
                    return
                }
                launch {
                    val credentials = ApiTokenCredentials(id, secret)
                    // Any authenticated call proves the token works; this one needs no privileges.
                    repository.newClient(target, pin, options).withCredentials(credentials).version()
                    finish(target, credentials, clusterName = null)
                }
            }
        }
    }

    private fun showSecondFactor(result: LoginResult.SecondFactorRequired) {
        val kinds = result.challenge.codeKinds
        if (kinds.isEmpty()) {
            error = UiMessage(
                if (result.challenge.webauthn) R.string.error_security_key_unsupported else R.string.error_second_factor_unsupported,
            )
            return
        }
        challenge = result.challenge
        challengeUser = result.username
        factorKinds = kinds
        factorKind = kinds.first()
        code = ""
        step = Step.SecondFactor
    }

    fun submitSecondFactor() {
        val target = address ?: return
        val pending = challenge ?: return
        val user = challengeUser ?: return
        val entered = code.trim()
        if (entered.isEmpty()) {
            error = UiMessage(R.string.error_missing_code)
            return
        }
        launch {
            val result = repository.newClient(target, pin, options).completeSecondFactor(user, pending, factorKind, entered)
            finish(target, result.credentials, result.clusterName)
        }
    }

    private suspend fun finish(target: ServerAddress, credentials: Credentials, clusterName: String?) {
        val principal = when (credentials) {
            is TicketCredentials -> credentials.username
            is ApiTokenCredentials -> credentials.tokenId
        }
        val profile = ServerProfile(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifEmpty { clusterName ?: target.host },
            host = target.host,
            port = target.port,
            pinnedSha256 = pin,
            authMethod = method,
            principal = principal,
            clientCertAlias = clientCertAlias,
        )
        repository.saveAndActivate(profile, credentials, acceptedHeaders, passwordToSave.takeIf { method == AuthMethod.Password })
        clearSecrets()
        step = Step.Done
    }

    /** Steps back once. Returns false when there is nothing to step back to. */
    fun back(): Boolean {
        if (busy) {
            job?.cancel()
            return true
        }
        error = null
        return when (step) {
            Step.Address, Step.Done -> false
            Step.Certificate, Step.SignIn -> {
                step = Step.Address
                true
            }
            Step.SecondFactor -> {
                code = ""
                challenge = null
                step = Step.SignIn
                true
            }
        }
    }

    /** Stops any request in flight and forgets everything secret. */
    fun cancel() {
        job?.cancel()
        clearSecrets()
    }

    private fun clearSecrets() {
        headers = emptyList()
        acceptedHeaders = emptyList()
        options = ConnectionOptions()
        passwordToSave = null
        password = ""
        tokenSecret = ""
        code = ""
        challenge = null
        challengeUser = null
    }

    private fun launch(block: suspend () -> Unit) {
        if (busy) return
        error = null
        busy = true
        job = scope.launch {
            try {
                block()
            } catch (e: IOException) {
                error = e.toUiMessage()
            } finally {
                busy = false
            }
        }
    }

    private companion object {
        const val DEFAULT_REALM = "pam"
    }
}
