package io.github.zachelks.proxkeep.ui.config

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GuestConfigViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()
    private val writes = CopyOnWriteArrayList<Map<String, String>>()
    private lateinit var viewModel: GuestConfigViewModel

    @Volatile
    private var config = """{"cores":2,"memory":"4096","name":"web","onboot":1,"sshkeys":"ssh-ed25519%20AAAA%20me%40laptop","net0":"virtio=BC:24:11:2A:3B:4C,bridge=vmbr0","scsi0":"local-lvm:vm-100-disk-0,size=32G","ide2":"local-lvm:vm-100-cloudinit,media=cdrom","digest":"d1"}"""

    private fun start() = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"running"}]}""")
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", SCHEMA)
        fixture.route("/nodes/pve1/qemu/100/pending", """{"data":[{"key":"cores","value":2,"pending":4}]}""")
        fixture.route("/nodes/pve1/qemu/100/config") { request ->
            if (request.method == "POST") {
                writes += form(request.body.readUtf8())
                response("""{"data":"UPID:pve1:1:2:3:qmconfig:100:root@pam:"}""")
            } else {
                response("""{"data":$config}""")
            }
        }
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:qmconfig:100:root@pam:/status", """{"data":{"status":"stopped","exitstatus":"OK"}}""")
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        viewModel = GuestConfigViewModel(repository, ClusterRepository(repository, refresh, scope), SchemaRepository(repository, cacheDir), refresh, "qemu/100")
        scope.launch { viewModel.guest.collect {} }
        withTimeout(TIMEOUT_MS) { viewModel.options.filterNotNull().first() }
    }

    @AfterTest
    fun tearDown() {
        if (::viewModel.isInitialized) viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
        cacheDir.deleteRecursively()
    }

    private fun form(body: String): Map<String, String> = body.split('&').filter { it.isNotEmpty() }.associate { pair ->
        URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
    }

    @Test
    fun loadsTheConfigurationPendingChangesAndOptions() {
        start()
        val settings = assertIs<Loadable.Loaded<GuestSettings>>(viewModel.settings.value).value
        assertEquals(setOf("cores"), settings.pending.keys)
        assertTrue(settings.hasCloudInit)
        assertEquals(2, settings.hardware.cores)

        val form = viewModel.options.value!!
        // Hardware keys are edited by hand, so the options leave them out; cloud-init gets an IP field per network device.
        assertEquals(listOf("name", "onboot", "sshkeys", "ipconfig0"), form.fields.map { it.key })
        assertEquals("ssh-ed25519 AAAA me@laptop", form.value("sshkeys"))
    }

    @Test
    fun savingOptionsSendsOnlyTheChangesWithTheDigest(): Unit = runBlocking {
        start()
        viewModel.editOption("name", "web-1")
        viewModel.editOption("onboot", "")
        viewModel.editOption("sshkeys", "ssh-ed25519 BBBB you@desk")
        val message = async { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
        viewModel.saveOptions()

        assertEquals(R.string.config_saved_pending, message.await().id)
        assertEquals(
            mapOf("name" to "web-1", "sshkeys" to "ssh-ed25519%20BBBB%20you%40desk", "delete" to "onboot", "digest" to "d1"),
            writes.single(),
        )
        assertTrue(viewModel.options.value!!.changes.isEmpty)
    }

    @Test
    fun editsSurviveAReload(): Unit = runBlocking {
        start()
        viewModel.editOption("name", "web-1")
        config = config.replace("\"digest\":\"d1\"", "\"digest\":\"d2\"")
        viewModel.reload()
        withTimeout(TIMEOUT_MS) {
            while ((viewModel.settings.value as? Loadable.Loaded)?.value?.config?.digest != "d2") kotlinx.coroutines.delay(20)
        }
        assertEquals("web-1", viewModel.options.value!!.value("name"))
        assertTrue(viewModel.options.value!!.isChanged("name"))
    }

    @Test
    fun aRefusedValueIsShownOnItsField(): Unit = runBlocking {
        start()
        fixture.route("/nodes/pve1/qemu/100/config") { request ->
            if (request.method == "POST") {
                response("""{"data":null,"message":"Parameter verification failed.","errors":{"name":"invalid format - value does not look like a valid DNS name"}}""", code = 400)
            } else {
                response("""{"data":$config}""")
            }
        }
        viewModel.editOption("name", "web_1")
        val message = async { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
        viewModel.saveOptions()

        assertEquals(R.string.error_server_response, message.await().id)
        val form = viewModel.options.value!!
        assertTrue("valid DNS name" in form.serverErrors.getValue("name"))
        assertEquals("web_1", form.value("name"))
        // The configuration is read again after a refusal, then the screen is free for another try.
        withTimeout(TIMEOUT_MS) { viewModel.saving.first { !it } }
        assertEquals("web_1", viewModel.options.value!!.value("name"))
    }

    @Test
    fun hardwareChangesFollowTheTask(): Unit = runBlocking {
        start()
        val message = async { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
        viewModel.applyHardware(mapOf("scsi1" to "local-lvm:16"))
        assertEquals(R.string.config_saved_pending, message.await().id)
        assertEquals(mapOf("scsi1" to "local-lvm:16", "digest" to "d1"), writes.single())
        assertTrue(fixture.requestedPaths.contains("/nodes/pve1/tasks/UPID:pve1:1:2:3:qmconfig:100:root@pam:/status"))
    }

    @Test
    fun aServerWithoutASchemaStillHasItsHardware() {
        fixture.route("/cluster/resources", """{"data":[{"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"running"}]}""")
        runBlocking {
            fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
            fixture.route("/pve-docs/api-viewer/apidoc.js", "Not found", code = 404)
            fixture.route("/nodes/pve1/qemu/100/pending", """{"data":[]}""")
            fixture.route("/nodes/pve1/qemu/100/config", """{"data":$config}""")
            val repository = fixture.signedInRepository(scope)
            val refresh = RefreshSignal()
            viewModel = GuestConfigViewModel(repository, ClusterRepository(repository, refresh, scope), SchemaRepository(repository, cacheDir), refresh, "qemu/100")
            scope.launch { viewModel.guest.collect {} }
            val schema = withTimeout(TIMEOUT_MS) { viewModel.schema.first { it !is Loadable.Loading } }
            assertEquals(R.string.error_schema_unavailable, assertIs<Loadable.Failed>(schema).message.id)
            withTimeout(TIMEOUT_MS) { viewModel.settings.first { it is Loadable.Loaded } }
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        val SCHEMA = """const apiSchema = [{"path":"/nodes/{node}/qemu/{vmid}/config","leaf":1,"info":{"POST":{"method":"POST","parameters":{"properties":{
            "node":{"type":"string"},"vmid":{"type":"integer"},"digest":{"type":"string","optional":1},"delete":{"type":"string","optional":1},
            "cores":{"type":"integer","optional":1,"minimum":1},"memory":{"type":"string","optional":1},"name":{"type":"string","optional":1},
            "onboot":{"type":"boolean","optional":1,"default":0},"sshkeys":{"type":"string","optional":1},"ipconfig[n]":{"type":"string","optional":1},
            "net[n]":{"type":"string","optional":1},"scsi[n]":{"type":"string","optional":1},"ide[n]":{"type":"string","optional":1}}}}}}];"""
    }
}
