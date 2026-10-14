package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import io.github.zachelks.proxkeep.core.api.tls.ServerTrust
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A reverse proxy that wants an access header and, in some setups, a client certificate. */
class ConnectionOptionsTest {

    private val serverCertificate = HeldCertificate.Builder().commonName("proxy.test").build()
    private val fingerprint = Fingerprints.sha256(serverCertificate.certificate)
    private val clientCa = HeldCertificate.Builder().certificateAuthority(0).commonName("Client CA").build()
    private val clientCertificate = HeldCertificate.Builder().commonName("phone").signedBy(clientCa).build()
    private val server = MockWebServer()
    private val realms = """{"data":[{"realm":"pam","type":"pam"}]}"""

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun start(requireClientCertificate: Boolean = false) {
        val handshake = HandshakeCertificates.Builder()
            .heldCertificate(serverCertificate)
            .addTrustedCertificate(clientCa.certificate)
            .build()
        server.useHttps(handshake.sslSocketFactory(), false)
        if (requireClientCertificate) server.requireClientAuth()
        server.start()
    }

    @Test
    fun headersAreSentWithEveryRequestButNeverReplaceTheSessionsOwn(): Unit = runBlocking {
        start()
        server.enqueue(MockResponse().setBody(realms))
        server.enqueue(MockResponse().setBody("""{"data":{"version":"9.2.1"}}"""))
        val options = ConnectionOptions(listOf("CF-Access-Client-Id" to "abc", "Authorization" to "Basic proxy"))

        assertIs<ProbeResult.Reachable>(probeServer(server.url("/api2/json/"), OkHttpClient(), fingerprint, options))
        val http = options.applyTo(ServerTrust(fingerprint).applyTo(OkHttpClient()))
        PveClient(server.url("/api2/json/"), http).withCredentials(ApiTokenCredentials("root@pam!app", "secret")).version()

        val probe = server.takeRequest()
        assertEquals("abc", probe.getHeader("CF-Access-Client-Id"))
        assertEquals("Basic proxy", probe.getHeader("Authorization"), "a request without credentials gets the proxy's")
        val signedIn = server.takeRequest()
        assertEquals("abc", signedIn.getHeader("CF-Access-Client-Id"))
        assertEquals("PVEAPIToken=root@pam!app=secret", signedIn.getHeader("Authorization"))
    }

    @Test
    fun aClientCertificateIsPresentedWhenTheServerRequiresOne(): Unit = runBlocking {
        start(requireClientCertificate = true)
        server.enqueue(MockResponse().setBody(realms))
        val clientKey = HandshakeCertificates.Builder().heldCertificate(clientCertificate, clientCa.certificate).build().keyManager

        val without = probeServer(server.url("/api2/json/"), OkHttpClient(), fingerprint)
        val with = probeServer(server.url("/api2/json/"), OkHttpClient(), fingerprint, ConnectionOptions(clientKey = clientKey))

        assertIs<ProbeResult.Failed>(without)
        assertIs<ProbeResult.Reachable>(with)
        assertEquals("CN=phone", server.takeRequest().handshake?.peerPrincipal?.name)
    }

    @Test
    fun invalidHeadersAreRefusedUpFront() {
        assertFailsWith<IllegalArgumentException> { ConnectionOptions(listOf("Bad Name" to "x")) }
        assertFailsWith<IllegalArgumentException> { ConnectionOptions(listOf("X-Token" to "line\nbreak")) }
        assertTrue(ConnectionOptions.isValidName("X-Auth-Token"))
        assertFalse(ConnectionOptions.isValidName("X Token"))
        assertFalse(ConnectionOptions.isValidName(""))
        assertTrue(ConnectionOptions.isValidValue("Bearer abc.def"))
        assertFalse(ConnectionOptions.isValidValue("ü"))
    }
}
