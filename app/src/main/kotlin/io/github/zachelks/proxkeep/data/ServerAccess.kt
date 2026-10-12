package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.model.Permissions
import io.github.zachelks.proxkeep.core.api.model.VersionInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

/**
 * What the signed-in server runs and what the session's credentials may do
 * there. Screens use it to offer only what the server supports and allows.
 *
 * @property version null until it has been read
 * @property permissions null until they have been read
 */
data class ServerAccess(
    val profileId: String,
    val version: VersionInfo? = null,
    val permissions: Permissions? = null,
) {
    /**
     * Whether to offer something that needs [privilege] on any of [paths].
     * While the permissions are unknown everything is offered: the server
     * checks every request anyway, and hiding too much is worse than a
     * refusal.
     */
    fun allows(paths: List<String>, privilege: String): Boolean =
        permissions?.hasAny(paths, privilege) ?: true
}

/**
 * Reads [ServerAccess] for the signed-in server while a screen is watching,
 * and again from time to time.
 */
class ServerAccessRepository(
    private val servers: ServerRepository,
    scope: CoroutineScope,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    val access: StateFlow<ServerAccess?> = servers.activeProfileId
        .flatMapLatest { profileId -> if (profileId == null) flowOf(null) else follow(profileId) }
        .stateIn(scope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private fun follow(profileId: String): Flow<ServerAccess> = flow {
        val previous = access.value?.takeIf { it.profileId == profileId }
        var current = previous ?: ServerAccess(profileId)
        emit(current)
        // Returning to a server already read waits a full interval before reading again.
        if (previous?.version != null && previous.permissions != null) delay(REFRESH_MS)
        while (true) {
            // Each part is kept from the last good read if it cannot be read now.
            val version = try {
                servers.request { it.version() }
            } catch (e: IOException) {
                current.version
            }
            val permissions = try {
                servers.request { it.permissions() }
            } catch (e: IOException) {
                current.permissions
            }
            current = ServerAccess(profileId, version, permissions)
            emit(current)
            delay(if (version == null || permissions == null) RETRY_MS else REFRESH_MS)
        }
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L

        /** Permissions change rarely, but an administrator may change them while the app is open. */
        const val REFRESH_MS = 5L * 60L * 1000L
        const val RETRY_MS = 30_000L
    }
}
