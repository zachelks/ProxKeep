package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate

/**
 * A server with a self-signed certificate that the test profile has pinned,
 * as after adding the server in the app.
 */
class PinnedServer : AutoCloseable {
    private val certificate = HeldCertificate.Builder()
        .commonName("pve.test")
        .addSubjectAlternativeName("pve.test")
        .build()

    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        start()
    }

    val profile = ServerProfile(
        id = "test-server",
        name = "Test",
        host = server.hostName,
        port = server.port,
        pinnedSha256 = Fingerprints.sha256(certificate.certificate),
        authMethod = AuthMethod.ApiToken,
        principal = "root@pam!test",
    )

    fun enqueue(body: String, code: Int = 200, reason: String = "OK") {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 $code $reason").setBody(body))
    }

    /** A repository signed in to this server with an API token. */
    suspend fun signedInRepository(scope: CoroutineScope): ServerRepository {
        val repository = ServerRepository(MemoryProfileStorage(), PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        repository.saveAndActivate(profile, ApiTokenCredentials(profile.principal, "secret"))
        return repository
    }

    override fun close() {
        server.shutdown()
    }
}

class MemoryProfileStorage : ProfileStorage {
    private var state = StoredServers()

    override suspend fun read(): StoredServers = state

    override suspend fun write(state: StoredServers) {
        this.state = state
    }
}

/** Stands in for the Android Keystore, which unit tests do not have. */
class PlainSecrets : SecretCipher {
    override fun encrypt(plainText: String): String = "plain:$plainText"

    override fun decrypt(encoded: String): String? = encoded.removePrefix("plain:")
}
