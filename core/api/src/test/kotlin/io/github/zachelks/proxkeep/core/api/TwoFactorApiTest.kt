package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.access.Totp
import io.github.zachelks.proxkeep.core.api.access.addRecoveryKeys
import io.github.zachelks.proxkeep.core.api.access.addTotp
import io.github.zachelks.proxkeep.core.api.access.deleteTfa
import io.github.zachelks.proxkeep.core.api.access.setTfaEnabled
import io.github.zachelks.proxkeep.core.api.access.tfaUsers
import io.github.zachelks.proxkeep.core.api.access.unlockTfa
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TwoFactorApiTest {

    private lateinit var server: MockWebServer
    private lateinit var client: PveClient

    @BeforeTest
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = PveClient(server.url("/api2/json/"), OkHttpClient())
            .withCredentials(TicketCredentials("ops@pve", "PVE:ops@pve:6712F3A1::c2ln", "csrf"))
    }

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String = """{"data":null}""") {
        server.enqueue(MockResponse().setBody(body))
    }

    private fun RecordedRequest.form(): Map<String, String> = body.readUtf8().split("&").filter { it.isNotEmpty() }.associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    @Test
    fun readsEveryUsersSecondFactors(): Unit = runBlocking {
        enqueue(
            """{"data":[
               {"userid":"ops@pve","entries":[{"id":"totp-1","type":"totp","description":"Phone","created":"1700000000","enable":1},
                                               {"id":"recovery","type":"recovery","enable":0}],"tfa-locked-until":2000,"totp-locked":0},
               {"userid":"root@pam","entries":[]}]}""",
        )
        val users = client.tfaUsers()
        assertEquals("/api2/json/access/tfa", server.takeRequest().requestUrl!!.encodedPath)
        val ops = users.first()
        assertEquals(listOf("totp", "recovery"), ops.entries.map { it.type })
        assertEquals(1_700_000_000L, ops.entries.first().created)
        assertFalse(ops.entries.last().enable)
        assertTrue(ops.isLocked(nowSeconds = 1000))
        assertFalse(ops.isLocked(nowSeconds = 3000))
        assertFalse(users.last().isLocked(nowSeconds = 0))
    }

    @Test
    fun anAuthenticatorIsAddedWithACodeFromIt(): Unit = runBlocking {
        enqueue("""{"data":{"id":"totp-2"}}""")
        val uri = Totp("JBSWY3DPEHPK3PXP").uri("Home lab", "ops@pve")
        client.addTotp("ops@pve", "Phone", uri, "123456", password = "hunter2")

        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api2/json/access/tfa/ops@pve", request.requestUrl!!.encodedPath)
        assertEquals(
            mapOf("type" to "totp", "totp" to uri, "value" to "123456", "description" to "Phone", "password" to "hunter2"),
            request.form(),
        )
    }

    @Test
    fun recoveryKeysAreReturnedOnce(): Unit = runBlocking {
        enqueue("""{"data":{"id":"recovery","recovery":["aaaa-bbbb","cccc-dddd"]}}""")
        assertEquals(listOf("aaaa-bbbb", "cccc-dddd"), client.addRecoveryKeys("ops@pve", "", password = null))
        assertEquals(mapOf("type" to "recovery"), server.takeRequest().form(), "root@pam needs no password")
    }

    @Test
    fun entriesAreDisabledDeletedAndUnlocked(): Unit = runBlocking {
        repeat(3) { enqueue() }
        client.setTfaEnabled("ops@pve", "totp-1", enable = false, password = "pw")
        client.deleteTfa("ops@pve", "totp-1", password = "pw")
        client.unlockTfa("ops@pve")

        val disable = server.takeRequest()
        assertEquals("PUT", disable.method)
        assertEquals("/api2/json/access/tfa/ops@pve/totp-1", disable.requestUrl!!.encodedPath)
        assertEquals(mapOf("enable" to "0", "password" to "pw"), disable.form())
        val delete = server.takeRequest()
        assertEquals("DELETE", delete.method)
        assertEquals("pw", delete.requestUrl!!.queryParameter("password"))
        val unlock = server.takeRequest()
        assertEquals("PUT", unlock.method)
        assertEquals("/api2/json/access/users/ops@pve/unlock-tfa", unlock.requestUrl!!.encodedPath)
    }

    @Test
    fun aSecretIsBase32AndItsUriNamesTheServer() {
        // RFC 4648's test vectors.
        assertEquals("MZXW6YTBOI", Totp.base32("foobar".toByteArray()))
        assertEquals("MZXW6", Totp.base32("foo".toByteArray()))
        val totp = Totp.generate()
        assertEquals(32, totp.secret.length, "160 bits")
        assertEquals("JBSW Y3DP", Totp("JBSWY3DP").grouped)
        assertEquals(
            "otpauth://totp/Home%20lab%3Aops%40pve?secret=JBSWY3DP&issuer=Home%20lab&algorithm=SHA1&digits=6&period=30",
            Totp("JBSWY3DP").uri("Home lab", "ops@pve"),
        )
    }
}
