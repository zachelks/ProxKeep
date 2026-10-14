package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.ConnectionOptions
import io.github.zachelks.proxkeep.core.api.ProbeResult
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.ServerAddress
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.Credentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.probeServer
import io.github.zachelks.proxkeep.core.api.tls.ServerTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.GeneralSecurityException

/** The app's relationship with the selected server. */
sealed interface Session {
    /** No server has been added. */
    data object None : Session

    /** A server is selected but there are no usable credentials for it. */
    data class SignedOut(val profile: ServerProfile) : Session

    /** Signing in with the password saved for [profile]. Ends as [Active] or [SignedOut]. */
    data class SigningIn(val profile: ServerProfile) : Session

    data class Active(val profile: ServerProfile, val client: PveClient) : Session
}

/** A request needed the selected server, but there is no signed-in session. */
class NotSignedInException : IOException("Not signed in")

/**
 * Owns the saved servers and the current session.
 *
 * Login tickets are kept in memory only. API token sessions are restored on
 * start from the encrypted secret. A server with a saved password signs in
 * again on start and when its session expires, unless the account needs a
 * second factor.
 */
class ServerRepository(
    private val store: ProfileStorage,
    private val secrets: SecretCipher,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val clientKeys: ClientKeys = ClientKeys.None,
) {
    private val mutex = Mutex()
    private var renewalJob: Job? = null
    private var signInJob: Job? = null

    private val _servers = MutableStateFlow(StoredServers())
    val servers: StateFlow<StoredServers> = _servers.asStateFlow()

    private val _session = MutableStateFlow<Session>(Session.None)
    val session: StateFlow<Session> = _session.asStateFlow()

    /**
     * The signed-in server's profile ID, or null when signed out. It changes
     * when the server or the sign-in changes, not when a ticket is renewed,
     * so data loaders can restart on it.
     */
    val activeProfileId: Flow<String?> = _session
        .map { (it as? Session.Active)?.profile?.id }
        .distinctUntilChanged()

    /** False until the saved servers have been read from storage. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                val stored = store.read()
                _servers.value = stored
                setSession(restoreSession(stored.active))
                _loaded.value = true
            }
        }
    }

    /**
     * Runs [block] with the signed-in server's client. Every request for the
     * selected server goes through here, so this is the one place that
     * notices the server rejecting the session's credentials: the session is
     * then signed out and the error passed on.
     *
     * @throws NotSignedInException when no server is signed in
     */
    suspend fun <T> request(block: suspend (PveClient) -> T): T {
        val active = _session.value as? Session.Active ?: throw NotSignedInException()
        try {
            return block(active.client)
        } catch (e: PveApiException) {
            if (e.isAuthenticationFailure) sessionExpired(active.profile.id)
            throw e
        }
    }

    /** Builds a client that applies [pinnedSha256] and [options] and carries no credentials. */
    suspend fun newClient(
        address: ServerAddress,
        pinnedSha256: String?,
        options: ConnectionOptions = ConnectionOptions(),
    ): PveClient = withContext(Dispatchers.IO) {
        val trust = ServerTrust(pinnedSha256, clientKey = options.clientKey)
        PveClient(address.apiBaseUrl, options.applyTo(trust.applyTo(http)))
    }

    suspend fun probe(
        address: ServerAddress,
        pinnedSha256: String?,
        options: ConnectionOptions = ConnectionOptions(),
    ): ProbeResult = withContext(Dispatchers.IO) {
        probeServer(address.apiBaseUrl, http, pinnedSha256, options)
    }

    /**
     * The connection settings for [headers] and the client certificate under
     * [clientCertAlias]. Reading the certificate can take a moment.
     *
     * @throws IllegalArgumentException when a header is not valid HTTP
     */
    suspend fun connectionOptions(headers: List<HttpHeader>, clientCertAlias: String?): ConnectionOptions =
        withContext(Dispatchers.IO) {
            ConnectionOptions(headers.map { it.name to it.value }, clientCertAlias?.let(clientKeys::keyManager))
        }

    /**
     * The reverse proxy headers saved with [profile], or null if they cannot
     * be decrypted on this device. Uses the Android Keystore, so it blocks
     * briefly.
     */
    fun savedHeaders(profile: ServerProfile): List<HttpHeader>? {
        val encrypted = profile.encryptedHeaders ?: return emptyList()
        val text = secrets.decrypt(encrypted) ?: return null
        return try {
            headerJson.decodeFromString(HEADER_LIST, text)
        } catch (e: SerializationException) {
            null
        }
    }

    /**
     * Saves [profile], replacing one with the same ID, and starts a session
     * with [credentials]. [headers] are stored encrypted with the profile,
     * and so is [savedPassword] for a password sign-in, if given.
     */
    @Throws(IOException::class)
    suspend fun saveAndActivate(
        profile: ServerProfile,
        credentials: Credentials,
        headers: List<HttpHeader> = emptyList(),
        savedPassword: String? = null,
    ) {
        mutex.withLock {
            val withHeaders = profile.copy(
                encryptedHeaders = if (headers.isEmpty()) null else encrypt(headerJson.encodeToString(HEADER_LIST, headers)),
            )
            val toStore = when (credentials) {
                is ApiTokenCredentials -> withHeaders.copy(encryptedSecret = encrypt(credentials.secret), encryptedPassword = null)
                is TicketCredentials -> withHeaders.copy(
                    encryptedSecret = null,
                    encryptedPassword = savedPassword?.let { encrypt(it) },
                )
            }
            val current = _servers.value.profiles
            val profiles = if (current.any { it.id == toStore.id }) {
                current.map { if (it.id == toStore.id) toStore else it }
            } else {
                current + toStore
            }
            val options = connectionOptions(headers, toStore.clientCertAlias)
            persist(StoredServers(profiles, toStore.id))
            val client = newClient(toStore.address, toStore.pinnedSha256, options).withCredentials(credentials)
            setSession(Session.Active(toStore, client))
        }
    }

    @Throws(IOException::class)
    suspend fun activate(profileId: String) {
        mutex.withLock {
            val profile = _servers.value.profiles.firstOrNull { it.id == profileId } ?: return
            if (sessionProfileId() == profileId) return
            persist(_servers.value.copy(activeId = profileId))
            setSession(restoreSession(profile))
        }
    }

    @Throws(IOException::class)
    suspend fun remove(profileId: String) {
        mutex.withLock {
            val remaining = _servers.value.profiles.filterNot { it.id == profileId }
            val wasSelected = sessionProfileId() == profileId
            val next = StoredServers(remaining, if (wasSelected) remaining.firstOrNull()?.id else _servers.value.activeId)
            persist(next)
            if (wasSelected) setSession(restoreSession(next.active))
        }
    }

    /** The password saved for [profile], or null if there is none or it cannot be decrypted here. */
    fun savedPassword(profile: ServerProfile): String? = profile.encryptedPassword?.let(secrets::decrypt)

    /** Drops the current credentials and any saved password. The server stays in the list. */
    suspend fun signOut() {
        mutex.withLock {
            val profile = when (val current = _session.value) {
                is Session.Active -> current.profile
                is Session.SigningIn -> current.profile
                else -> return
            }
            // Signing out never fails, even if the saved password cannot be removed from storage now.
            val updated = try {
                forgetPassword(profile)
            } catch (e: IOException) {
                profile
            }
            setSession(Session.SignedOut(updated))
        }
    }

    /** Called when the server stops accepting the session's credentials. */
    suspend fun sessionExpired(profileId: String) {
        mutex.withLock {
            val active = _session.value as? Session.Active ?: return
            if (active.profile.id != profileId) return
            val canSignInAgain = active.profile.authMethod == AuthMethod.Password && active.profile.encryptedPassword != null
            setSession(if (canSignInAgain) Session.SigningIn(active.profile) else Session.SignedOut(active.profile))
        }
    }

    private fun sessionProfileId(): String? = when (val current = _session.value) {
        is Session.Active -> current.profile.id
        is Session.SignedOut -> current.profile.id
        is Session.SigningIn -> current.profile.id
        Session.None -> null
    }

    /** Removes the saved password from [profile] and storage. Returns the updated profile. */
    private suspend fun forgetPassword(profile: ServerProfile): ServerProfile {
        if (profile.encryptedPassword == null) return profile
        val updated = profile.copy(encryptedPassword = null)
        persist(_servers.value.copy(profiles = _servers.value.profiles.map { if (it.id == profile.id) updated else it }))
        return updated
    }

    /**
     * Signs in to [profile] with its saved password. The outcome replaces
     * the session only if it is still waiting for this sign-in.
     */
    private suspend fun signInWithSavedPassword(profile: ServerProfile) {
        var rejected = false
        val next: Session = try {
            val password = withContext(Dispatchers.IO) { savedPassword(profile) }
            val headers = withContext(Dispatchers.IO) { savedHeaders(profile) }
            if (password == null || headers == null) {
                Session.SignedOut(profile)
            } else {
                val client = newClient(profile.address, profile.pinnedSha256, connectionOptions(headers, profile.clientCertAlias))
                when (val result = client.login(profile.principal.substringBeforeLast('@'), profile.principal.substringAfterLast('@'), password)) {
                    is LoginResult.Success -> Session.Active(profile, client.withCredentials(result.credentials))
                    // The person has to enter the code; the sign-in screen starts with the saved password.
                    is LoginResult.SecondFactorRequired -> Session.SignedOut(profile)
                }
            }
        } catch (e: PveApiException) {
            rejected = e.isAuthenticationFailure
            Session.SignedOut(profile)
        } catch (e: IOException) {
            Session.SignedOut(profile)
        } catch (e: IllegalArgumentException) {
            Session.SignedOut(profile)
        }
        mutex.withLock {
            val now = _session.value
            if (now !is Session.SigningIn || now.profile.id != profile.id) return
            signInJob = null
            // A password the server refuses is not tried again, so an account is never locked out by retries.
            val outcome = if (rejected) {
                Session.SignedOut(
                    try {
                        forgetPassword(profile)
                    } catch (e: IOException) {
                        profile
                    },
                )
            } else {
                next
            }
            setSession(outcome)
        }
    }

    private suspend fun persist(next: StoredServers) {
        store.write(next)
        _servers.value = next
    }

    private suspend fun encrypt(secret: String): String = withContext(Dispatchers.IO) {
        try {
            secrets.encrypt(secret)
        } catch (e: GeneralSecurityException) {
            throw IOException("The device could not encrypt the secret", e)
        }
    }

    private suspend fun restoreSession(profile: ServerProfile?): Session {
        if (profile == null) return Session.None
        if (profile.authMethod == AuthMethod.Password && profile.encryptedPassword != null) return Session.SigningIn(profile)
        val encrypted = profile.encryptedSecret
        if (profile.authMethod == AuthMethod.ApiToken && encrypted != null) {
            val secret = withContext(Dispatchers.IO) { secrets.decrypt(encrypted) }
            // Headers that cannot be decrypted are needed to reach the server; signing in again restores them.
            val headers = withContext(Dispatchers.IO) { savedHeaders(profile) }
            if (secret != null && headers != null) {
                val credentials = ApiTokenCredentials(profile.principal, secret)
                val options = try {
                    connectionOptions(headers, profile.clientCertAlias)
                } catch (e: IllegalArgumentException) {
                    return Session.SignedOut(profile)
                }
                return Session.Active(profile, newClient(profile.address, profile.pinnedSha256, options).withCredentials(credentials))
            }
        }
        return Session.SignedOut(profile)
    }

    private fun setSession(next: Session) {
        _session.value = next
        renewalJob?.cancel()
        renewalJob = null
        signInJob?.cancel()
        signInJob = null
        if (next is Session.Active && next.client.credentials is TicketCredentials) {
            renewalJob = scope.launch { keepTicketFresh(next.profile.id) }
        }
        if (next is Session.SigningIn) {
            signInJob = scope.launch { signInWithSavedPassword(next.profile) }
        }
    }

    /**
     * Tickets expire two hours after issue. Renewing well inside that window
     * keeps a session alive for as long as the app's process does.
     */
    private suspend fun keepTicketFresh(profileId: String) {
        while (true) {
            delay(RENEW_INTERVAL_MS)
            val active = _session.value as? Session.Active ?: return
            val ticket = active.client.credentials as? TicketCredentials ?: return
            if (active.profile.id != profileId) return
            try {
                val renewed = active.client.renewTicket(ticket)
                mutex.withLock {
                    val now = _session.value
                    // Replace the client only if nothing changed while the request ran.
                    if (now is Session.Active && now.client === active.client) {
                        _session.value = now.copy(client = now.client.withCredentials(renewed))
                    }
                }
            } catch (e: PveApiException) {
                if (e.isAuthenticationFailure) {
                    sessionExpired(profileId)
                    return
                }
            } catch (e: IOException) {
                // Offline or unreachable. The next round tries again.
            }
        }
    }

    private companion object {
        const val RENEW_INTERVAL_MS = 30L * 60L * 1000L
        val HEADER_LIST = ListSerializer(HttpHeader.serializer())
        val headerJson = Json { ignoreUnknownKeys = true }
    }
}
