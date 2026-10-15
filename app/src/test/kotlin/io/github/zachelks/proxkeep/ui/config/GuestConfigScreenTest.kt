package io.github.zachelks.proxkeep.ui.config

import android.content.Context
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.waitWithLooper
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.response
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GuestConfigScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()
    private val writes = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: GuestConfigViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"lxc/200","type":"lxc","node":"pve1","vmid":200,"name":"proxy","status":"running"}]}""")
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", "", code = 404)
        fixture.route("/nodes/pve1/lxc/200/pending", """{"data":[{"key":"memory","value":1024,"pending":2048}]}""")
        fixture.route("/nodes/pve1/lxc/200/config") { request ->
            if (request.method == "PUT") {
                writes += request.body.readUtf8()
                response("""{"data":null}""")
            } else {
                response(
                    """{"data":{"hostname":"proxy","cores":2,"memory":2048,"swap":512,"rootfs":"local-lvm:vm-200-disk-0,size=8G",
                        "net0":"name=eth0,bridge=vmbr0,ip=dhcp","digest":"d1"}}""",
                )
            }
        }
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        viewModel = GuestConfigViewModel(repository, ClusterRepository(repository, refresh, scope), SchemaRepository(repository, cacheDir), refresh, "lxc/200")
        // The view model works on the main thread, which the compose rule drives, so the tests wait there.
        scope.launch { viewModel.guest.collect {} }
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
        cacheDir.deleteRecursively()
    }

    private fun scrollTo(text: String, substring: Boolean = false) {
        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(text, substring = substring))
        compose.onNodeWithText(text, substring = substring).assertExists()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun showsTheHardwareAndChangesTheProcessor() {
        compose.setContent { GuestConfigScreen("lxc/200", onClose = {}, viewModel = viewModel) }
        val cores = context.resources.getQuantityString(R.plurals.hardware_cores_count, 2, 2)
        compose.waitWithLooper { compose.onAllNodes(hasText(cores)).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(string(R.string.config_pending_title)).assertExists()
        // The list is longer than the test's screen, so each row is scrolled to before it is looked for.
        scrollTo(string(R.string.hardware_swap, "512.0 MiB"))
        scrollTo(string(R.string.hardware_root_disk))
        scrollTo(string(R.string.hardware_ip_dhcp), substring = true)
        scrollTo(cores)

        compose.onNodeWithText(cores).performClick()
        compose.onNode(hasText("2") and hasSetTextAction()).performTextReplacement("4")
        compose.onNodeWithText(string(R.string.action_save)).performClick()
        compose.waitWithLooper { writes.isNotEmpty() }
        assertTrue("cores=4" in writes.single())
        assertTrue("digest=d1" in writes.single())
    }

    @Test
    fun optionsSayWhyTheyCannotBeShown() {
        compose.setContent { GuestConfigScreen("lxc/200", onClose = {}, viewModel = viewModel) }
        compose.onNodeWithText(string(R.string.config_tab_options)).performClick()
        val reason = string(R.string.error_schema_unavailable)
        compose.waitWithLooper { compose.onAllNodes(hasText(reason)).fetchSemanticsNodes().isNotEmpty() }
    }
}
