package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PveClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PveClient

    private val ticket = "PVE:root@pam:6712F3A1::c2lnbmF0dXJl"
    private val challengeTicket =
        "PVE:!tfa!%7B%22totp%22%3Atrue%2C%22recovery%22%3A%5B0%5D%7D:6712F3A1::Y2hhbGxlbmdl"

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PveClient(server.url("/api2/json/"), OkHttpClient())
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String, code: Int = 200, reason: String = "OK") {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 $code $reason").setBody(body))
    }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            val name = URLDecoder.decode(pair.substringBefore('='), "UTF-8")
            val value = URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
            name to value
        }

    @Test
    fun realmsNeedNoCredentials(): Unit = runBlocking {
        enqueue(
            """{"data":[{"realm":"pam","type":"pam","comment":"Linux PAM standard authentication"},
               {"realm":"pve","type":"pve","comment":"Proxmox VE authentication server","default":1},
               {"realm":"sso","type":"openid"}]}""",
        )
        val authenticated = client.withCredentials(TicketCredentials("root@pam", ticket, "csrf"))
        val realms = authenticated.realms()

        assertEquals(listOf("pam", "pve", "sso"), realms.map { it.realm })
        assertEquals("pve", realms.single { it.isDefault }.realm)
        assertTrue(realms.last().usesWebLogin)

        val request = server.takeRequest()
        assertEquals("GET", request.method)
        assertEquals("/api2/json/access/domains", request.path)
        assertNull(request.getHeader("Cookie"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun passwordLoginReturnsATicket(): Unit = runBlocking {
        enqueue(
            """{"data":{"username":"root@pam","ticket":"$ticket",
               "CSRFPreventionToken":"6712F3A1:Y3NyZg","clustername":"lab","cap":{"vms":{"VM.Audit":1}}}}""",
        )
        val result = assertIs<LoginResult.Success>(client.login("root", "pam", "p@ss word&1"))

        assertEquals(TicketCredentials("root@pam", ticket, "6712F3A1:Y3NyZg"), result.credentials)
        assertEquals("lab", result.clusterName)

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api2/json/access/ticket", request.path)
        assertEquals(mapOf("username" to "root@pam", "password" to "p@ss word&1"), form(request))
    }

    @Test
    fun wrongPasswordIsAnAuthenticationFailure(): Unit = runBlocking {
        enqueue("""{"data":null}""", code = 401, reason = "authentication failure")
        val error = assertFailsWith<PveApiException> { client.login("root", "pam", "wrong") }

        assertTrue(error.isAuthenticationFailure)
        assertEquals("authentication failure", error.statusMessage)
    }

    @Test
    fun secondFactorLoginTakesTwoSteps(): Unit = runBlocking {
        enqueue("""{"data":{"username":"root@pam","ticket":"$challengeTicket","NeedTFA":1}}""")
        enqueue("""{"data":{"username":"root@pam","ticket":"$ticket","CSRFPreventionToken":"csrf"}}""")

        val first = assertIs<LoginResult.SecondFactorRequired>(client.login("root", "pam", "secret"))
        assertEquals("root@pam", first.username)
        assertEquals(listOf(SecondFactorKind.Totp, SecondFactorKind.Recovery), first.challenge.codeKinds)

        val second = client.completeSecondFactor(first.username, first.challenge, SecondFactorKind.Totp, " 123456 ")
        assertEquals(TicketCredentials("root@pam", ticket, "csrf"), second.credentials)

        server.takeRequest()
        assertEquals(
            mapOf("username" to "root@pam", "password" to "totp:123456", "tfa-challenge" to challengeTicket),
            form(server.takeRequest()),
        )
    }

    @Test
    fun renewalSendsTheOldTicketAsThePassword(): Unit = runBlocking {
        val newTicket = "PVE:root@pam:6712FFFF::bmV3"
        enqueue("""{"data":{"username":"root@pam","ticket":"$newTicket","CSRFPreventionToken":"csrf2"}}""")

        val renewed = client.renewTicket(TicketCredentials("root@pam", ticket, "csrf"))

        assertEquals(TicketCredentials("root@pam", newTicket, "csrf2"), renewed)
        assertEquals(mapOf("username" to "root@pam", "password" to ticket), form(server.takeRequest()))
    }

    @Test
    fun ticketCanArriveAsACookie(): Unit = runBlocking {
        server.enqueue(
            MockResponse()
                .addHeader("Set-Cookie", "PVEAuthCookie=PVE%3Aroot%40pam%3A6712F3A1%3A%3Ac2lnbmF0dXJl; Secure; HttpOnly")
                .setBody("""{"data":{"username":"root@pam","CSRFPreventionToken":"csrf"}}"""),
        )
        val result = assertIs<LoginResult.Success>(client.login("root", "pam", "secret"))
        assertEquals(ticket, result.credentials.ticket)
    }

    @Test
    fun ticketCredentialsAreSentAsACookie(): Unit = runBlocking {
        enqueue("""{"data":{"version":"9.2.1","release":"9.2","repoid":"0123abcd"}}""")
        val version = client.withCredentials(TicketCredentials("root@pam", ticket, "csrf")).version()

        assertEquals(9, version.major)
        val request = server.takeRequest()
        assertEquals("/api2/json/version", request.path)
        assertEquals("PVEAuthCookie=$ticket", request.getHeader("Cookie"))
        // Read requests carry no CSRF token.
        assertNull(request.getHeader("CSRFPreventionToken"))
    }

    @Test
    fun apiTokensAreSentInTheAuthorizationHeader(): Unit = runBlocking {
        enqueue("""{"data":[{"id":"qemu/100","type":"qemu","vmid":100,"status":"running"}]}""")
        val token = ApiTokenCredentials("root@pam!phone", "11111111-2222-3333-4444-555555555555")
        val resources = client.withCredentials(token).clusterResources()

        assertEquals(listOf(100), resources.map { it.vmid })
        val request = server.takeRequest()
        assertEquals("/api2/json/cluster/resources", request.path)
        assertEquals(
            "PVEAPIToken=root@pam!phone=11111111-2222-3333-4444-555555555555",
            request.getHeader("Authorization"),
        )
        assertNull(request.getHeader("Cookie"))
    }

    @Test
    fun serverErrorsCarryStatusAndParameterDetails(): Unit = runBlocking {
        enqueue(
            """{"data":null,"errors":{"vmid":"invalid format"},"message":"Parameter verification failed.\n"}""",
            code = 400,
            reason = "Parameter verification failed.",
        )
        val error = assertFailsWith<PveApiException> { client.version() }

        assertEquals(400, error.statusCode)
        assertEquals(mapOf("vmid" to "invalid format"), error.parameterErrors)
        assertTrue(error.message.orEmpty().contains("vmid: invalid format"))
    }

    @Test
    fun permissionDeniedIsReported(): Unit = runBlocking {
        enqueue("""{"data":null}""", code = 403, reason = "Permission check failed (/, Sys.Audit)")
        val error = assertFailsWith<PveApiException> { client.clusterResources() }

        assertTrue(error.isPermissionDenied)
        assertEquals("Permission check failed (/, Sys.Audit)", error.statusMessage)
    }

    @Test
    fun nonJsonAnswersAreReportedNotThrownRaw(): Unit = runBlocking {
        enqueue("<html><body>It works!</body></html>")
        val error = assertFailsWith<PveApiException> { client.version() }
        assertEquals(200, error.statusCode)
    }
}
