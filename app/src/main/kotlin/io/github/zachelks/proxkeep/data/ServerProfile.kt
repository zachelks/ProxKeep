package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.ServerAddress
import kotlinx.serialization.Serializable

@Serializable
enum class AuthMethod { Password, ApiToken }

/**
 * A saved server.
 *
 * Passwords are never stored. For API tokens the secret is stored encrypted
 * in [encryptedSecret].
 */
@Serializable
data class ServerProfile(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    /** Fingerprint of the certificate the person chose to trust, if any. */
    val pinnedSha256: String? = null,
    val authMethod: AuthMethod,
    /** `user@realm` for password sign-in, `user@realm!name` for an API token. */
    val principal: String,
    /** API token secret, encrypted with a key held in the Android Keystore. */
    val encryptedSecret: String? = null,
) {
    val address: ServerAddress
        get() = ServerAddress(host, port)
}

/** Everything the app persists about servers. */
@Serializable
data class StoredServers(
    val profiles: List<ServerProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: ServerProfile?
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}
