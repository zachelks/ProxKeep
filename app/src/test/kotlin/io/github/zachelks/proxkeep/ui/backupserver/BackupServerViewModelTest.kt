package io.github.zachelks.proxkeep.ui.backupserver

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.Product
import io.github.zachelks.proxkeep.core.api.pbs.BackupGroup
import io.github.zachelks.proxkeep.core.api.pbs.BackupSnapshot
import io.github.zachelks.proxkeep.core.api.pbs.PruneOptions
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.datacenter.Collections
import io.github.zachelks.proxkeep.ui.datacenter.DatacenterEntry
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import io.github.zachelks.proxkeep.ui.tasks.unescapeTaskId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BackupServerViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer(product = Product.Pbs)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val writes = CopyOnWriteArrayList<String>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    private fun formOf(body: String): Map<String, String> =
        body.split('&').filter { it.isNotEmpty() }.associate { pair ->
            URLDecoder.decode(pair.substringBefore('='), "UTF-8") to URLDecoder.decode(pair.substringAfter('=', ""), "UTF-8")
        }

    @Test
    fun aPruneIsPreviewedBeforeItIsApplied(): Unit = runBlocking {
        fixture.route(
            "/admin/datastore/store1/snapshots",
            """{"data":[{"backup-type":"vm","backup-id":"100","backup-time":200},{"backup-type":"vm","backup-id":"100","backup-time":100}]}""",
        )
        fixture.route("/admin/datastore/store1/prune") { request ->
            val form = formOf(request.body.readUtf8())
            writes += form.toSortedMap().toString()
            response(
                if (form["dry-run"] == "1") {
                    """{"data":[{"backup-type":"vm","backup-id":"100","backup-time":200,"keep":true},{"backup-type":"vm","backup-id":"100","backup-time":100,"keep":false}]}"""
                } else {
                    """{"data":[]}"""
                },
            )
        }
        val repository = fixture.signedInRepository(scope)
        val viewModel = BackupGroupViewModel(repository, RefreshSignal(), "store1", "", BackupGroup("vm", "100"))
        try {
            scope.launch { viewModel.snapshots.collect {} }
            val snapshots = withTimeout(TIMEOUT_MS) { viewModel.snapshots.first { it is Loadable.Loaded } }
            assertEquals(listOf(200L, 100L), assertIs<Loadable.Loaded<List<BackupSnapshot>>>(snapshots).value.map { it.time })

            viewModel.previewPrune(PruneOptions(last = 1))
            val preview = withTimeout(TIMEOUT_MS) { viewModel.preview.filterNotNull().first() }
            assertEquals(1, preview.removed)
            assertEquals(listOf("{backup-id=100, backup-type=vm, dry-run=1, keep-last=1}"), writes)

            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.confirmPrune()
            assertEquals(R.string.pbs_pruned, message.await().id)
            assertNull(viewModel.preview.value)
            assertEquals("{backup-id=100, backup-type=vm, keep-last=1}", writes.last())
        } finally {
            // Waits for the view model's work to end, so none of it runs into the next test's main dispatcher.
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun groupsFollowTheChosenNamespace(): Unit = runBlocking {
        fixture.route("/status/datastore-usage", """{"data":[{"store":"store1","total":100,"used":10}]}""")
        fixture.route("/admin/datastore/store1/namespace", """{"data":[{"ns":""},{"ns":"prod"}]}""")
        fixture.route("/admin/datastore/store1/groups") { request ->
            val ns = request.requestUrl?.queryParameter("ns")
            response(if (ns == "prod") """{"data":[{"backup-type":"ct","backup-id":"200"}]}""" else """{"data":[{"backup-type":"vm","backup-id":"100"}]}""")
        }
        fixture.route("/admin/datastore/store1/gc") { request ->
            writes += request.method!!
            response("""{"data":"UPID:pbs:1:2:3:4:garbage_collection:store1:root@pam:"}""")
        }
        val repository = fixture.signedInRepository(scope)
        val viewModel = DatastoreViewModel(repository, RefreshSignal(), "store1")
        try {
            scope.launch { viewModel.groups.collect {} }
            scope.launch { viewModel.namespaces.collect {} }
            val root = withTimeout(TIMEOUT_MS) { viewModel.groups.first { it is Loadable.Loaded } }
            assertEquals(listOf("vm/100"), assertIs<Loadable.Loaded<List<BackupGroup>>>(root).value.map { it.key })
            withTimeout(TIMEOUT_MS) { viewModel.namespaces.first { it.size == 2 } }

            viewModel.selectNamespace("prod")
            val prod = withTimeout(TIMEOUT_MS) {
                viewModel.groups.first { state -> (state as? Loadable.Loaded)?.value?.map { it.key } == listOf("ct/200") }
            }
            assertNotNull(prod)

            // Garbage collection is reported once started; the person follows it in Tasks.
            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.collectGarbage()
            assertEquals(R.string.pbs_gc_started, message.await().id)
            assertEquals(listOf("POST"), writes)
        } finally {
            // Waits for the view model's work to end, so none of it runs into the next test's main dispatcher.
            viewModel.viewModelScope.coroutineContext.job.cancelAndJoin()
        }
    }

    @Test
    fun keepCountsAreRead() {
        assertNull(pruneOptionsOf(emptyMap()), "keeping everything is no prune")
        assertNull(pruneOptionsOf(mapOf(KeepPeriod.Daily to "0")))
        assertNull(pruneOptionsOf(mapOf(KeepPeriod.Daily to "7", KeepPeriod.Weekly to "x")))
        assertEquals(PruneOptions(daily = 7, yearly = 2), pruneOptionsOf(mapOf(KeepPeriod.Daily to "7", KeepPeriod.Weekly to "", KeepPeriod.Yearly to "2")))
    }

    @Test
    fun everyAdministrationEntryHasItsCollection() {
        val collections = BACKUP_SERVER_GROUPS.flatMap { it.entries }.filterIsInstance<DatacenterEntry.Collection>()
        assertTrue(collections.isNotEmpty())
        collections.forEach { entry ->
            val spec = assertNotNull(Collections.get(entry.specId), entry.specId)
            // Node-scoped paths are filled with the Backup Server's own name.
            assertTrue('{' !in spec.list(entry.args(null)), spec.id)
        }
    }

    @Test
    fun escapedTaskIdsAreReadable() {
        assertEquals("store1:vm/100", unescapeTaskId("store1:vm\\x2f100"))
        assertEquals("100", unescapeTaskId("100"))
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
