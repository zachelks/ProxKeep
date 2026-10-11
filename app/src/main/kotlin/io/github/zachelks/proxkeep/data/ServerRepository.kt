package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.ProbeResult
import io.github.zachelks.proxkeep.core.api.PveApiException
import io.github.zachelks.proxkeep.core.api.PveClient
import io.github.zachelks.proxkeep.core.api.ServerAddress
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.Credentials
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.probeServer
import io.github.zachelks.proxkeep.core.api.tls.ServerTrust
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.IOException
import java.security.GeneralSecurityException

/** The app's relationship with the selected server. */
sealed interface Session {
    /** No server has been added. */
    data object None : Session

    /** A server is selected but there are no usable credentials for it. */
    data class SignedOut(val profile: ServerProfile) : Session

    data class Active(val profile: ServerProfile, val client: PveClient) : Session
}

/**
 * Owns the saved servers and the current session.
 *
 * Login tickets are kept in memory only, so a password sign-in lasts until
 * the app's process ends. API token sessions are restored on start from the
 * encrypted secret.
 */
class ServerRepository(
    private val store: ProfileStore,
    private val secrets: SecretBox,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private var renewalJob: Job? = null

    private val _servers = MutableStateFlow(StoredServers())
    val servers: StateFlow<StoredServers> = _servers.asStateFlow()

    private val _session = MutableStateFlow<Session>(Session.None)
    val session: StateFlow<Session> = _session.asStateFlow()

    /** False until the saved servers have been read from storage. */
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch {
            mutex.withLock {
                val stored = store.read()
                _servers.value = stored
                _session.value = restoreSession(stored.active)
                _loaded.value = true
            }
        }
    }

    /** Builds a client that applies [pinnedSha256] and carries no credentials. */
    suspend fun newClient(address: ServerAddress, pinnedSha256: String?): PveClient =
        withContext(Dispatchers.IO) {
            PveClient(address.apiBaseUrl, ServerTrust(pinnedSha256).applyTo(http))
        }

    suspend fun probe(address: ServerAddress, pinnedSha256: String?): ProbeResult =
        withContext(Dispatchers.IO) {
            probeServer(address.apiBaseUrl, http, pinnedSha256)
        }

    /** Saves [profile], replacing one with the same ID, and starts a session with [credentials]. */
    @Throws(IOException::class)
    suspend fun saveAndActivate(profile: ServerProfile, credentials: Credentials) {
        mutex.withLock {
            val toStore = when (credentials) {
                is ApiTokenCredentials -> profile.copy(encryptedSecret = encrypt(credentials.secret))
                is TicketCredentials -> profile.copy(encryptedSecret = null)
            }
            val current = _servers.value.profiles
            val profiles = if (current.any { it.id == toStore.id }) {
                current.map { if (it.id == toStore.id) toStore else it }
            } else {
                current + toStore
            }
            persist(StoredServers(profiles, toStore.id))
            val client = newClient(toStore.address, toStore.pinnedSha256).withCredentials(credentials)
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

    /** Drops the current credentials. The server stays in the list. */
    suspend fun signOut() {
        mutex.withLock {
            val active = _session.value as? Session.Active ?: return
            setSession(Session.SignedOut(active.profile))
        }
    }

    /** Called when the server stops accepting the session's credentials. */
    suspend fun sessionExpired(profileId: String) {
        mutex.withLock {
            val active = _session.value as? Session.Active ?: return
            if (active.profile.id == profileId) setSession(Session.SignedOut(active.profile))
        }
    }

    private fun sessionProfileId(): String? = when (val current = _session.value) {
        is Session.Active -> current.profile.id
        is Session.SignedOut -> current.profile.id
        Session.None -> null
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
        val encrypted = profile.encryptedSecret
        if (profile.authMethod == AuthMethod.ApiToken && encrypted != null) {
            val secret = withContext(Dispatchers.IO) { secrets.decrypt(encrypted) }
            if (secret != null) {
                val credentials = ApiTokenCredentials(profile.principal, secret)
                return Session.Active(profile, newClient(profile.address, profile.pinnedSha256).withCredentials(credentials))
            }
        }
        return Session.SignedOut(profile)
    }

    private fun setSession(next: Session) {
        _session.value = next
        renewalJob?.cancel()
        renewalJob = null
        if (next is Session.Active && next.client.credentials is TicketCredentials) {
            renewalJob = scope.launch { keepTicketFresh(next.profile.id) }
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
    }
}
