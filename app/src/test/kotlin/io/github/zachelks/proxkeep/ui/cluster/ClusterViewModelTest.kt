package io.github.zachelks.proxkeep.ui.cluster

import android.content.Context
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.ClusterState
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

@RunWith(RobolectricTestRunner::class)
class ClusterViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val refresh = RefreshSignal()
    private lateinit var consoles: ConsoleManager
    private lateinit var viewModel: ClusterViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"node/pve1","type":"node","node":"pve1","status":"online"}]}""")
        fixture.route("/version", """{"data":{"version":"9.2.1"}}""")
        fixture.route("/access/permissions", """{"data":{"/nodes/pve1":{"Sys.Console":1}}}""")
        val repository = fixture.signedInRepository(scope)
        consoles = ConsoleManager(ApplicationProvider.getApplicationContext<Context>(), repository, scope)
        viewModel = ClusterViewModel(ClusterRepository(repository, refresh, scope), ServerAccessRepository(repository, scope), consoles, refresh)
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        consoles.close()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun theClusterAndAccessAreShared(): Unit = runBlocking {
        scope.launch { viewModel.cluster.collect {} }
        scope.launch { viewModel.access.collect {} }

        val cluster = withTimeout(TIMEOUT_MS) { viewModel.cluster.first { it is ClusterState.Loaded } }
        val access = withTimeout(TIMEOUT_MS) { viewModel.access.filterNotNull().first { it.permissions != null } }

        assertEquals(listOf("node/pve1"), assertIs<ClusterState.Loaded>(cluster).resources.map { it.id })
        assertEquals("9.2.1", access.version?.version)
    }

    @Test
    fun retryReadsTheClusterAgain(): Unit = runBlocking {
        scope.launch { viewModel.cluster.collect {} }
        withTimeout(TIMEOUT_MS) { viewModel.cluster.first { it is ClusterState.Loaded } }

        viewModel.retry()

        withTimeout(TIMEOUT_MS) { while (fixture.requestCount("/cluster/resources") < 2) delay(10) }
    }

    @Test
    fun aNodeShellOpensForTheNode() {
        viewModel.openNodeShell("pve1")

        assertEquals("pve1", consoles.current.value?.title)
        assertEquals(ConsoleTarget.NodeShell("pve1"), consoles.current.value?.target)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
