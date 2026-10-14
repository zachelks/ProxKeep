package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.ServerAddress
import kotlinx.serialization.Serializable

@Serializable
enum class AuthMethod {
    Password,
    ApiToken,

    /** The server's own login page in the app, for security keys and OpenID Connect. */
    Web,
}

/**
 * A saved server.
 *
 * Passwords are stored only when the person chooses to, encrypted in
 * [encryptedPassword]. For API tokens the secret is stored encrypted in
 * [encryptedSecret].
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
    /** Extra HTTP headers for a reverse proxy, as encrypted JSON, since they often carry access tokens. */
    val encryptedHeaders: String? = null,
    /** Android KeyChain alias of the client certificate a reverse proxy asks for. */
    val clientCertAlias: String? = null,
    /** The password, encrypted, only when the person chose to save it. */
    val encryptedPassword: String? = null,
) {
    val address: ServerAddress
        get() = ServerAddress(host, port)
}

/** An extra HTTP header sent to a server, usually for a reverse proxy in front of it. */
@Serializable
data class HttpHeader(val name: String, val value: String)

/** Everything the app persists about servers. */
@Serializable
data class StoredServers(
    val profiles: List<ServerProfile> = emptyList(),
    val activeId: String? = null,
) {
    val active: ServerProfile?
        get() = profiles.firstOrNull { it.id == activeId } ?: profiles.firstOrNull()
}
