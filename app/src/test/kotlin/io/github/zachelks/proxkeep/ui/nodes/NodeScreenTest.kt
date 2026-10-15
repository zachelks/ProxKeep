package io.github.zachelks.proxkeep.ui.nodes

import android.content.Context
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.waitWithLooper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class NodeScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val posts = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: NodeViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/nodes/pve1/status") { request ->
            if (request.method == "POST") {
                posts += request.body.readUtf8()
                response("""{"data":null}""")
            } else {
                response("""{"data":{"cpu":0.1,"cpuinfo":{"cpus":8},"memory":{"used":1073741824,"total":8589934592},"uptime":3600,"pveversion":"pve-manager/9.0.3/abc"}}""")
            }
        }
        fixture.route("/nodes/pve1/rrddata", """{"data":[]}""")
        fixture.route("/nodes/pve1/services", """{"data":[{"service":"pveproxy","desc":"PVE API Proxy Server","state":"running","unit-state":"enabled"}]}""")
        fixture.route("/nodes/pve1/apt/update", """{"data":[{"Package":"pve-manager","OldVersion":"9.0.2","Version":"9.0.3"}]}""")
        val repository = fixture.signedInRepository(scope)
        viewModel = NodeViewModel(repository, RefreshSignal(), ConsoleManager(context, repository, scope), "pve1")
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    private fun show() {
        compose.setContent { NodeScreen("pve1", onClose = {}, onConsoleOpened = {}, onOpenRoute = {}, viewModel = viewModel) }
    }

    @Test
    fun theSummaryShowsTheNodeAndRebootsItAfterAsking() {
        show()
        compose.waitWithLooper { compose.onAllNodes(hasText("pve-manager/9.0.3/abc")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(string(R.string.action_reboot)))
        compose.onNodeWithText(string(R.string.action_reboot)).performClick()
        compose.onNode(hasText(string(R.string.action_reboot)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitWithLooper { posts.isNotEmpty() }
        assertEquals("command=reboot", posts.single())
    }

    @Test
    fun servicesAndUpdatesHaveTabsOfTheirOwn() {
        show()
        compose.onNodeWithText(string(R.string.node_tab_services)).performClick()
        compose.waitWithLooper { compose.onAllNodes(hasText("pveproxy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.service_running)).assertExists()

        compose.onNodeWithText(string(R.string.node_tab_updates)).performClick()
        val count = context.resources.getQuantityString(R.plurals.node_updates, 1, 1)
        compose.waitWithLooper { compose.onAllNodes(hasText(count)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(string(R.string.node_version_change, "9.0.2", "9.0.3")).assertExists()
    }
}
