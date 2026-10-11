package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.tls.Fingerprints
import io.github.zachelks.proxkeep.core.api.tls.ServerTrust
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import java.io.IOException
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs real TLS handshakes against a server with a self-signed certificate
 * issued for a name other than the one the client connects to, the usual
 * situation for a Proxmox VE server reached by IP address.
 */
class ServerTrustTest {

    private lateinit var server: MockWebServer
    private val http = OkHttpClient()

    private val serverCertificate = HeldCertificate.Builder()
        .commonName("pve.internal")
        .addSubjectAlternativeName("pve.internal")
        .build()
    private val fingerprint = Fingerprints.sha256(serverCertificate.certificate)

    private val realmsBody = """{"data":[{"realm":"pam","type":"pam"}]}"""

    @BeforeTest
    fun setUp() {
        val handshake = HandshakeCertificates.Builder().heldCertificate(serverCertificate).build()
        server = MockWebServer()
        server.useHttps(handshake.sslSocketFactory(), false)
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private val baseUrl get() = server.url("/api2/json/")

    @Test
    fun unknownCertificateIsReportedAndNothingIsSent(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(realmsBody))

        val result = assertIs<ProbeResult.UntrustedCertificate>(probeServer(baseUrl, http))

        assertEquals(fingerprint, result.certificate.sha256Fingerprint)
        assertTrue(result.certificate.isSelfSigned)
        assertEquals(listOf("pve.internal"), result.certificate.subjectAltNames)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun pinnedCertificateIsAcceptedDespiteTheHostName(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(realmsBody))

        val result = assertIs<ProbeResult.Reachable>(probeServer(baseUrl, http, pinnedSha256 = fingerprint))

        assertEquals(listOf("pam"), result.realms.map { it.realm })
        assertEquals(fingerprint, result.certificate?.sha256Fingerprint)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun pinIsAcceptedInAnySpelling(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(realmsBody))
        val typed = fingerprint.replace(":", " ").lowercase()

        assertIs<ProbeResult.Reachable>(probeServer(baseUrl, http, pinnedSha256 = typed))
    }

    @Test
    fun differentPinIsRejected(): Unit = runBlocking {
        server.enqueue(MockResponse().setBody(realmsBody))
        val other = Fingerprints.sha256(HeldCertificate.Builder().commonName("other").build().certificate)

        val result = assertIs<ProbeResult.UntrustedCertificate>(probeServer(baseUrl, http, pinnedSha256 = other))

        // The certificate now on offer is reported so the change can be shown.
        assertEquals(fingerprint, result.certificate.sha256Fingerprint)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun clientWithoutThePinCannotConnect() {
        server.enqueue(MockResponse().setBody(realmsBody))
        val client = PveClient(baseUrl, ServerTrust().applyTo(http))

        assertFailsWith<IOException> { runBlocking { client.realms() } }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun unreachableServerIsAFailureNotACertificatePrompt(): Unit = runBlocking {
        // A port that was just released, so nothing is listening on it.
        val closedPort = ServerSocket(0).use { it.localPort }
        val url = ServerAddress("localhost", closedPort).apiBaseUrl

        assertIs<ProbeResult.Failed>(probeServer(url, http))
    }

    @Test
    fun fingerprintsAreNormalized() {
        assertEquals(fingerprint, Fingerprints.normalize(fingerprint.lowercase()))
        assertEquals(fingerprint, Fingerprints.normalize(fingerprint.replace(":", "")))
        assertNull(Fingerprints.normalize("AA:BB"))
        assertNull(Fingerprints.normalize("Z".repeat(64)))
    }
}
