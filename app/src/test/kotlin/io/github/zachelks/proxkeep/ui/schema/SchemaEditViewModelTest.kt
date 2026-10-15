package io.github.zachelks.proxkeep.ui.schema

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import java.net.URLDecoder
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SchemaEditViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()
    private val writes = CopyOnWriteArrayList<Pair<String, Map<String, String>>>()
    private lateinit var viewModel: SchemaEditViewModel

    private fun start(request: SchemaEditRequest) = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", SCHEMA)
        fixture.route("/nodes/pve1/network/vmbr0") { request ->
            if (request.method == "GET") {
                response("""{"data":{"iface":"vmbr0","type":"bridge","autostart":1,"cidr":"192.0.2.10/24","bridge_ports":"eno1","comments":"uplink","families":["inet"],"digest":"d9"}}""")
            } else {
                writes += request.method!! to form(request.body.readUtf8())
                response("""{"data":null}""")
            }
        }
        fixture.route("/nodes/pve1/network") { request ->
            writes += request.method!! to form(request.body.readUtf8())
            response("""{"data":null}""")
        }
        val repository = fixture.signedInRepository(scope)
        viewModel = SchemaEditViewModel(repository, SchemaRepository(repository, cacheDir), RefreshSignal(), request)
        withTimeout(TIMEOUT_MS) { viewModel.form.first { it !is Loadable.Loading } }
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

    private val edit = SchemaEditRequest("/nodes/pve1/network/vmbr0", "/nodes/{node}/network/{iface}", "PUT")

    @Test
    fun anObjectIsReadIntoItsForm() {
        start(edit)
        val form = assertIs<Loadable.Loaded<SchemaForm>>(viewModel.form.value).value
        // Path parameters and the digest are not settings.
        assertEquals(listOf("type", "autostart", "cidr", "bridge_ports", "comments"), form.fields.map { it.key })
        assertEquals("1", form.value("autostart"))
        assertEquals("uplink", form.value("comments"))
    }

    @Test
    fun savingSendsChangesRemovalsAndTheDigest(): Unit = runBlocking {
        start(edit)
        viewModel.edit("comments", "")
        viewModel.edit("cidr", "192.0.2.11/24")
        viewModel.save()
        // Saved is state, so waiting for it after the save cannot miss it.
        withTimeout(TIMEOUT_MS) { viewModel.saved.first { it } }
        assertEquals("PUT" to mapOf("cidr" to "192.0.2.11/24", "delete" to "comments", "digest" to "d9"), writes.single())
    }

    @Test
    fun aNewObjectStartsFromItsPresets(): Unit = runBlocking {
        start(SchemaEditRequest("/nodes/pve1/network", "/nodes/{node}/network", "POST", presets = mapOf("type" to "bridge")))
        val form = assertIs<Loadable.Loaded<SchemaForm>>(viewModel.form.value).value
        assertEquals(listOf("iface", "type", "autostart", "cidr", "bridge_ports", "comments"), form.fields.map { it.key })
        assertEquals("bridge", form.value("type"))
        viewModel.edit("iface", "vmbr9")
        viewModel.save()
        // Saved is state, so waiting for it after the save cannot miss it.
        withTimeout(TIMEOUT_MS) { viewModel.saved.first { it } }
        assertEquals("POST" to mapOf("type" to "bridge", "iface" to "vmbr9"), writes.single())
    }

    @Test
    fun valuesAreFlattenedForTheForm() {
        val json = Json.parseToJsonElement("""{"a":true,"b":false,"c":"text","d":3,"e":["x","y"],"f":{"nested":1},"g":null,"h":"true"}""") as JsonObject
        assertEquals(mapOf("a" to "1", "b" to "0", "c" to "text", "d" to "3", "e" to "x,y", "h" to "true"), SchemaEditViewModel.flatten(json))
        assertEquals(setOf("node", "iface"), SchemaEditViewModel.pathParameters("/nodes/{node}/network/{iface}"))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L

        private const val FIELDS = """"type":{"type":"string","enum":["bridge","bond","vlan"]},"autostart":{"type":"boolean","optional":1},
            "cidr":{"type":"string","optional":1},"bridge_ports":{"type":"string","optional":1},"comments":{"type":"string","optional":1}"""

        val SCHEMA = """const apiSchema = [
            {"path":"/nodes/{node}/network","info":{"POST":{"method":"POST","parameters":{"properties":{"node":{"type":"string"},"iface":{"type":"string"},$FIELDS}}}}},
            {"path":"/nodes/{node}/network/{iface}","info":{"PUT":{"method":"PUT","parameters":{"properties":{"node":{"type":"string"},"iface":{"type":"string"},
                "digest":{"type":"string","optional":1},"delete":{"type":"string","optional":1},$FIELDS}}}}}];"""
    }
}
