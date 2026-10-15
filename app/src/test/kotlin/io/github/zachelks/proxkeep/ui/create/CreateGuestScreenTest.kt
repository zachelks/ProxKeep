package io.github.zachelks.proxkeep.ui.create

import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.waitWithLooper
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class CreateGuestScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val posts = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: CreateGuestViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"node/pve1","type":"node","node":"pve1","status":"online"}]}""")
        fixture.route("/cluster/nextid", """{"data":"102"}""")
        fixture.route("/nodes/pve1/storage", """{"data":[{"storage":"local","content":"vztmpl","active":1},{"storage":"local-lvm","content":"rootdir","active":1}]}""")
        fixture.route("/nodes/pve1/storage/local/content", """{"data":[{"volid":"local:vztmpl/debian-13-standard_13.1-1_amd64.tar.zst"}]}""")
        fixture.route("/nodes/pve1/network", """{"data":[{"iface":"vmbr0","type":"bridge"}]}""")
        fixture.route("/nodes/pve1/lxc") { request ->
            posts += request.body.readUtf8()
            response("""{"data":"UPID:pve1:1:2:3:vzcreate:102:root@pam:"}""")
        }
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:vzcreate:102:root@pam:/status", """{"data":{"status":"running"}}""")
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        viewModel = CreateGuestViewModel(repository, ClusterRepository(repository, refresh, scope), GuestLifecycle(repository, refresh, scope), ResourceKind.Lxc)
        // The view model works on the main thread, which the compose rule drives, so the tests wait there.
        scope.launch { viewModel.cluster.collect {} }
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    private fun string(id: Int) = context.getString(id)


    private fun type(label: Int, text: String) {
        compose.onNode(hasSetTextAction() and hasText(string(label))).performScrollTo().performTextInput(text)
    }

    @Test
    fun aContainerIsCreatedStepByStep() {
        var closed = 0
        compose.setContent { CreateGuestScreen(ResourceKind.Lxc, onClose = { closed++ }, viewModel = viewModel) }
        compose.waitWithLooper { viewModel.ct.value.vmid == "102" && viewModel.ct.value.template.isNotEmpty() }

        val next = compose.onNodeWithText(string(R.string.action_next))
        next.assertIsNotEnabled()
        type(R.string.setting_hostname, "proxy")
        type(R.string.create_password, "secret1")
        type(R.string.create_password_again, "secret1")
        next.assertIsEnabled().performClick()

        compose.onNodeWithText("debian-13-standard_13.1-1_amd64.tar.zst").assertExists()
        repeat(4) { next.performClick() }

        compose.onNodeWithText(string(R.string.create_step_confirm)).assertExists()
        compose.onNodeWithText("proxy").assertExists()
        compose.onNodeWithText(string(R.string.action_create)).performClick()

        assertEquals(1, closed)
        compose.waitWithLooper { posts.isNotEmpty() }
        assertTrue("hostname=proxy" in posts.single())
        assertTrue("rootfs=local-lvm%3A8" in posts.single())
    }

    @Test
    fun backOnTheFirstStepCancels() {
        var closed = 0
        compose.setContent { CreateGuestScreen(ResourceKind.Lxc, onClose = { closed++ }, viewModel = viewModel) }
        compose.waitWithLooper { compose.onAllNodes(hasText(string(R.string.action_cancel))).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText(string(R.string.action_cancel)))[0].performClick()
        assertEquals(1, closed)
    }
}
