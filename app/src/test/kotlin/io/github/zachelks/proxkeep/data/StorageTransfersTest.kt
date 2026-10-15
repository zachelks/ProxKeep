package io.github.zachelks.proxkeep.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StorageTransfersTest {

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun anUploadIsFollowedToItsEnd(): Unit = runBlocking {
        var uploaded = 0
        fixture.route("/nodes/pve1/storage/local/upload") { request ->
            uploaded = request.bodySize.toInt()
            response("""{"data":"UPID:pve1:1:2:3:imgcopy::root@pam:"}""")
        }
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:imgcopy::root@pam:/status", """{"data":{"status":"stopped","exitstatus":"OK"}}""")
        val transfers = StorageTransfers(fixture.signedInRepository(scope), RefreshSignal(), scope)
        val event = async { withTimeout(TIMEOUT_MS) { transfers.events.first() } }
        val bytes = ByteArray(50_000) { 7 }

        transfers.upload(fixture.profile.id, "pve1", "local", "iso", "tiny.iso", bytes.size.toLong()) { ByteArrayInputStream(bytes) }
        assertEquals(listOf("tiny.iso"), transfers.transfers.value.map { it.fileName })

        val outcome = event.await()
        assertTrue(outcome.succeeded)
        assertEquals("tiny.iso", outcome.fileName)
        assertTrue(uploaded > bytes.size, "the form carries the file")
        withTimeout(TIMEOUT_MS) { transfers.transfers.first { it.isEmpty() } }
    }

    @Test
    fun aFailedDownloadSaysWhy(): Unit = runBlocking {
        fixture.route("/nodes/pve1/storage/local/download-url", """{"data":null,"message":"invalid URL"}""", code = 400)
        val transfers = StorageTransfers(fixture.signedInRepository(scope), RefreshSignal(), scope)
        val event = async { withTimeout(TIMEOUT_MS) { transfers.events.first() } }

        transfers.download(fixture.profile.id, "pve1", "local", "iso", "ftp://x", "x.iso", null, true, null)
        val outcome = event.await()
        assertFalse(outcome.succeeded)
        assertEquals("invalid URL", outcome.detail)
    }

    @Test
    fun progressIsAFractionUntilTheNodeStoresTheFile() {
        val transfer = Transfer(1, "p", "pve1", "local", "x.iso", sent = 25, total = 100)
        assertEquals(0.25f, transfer.fraction)
        assertEquals(null, transfer.copy(storing = true).fraction)
        assertEquals(null, transfer.copy(total = null).fraction)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
