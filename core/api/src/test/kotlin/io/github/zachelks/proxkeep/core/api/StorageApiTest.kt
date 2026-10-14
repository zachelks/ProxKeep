package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.core.api.model.ChecksumAlgorithm
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StorageApiTest {

    private val server = MockWebServer().apply { start() }
    private val client = PveClient(server.url("/api2/json/"), OkHttpClient())
        .withCredentials(TicketCredentials("root@pam", "PVE:root@pam:X::Y", "csrf"))

    @AfterTest
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueue(body: String, code: Int = 200) {
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))
    }

    private fun form(request: RecordedRequest): Map<String, String> =
        request.body.readUtf8().split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }

    @Test
    fun aStoragesStatusIsRead(): Unit = runBlocking {
        enqueue("""{"data":{"type":"zfspool","content":"images,rootdir","total":1000,"used":250,"avail":750,"active":1,"enabled":1,"shared":0}}""")
        val status = client.storageStatus("pve1", "local-zfs")
        assertEquals("/api2/json/nodes/pve1/storage/local-zfs/status", server.takeRequest().path)
        assertEquals("local-zfs", status.storage)
        assertEquals(setOf("images", "rootdir"), status.contentTypes)
        assertEquals(750, status.avail)
    }

    @Test
    fun aVolumeIDIsOnePathSegment(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:imgdel::root@pam:"}""")
        client.deleteVolume("pve1", "local", "local:iso/debian-13.1.0-amd64-netinst.iso")
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/api2/json/nodes/pve1/storage/local/content/local:iso%2Fdebian-13.1.0-amd64-netinst.iso", request.path)
    }

    @Test
    fun aFileIsUploadedAsAMultipartForm(): Unit = runBlocking {
        enqueue("""{"data":"UPID:pve1:1:2:3:imgcopy::root@pam:"}""")
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val seen = CopyOnWriteArrayList<Long>()

        val upid = client.upload("pve1", "local", "iso", "tiny.iso", bytes.size.toLong(), { ByteArrayInputStream(bytes) }) { sent, total ->
            assertEquals(bytes.size.toLong(), total)
            seen += sent
        }

        assertEquals("UPID:pve1:1:2:3:imgcopy::root@pam:", upid)
        val request = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/storage/local/upload", request.path)
        assertEquals("csrf", request.getHeader("CSRFPreventionToken"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data"))
        val body = request.body.readByteArray()
        val text = String(body, Charsets.ISO_8859_1)
        assertTrue("name=\"content\"\r\n" in text && "\r\n\r\niso\r\n" in text)
        assertTrue("name=\"filename\"; filename=\"tiny.iso\"" in text)
        val start = text.indexOf("\r\n\r\n", text.indexOf("filename=\"tiny.iso\"")) + 4
        assertTrue(bytes.contentEquals(body.copyOfRange(start, start + bytes.size)), "the file's bytes are sent unchanged")
        assertEquals(bytes.size.toLong(), seen.last())
        assertTrue(seen.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun aDownloadIsCheckedFirstThenStarted(): Unit = runBlocking {
        enqueue("""{"data":{"filename":"debian-13.1.0-amd64-netinst.iso","size":"789577728","mimetype":"application/x-iso9660-image"}}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:download::root@pam:"}""")
        val metadata = client.queryUrl("pve1", "https://cdimage.debian.org/debian-13.1.0-amd64-netinst.iso")
        assertEquals(789577728, metadata.size)
        assertEquals("/api2/json/nodes/pve1/query-url-metadata?url=https%3A%2F%2Fcdimage.debian.org%2Fdebian-13.1.0-amd64-netinst.iso&verify-certificates=1", server.takeRequest().path)

        client.downloadUrl("pve1", "local", "iso", "https://cdimage.debian.org/x.iso", "x.iso", ChecksumAlgorithm.Sha256 to "abc123")
        val request = server.takeRequest()
        assertEquals("/api2/json/nodes/pve1/storage/local/download-url", request.path)
        assertEquals(
            mapOf("content" to "iso", "url" to "https://cdimage.debian.org/x.iso", "filename" to "x.iso", "verify-certificates" to "1", "checksum-algorithm" to "sha256", "checksum" to "abc123"),
            form(request),
        )
    }

    @Test
    fun catalogTemplatesAreListedAndDownloaded(): Unit = runBlocking {
        enqueue("""{"data":[{"template":"debian-13-standard_13.1-1_amd64.tar.zst","headline":"Debian 13 (standard)","section":"system","os":"debian-13","package":"debian-13-standard"}]}""")
        enqueue("""{"data":"UPID:pve1:1:2:3:download::root@pam:"}""")
        assertEquals("Debian 13 (standard)", client.applianceTemplates("pve1").single().headline)
        server.takeRequest()
        client.downloadTemplate("pve1", "local", "debian-13-standard_13.1-1_amd64.tar.zst")
        assertEquals(mapOf("storage" to "local", "template" to "debian-13-standard_13.1-1_amd64.tar.zst"), form(server.takeRequest()))
    }
}
