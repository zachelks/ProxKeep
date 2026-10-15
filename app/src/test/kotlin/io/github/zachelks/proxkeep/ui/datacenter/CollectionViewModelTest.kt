package io.github.zachelks.proxkeep.ui.datacenter

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.schema.SchemaEditRequest
import io.github.zachelks.proxkeep.ui.schema.SchemaEditViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CollectionViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()
    private val writes = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
        cacheDir.deleteRecursively()
    }

    @Test
    fun itemsAreListedAndDeleted(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", "", code = 404)
        fixture.route("/cluster/firewall/ipset/office", """{"data":[{"cidr":"10.0.0.0/8","comment":"lan"},{"cidr":"192.0.2.0/24"}]}""")
        fixture.route("/cluster/firewall/ipset/office/10.0.0.0%2F8") { request ->
            writes += "${request.method} ${request.requestUrl?.encodedPath}"
            response("""{"data":null}""")
        }
        val repository = fixture.signedInRepository(scope)
        val spec = Collections.get("fw-ipset-entries")!!
        val viewModel = CollectionViewModel(repository, SchemaRepository(repository, cacheDir), RefreshSignal(), spec, mapOf("name" to "office"))
        try {
            scope.launch { viewModel.items.collect {} }
            val items = withTimeout(TIMEOUT_MS) { viewModel.items.first { it is Loadable.Loaded } }
            assertEquals(listOf("10.0.0.0/8", "192.0.2.0/24"), assertIs<Loadable.Loaded<List<CollectionItem>>>(items).value.map { it.id })

            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.delete(items.value.first())
            assertEquals(R.string.dc_deleted, message.await().id)
            assertEquals(listOf("DELETE /api2/json/cluster/firewall/ipset/office/10.0.0.0%2F8"), writes)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun aRuleIsDeletedOnlyIfTheListHasNotChanged(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        // Without the schema, a rule's position still carries the list's digest.
        fixture.route("/pve-docs/api-viewer/apidoc.js", "", code = 404)
        fixture.route(
            "/cluster/firewall/rules",
            """{"data":[{"pos":0,"type":"in","action":"ACCEPT","digest":"d1"},{"pos":1,"type":"in","action":"DROP","digest":"d1"}]}""",
        )
        fixture.route("/cluster/firewall/rules/1") { request ->
            writes += "${request.method} ${request.requestUrl?.encodedPath}?${request.requestUrl?.encodedQuery}"
            response("""{"data":null}""")
        }
        val repository = fixture.signedInRepository(scope)
        val viewModel = CollectionViewModel(repository, SchemaRepository(repository, cacheDir), RefreshSignal(), Collections.get("fw-rules")!!, emptyMap())
        try {
            scope.launch { viewModel.items.collect {} }
            val items = assertIs<Loadable.Loaded<List<CollectionItem>>>(withTimeout(TIMEOUT_MS) { viewModel.items.first { it is Loadable.Loaded } })

            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.delete(items.value.single { it.id == "1" })
            assertEquals(R.string.dc_deleted, message.await().id)
            assertEquals(listOf("DELETE /api2/json/cluster/firewall/rules/1?digest=d1"), writes)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun theSchemaDecidesWhetherADeleteCarriesTheDigest(): Unit = runBlocking {
        assertEquals(listOf("DELETE /api2/json/cluster/firewall/aliases/lan?digest=d1"), deleteAlias(takesDigest = true))
    }

    @Test
    fun aDeleteThatTakesNoDigestIsSentWithout(): Unit = runBlocking {
        assertEquals(listOf("DELETE /api2/json/cluster/firewall/aliases/lan?null"), deleteAlias(takesDigest = false))
    }

    /** Deletes an alias whose delete, by the schema, does or does not take the list's digest. */
    private suspend fun deleteAlias(takesDigest: Boolean): List<String> = coroutineScope {
        val digest = if (takesDigest) ""","digest":{"type":"string","optional":1}""" else ""
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route(
            "/pve-docs/api-viewer/apidoc.js",
            """const apiSchema = [{"path":"/cluster/firewall/aliases","info":{"GET":{"method":"GET"}}},
               {"path":"/cluster/firewall/aliases/{name}","info":{"DELETE":{"method":"DELETE","parameters":{"properties":{"name":{"type":"string"}$digest}}}}}];""",
        )
        fixture.route("/cluster/firewall/aliases", """{"data":[{"name":"lan","cidr":"10.0.0.0/8","digest":"d1"}]}""")
        fixture.route("/cluster/firewall/aliases/lan") { request ->
            writes += "${request.method} ${request.requestUrl?.encodedPath}?${request.requestUrl?.encodedQuery}"
            response("""{"data":null}""")
        }
        val repository = fixture.signedInRepository(scope)
        val viewModel = CollectionViewModel(repository, SchemaRepository(repository, cacheDir), RefreshSignal(), Collections.get("fw-aliases")!!, emptyMap())
        try {
            scope.launch { viewModel.items.collect {} }
            val items = assertIs<Loadable.Loaded<List<CollectionItem>>>(withTimeout(TIMEOUT_MS) { viewModel.items.first { it is Loadable.Loaded } })
            // The schema has loaded once it has taken away creating, which this one lacks.
            withTimeout(TIMEOUT_MS) { viewModel.abilities.first { !it.create } }

            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.delete(items.value.single())
            assertEquals(R.string.dc_deleted, message.await().id)
            writes.toList()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun aNewTokensSecretIsShownBeforeTheFormCloses(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route(
            "/pve-docs/api-viewer/apidoc.js",
            """const apiSchema = [{"path":"/access/users/{userid}/token/{tokenid}","info":{"POST":{"method":"POST","parameters":{"properties":{
                "userid":{"type":"string"},"tokenid":{"type":"string"},"comment":{"type":"string","optional":1},"privsep":{"type":"boolean","optional":1,"default":1}}}}}}];""",
        )
        fixture.route("/access/users/ops@pve/token/ci", """{"data":{"full-tokenid":"ops@pve!ci","value":"5f1b-secret"}}""")
        val repository = fixture.signedInRepository(scope)
        val viewModel = SchemaEditViewModel(
            repository, SchemaRepository(repository, cacheDir), RefreshSignal(),
            SchemaEditRequest("/access/users/ops@pve/token/ci", "/access/users/{userid}/token/{tokenid}", "POST"),
        )
        try {
            withTimeout(TIMEOUT_MS) { viewModel.form.first { it is Loadable.Loaded } }
            viewModel.edit("comment", "CI runner")
            viewModel.save()
            assertEquals("5f1b-secret", withTimeout(TIMEOUT_MS) { viewModel.secret.filterNotNull().first() })
            assertFalse(viewModel.saved.value, "the form stays open while the secret is shown")
            viewModel.secretShown()
            assertTrue(viewModel.saved.value)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
