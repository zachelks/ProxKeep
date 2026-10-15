package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.auth.LoginResult
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.core.api.model.RrdTimeframe
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
import kotlin.test.assertFalse
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

    // --- Guests and tasks ------------------------------------------------

    private val upid = "UPID:pve1:0001E2A4:02F3B6C1:6712F3A1:qmstart:100:root@pam:"

    private fun segments(request: RecordedRequest): List<String> =
        request.requestUrl!!.pathSegments.drop(2)

    @Test
    fun guestActionPostsWithTheCsrfTokenAndReturnsTheTaskId(): Unit = runBlocking {
        enqueue("""{"data":"$upid"}""")
        val signedIn = client.withCredentials(TicketCredentials("root@pam", ticket, "csrf-token"))

        assertEquals(upid, signedIn.guestAction("pve1", ResourceKind.Qemu, 100, GuestAction.Start))

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals(listOf("nodes", "pve1", "qemu", "100", "status", "start"), segments(request))
        assertEquals("csrf-token", request.getHeader("CSRFPreventionToken"))
        assertEquals("PVEAuthCookie=$ticket", request.getHeader("Cookie"))
    }

    @Test
    fun containerActionsUseTheContainerPath(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:vzshutdown:101:root@pam:"}""")
        val token = ApiTokenCredentials("root@pam!phone", "secret")

        client.withCredentials(token).guestAction("pve1", ResourceKind.Lxc, 101, GuestAction.Shutdown)

        val request = server.takeRequest()
        assertEquals(listOf("nodes", "pve1", "lxc", "101", "status", "shutdown"), segments(request))
        // API tokens need no CSRF token.
        assertNull(request.getHeader("CSRFPreventionToken"))
    }

    @Test
    fun actionsAContainerDoesNotHaveAreRefusedBeforeAnyRequest() {
        assertFailsWith<IllegalArgumentException> {
            runBlocking { client.guestAction("pve1", ResourceKind.Lxc, 101, GuestAction.Reset) }
        }
        assertEquals(0, server.requestCount)
    }

    @Test
    fun guestStatusAndConfigAreRead(): Unit = runBlocking {
        enqueue("""{"data":{"status":"running","qmpstatus":"running","name":"web","cpus":4,"mem":2147483648}}""")
        enqueue("""{"data":{"cores":4,"memory":"4096","net0":"virtio=AA:BB,bridge=vmbr0","digest":"d"}}""")

        val status = client.guestStatus("pve1", ResourceKind.Qemu, 100)
        val config = client.guestConfig("pve1", ResourceKind.Qemu, 100)

        assertTrue(status.isRunning)
        assertFalse(status.isPaused)
        assertEquals(4096L, config.long("memory"))
        assertEquals(listOf("net0"), config.networks.map { it.key })
        assertEquals(listOf("nodes", "pve1", "qemu", "100", "status", "current"), segments(server.takeRequest()))
        assertEquals(listOf("nodes", "pve1", "qemu", "100", "config"), segments(server.takeRequest()))
    }

    @Test
    fun clusterTasksAreListed(): Unit = runBlocking {
        enqueue(
            """{"data":[
                {"upid":"$upid","node":"pve1","type":"qmstart","id":"100","user":"root@pam","starttime":1729000000},
                {"upid":"UPID:pve1:1:2:3:vzdump:101:root@pam:","node":"pve1","type":"vzdump","id":"101",
                 "user":"root@pam","starttime":1728990000,"endtime":1728990300,"status":"OK","saved":"1"}]}""",
        )
        val tasks = client.clusterTasks()

        assertEquals(listOf(true, false), tasks.map { it.isRunning })
        assertTrue(tasks.last().succeeded)
        assertEquals(listOf("cluster", "tasks"), segments(server.takeRequest()))
    }

    @Test
    fun taskLogIsReadFromAnOffset(): Unit = runBlocking {
        enqueue("""{"data":[{"n":3,"t":"starting"},{"n":"4","t":"TASK OK"}],"total":4}""")

        val lines = client.taskLog("pve1", upid, start = 2, limit = 100)

        assertEquals(listOf(3 to "starting", 4 to "TASK OK"), lines.map { it.n to it.t })
        val request = server.takeRequest()
        // The task ID travels as one path segment, punctuation and all.
        assertEquals(listOf("nodes", "pve1", "tasks", upid, "log"), segments(request))
        assertEquals("2", request.requestUrl!!.queryParameter("start"))
        assertEquals("100", request.requestUrl!!.queryParameter("limit"))
    }

    @Test
    fun awaitTaskPollsUntilTheTaskStops(): Unit = runBlocking {
        enqueue("""{"data":{"status":"running","type":"qmstart","id":"100"}}""")
        enqueue("""{"data":{"status":"running","type":"qmstart","id":"100"}}""")
        enqueue("""{"data":{"status":"stopped","exitstatus":"OK","type":"qmstart","id":"100"}}""")

        val result = client.awaitTask(Upid.parse(upid)!!, pollIntervalMs = 1)

        assertTrue(result.succeeded)
        assertEquals(3, server.requestCount)
        assertEquals(listOf("nodes", "pve1", "tasks", upid, "status"), segments(server.takeRequest()))
    }

    @Test
    fun awaitTaskReportsAFailedTask(): Unit = runBlocking {
        enqueue("""{"data":{"status":"stopped","exitstatus":"can't lock file - got timeout"}}""")

        val result = client.awaitTask(Upid.parse(upid)!!, pollIntervalMs = 1)

        assertFalse(result.succeeded)
        assertEquals("can't lock file - got timeout", result.exitstatus)
    }

    @Test
    fun awaitTaskDoesNotRetryAnErrorFromTheServer(): Unit = runBlocking {
        enqueue("""{"data":null}""", code = 403, reason = "Permission check failed")

        assertFailsWith<PveApiException> { client.awaitTask(Upid.parse(upid)!!, pollIntervalMs = 1) }
        assertEquals(1, server.requestCount)
    }

    @Test
    fun guestAgentReportsTheOperatingSystem(): Unit = runBlocking {
        enqueue(
            """{"data":{"result":{"id":"mswindows","name":"Microsoft Windows",
               "pretty-name":"Windows Server 2016 Standard","version":"Microsoft Windows Server 2016",
               "version-id":"2016","kernel-release":"14393","machine":"x86_64"}}}""",
        )
        enqueue("""{"data":{"result":{"name":"Ubuntu","version":"24.04.1 LTS (Noble Numbat)"}}}""")
        enqueue("""{"data":null}""", code = 500, reason = "QEMU guest agent is not running")

        val windows = client.guestOsInfo("pve1", 100)
        assertEquals("Windows Server 2016 Standard", windows.displayName)
        assertEquals("14393", windows.kernelRelease)
        assertEquals(listOf("nodes", "pve1", "qemu", "100", "agent", "get-osinfo"), segments(server.takeRequest()))

        // Without a pretty name the name and version are joined.
        assertEquals("Ubuntu 24.04.1 LTS (Noble Numbat)", client.guestOsInfo("pve1", 101).displayName)

        val error = assertFailsWith<PveApiException> { client.guestOsInfo("pve1", 102) }
        assertEquals("QEMU guest agent is not running", error.statusMessage)
    }

    @Test
    fun permissionsAreReadForTheWholeTreeOrOnePath(): Unit = runBlocking {
        enqueue("""{"data":{"/":{"Sys.Audit":1},"/vms/100":{"VM.PowerMgmt":0}}}""")
        enqueue("""{"data":{"/vms/100":{"VM.Console":1}}}""")

        val all = client.permissions()
        assertTrue(all.has("/vms/100", "VM.PowerMgmt"))
        assertFalse(all.has("/vms/100", "Sys.Audit"))
        assertEquals("/api2/json/access/permissions", server.takeRequest().path)

        assertTrue(client.permissions("/vms/100").has("/vms/100", "VM.Console"))
        assertEquals("/api2/json/access/permissions?path=%2Fvms%2F100", server.takeRequest().path)
    }

    @Test
    fun usageHistoryIsReadOldestFirstAndKeepsWhatWasRecorded(): Unit = runBlocking {
        enqueue(
            """{"data":[
                {"time":1760400060,"cpu":"0.25","maxcpu":2,"mem":1073741824,"netin":512.5},
                {"time":1760400000,"cpu":0.1,"maxcpu":2},
                {"time":1760400120,"maxcpu":2},
                {"cpu":0.5}]}""",
        )
        enqueue("""{"data":[{"time":1760400000,"loadavg":"0.42","memused":2147483648}]}""")

        val guest = client.guestRrd("pve1", ResourceKind.Lxc, 200, RrdTimeframe.Day)
        assertEquals(listOf(1760400000L, 1760400060L, 1760400120L), guest.map { it.time })
        assertEquals(0.25, guest[1]["cpu"])
        assertEquals(512.5, guest[1]["netin"])
        assertNull(guest[2]["cpu"], "nothing recorded while the guest was off")
        assertEquals("/api2/json/nodes/pve1/lxc/200/rrddata?timeframe=day&cf=AVERAGE", server.takeRequest().path)

        val node = client.nodeRrd("pve1", RrdTimeframe.Hour)
        assertEquals(0.42, node.single()["loadavg"])
        assertEquals("/api2/json/nodes/pve1/rrddata?timeframe=hour&cf=AVERAGE", server.takeRequest().path)
    }
}
