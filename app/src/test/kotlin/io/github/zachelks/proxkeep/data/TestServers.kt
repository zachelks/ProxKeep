package io.github.zachelks.proxkeep.data

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue

/**
 * A server with a self-signed certificate that the test profile has pinned,
 * as after adding the server in the app.
 */
class PinnedServer(id: String = "test-server") : AutoCloseable {
    private val certificate = HeldCertificate.Builder()
        .commonName("pve.test")
        .addSubjectAlternativeName("pve.test")
        .build()

    private val queue = LinkedBlockingQueue<MockResponse>()
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()

    /** The path of every request received, in order, without the `/api2/json` prefix or the query. */
    val requestedPaths: MutableList<String> = CopyOnWriteArrayList()

    val server = MockWebServer().apply {
        useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.apiPath
                requestedPaths += path
                return routes[path]?.invoke(request) ?: queue.take()
            }

            override fun shutdown() {
                // Releases a request still waiting for a response.
                queue.put(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START))
            }
        }
        start()
    }

    /** The certificate's fingerprint, as a person pins it in the app. */
    val fingerprint: String = Fingerprints.sha256(certificate.certificate)

    val profile = ServerProfile(
        id = id,
        name = "Test",
        host = server.hostName,
        port = server.port,
        pinnedSha256 = fingerprint,
        authMethod = AuthMethod.ApiToken,
        principal = "root@pam!test",
    )

    /** Answers the next request that has no [route], in the order enqueued. */
    fun enqueue(body: String, code: Int = 200, reason: String = "OK") {
        queue.put(response(body, code, reason))
    }

    /**
     * Answers every request for [path], such as `/cluster/resources`, with
     * the same response, whatever order requests arrive in.
     */
    fun route(path: String, body: String, code: Int = 200, reason: String = "OK") {
        routes[path] = { response(body, code, reason) }
    }

    /** Answers every request for [path] with what [respond] returns for it. */
    fun route(path: String, respond: (RecordedRequest) -> MockResponse) {
        routes[path] = respond
    }

    fun requestCount(path: String): Int = requestedPaths.count { it == path }

    /** A repository signed in to this server with an API token. */
    suspend fun signedInRepository(
        scope: CoroutineScope,
        storage: ProfileStorage = MemoryProfileStorage(),
    ): ServerRepository {
        val repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        repository.saveAndActivate(profile, ApiTokenCredentials(profile.principal, "secret"))
        return repository
    }

    override fun close() {
        server.shutdown()
    }
}

fun response(body: String, code: Int = 200, reason: String = "OK"): MockResponse =
    MockResponse().setStatus("HTTP/1.1 $code $reason").setBody(body)

private val RecordedRequest.apiPath: String
    get() = requestUrl?.encodedPath.orEmpty().removePrefix("/api2/json")

class MemoryProfileStorage(private var state: StoredServers = StoredServers()) : ProfileStorage {

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
