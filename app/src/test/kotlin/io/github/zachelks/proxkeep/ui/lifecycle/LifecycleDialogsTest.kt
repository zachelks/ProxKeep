package io.github.zachelks.proxkeep.ui.lifecycle

import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class LifecycleDialogsTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var viewModel: LifecycleViewModel
    private var done = 0

    private val stopped = ClusterResource(id = "qemu/100", type = "qemu", node = "pve1", vmid = 100, name = "web", status = "stopped")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[{"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"stopped"}]}""")
        fixture.route("/nodes/pve1/qemu/100", """{"data":"UPID:pve1:1:2:3:qmdestroy:100:root@pam:"}""")
        fixture.route("/nodes/pve1/tasks/UPID:pve1:1:2:3:qmdestroy:100:root@pam:/status", """{"data":{"status":"running"}}""")
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        viewModel = LifecycleViewModel(repository, ClusterRepository(repository, refresh, scope), GuestLifecycle(repository, refresh, scope), refresh, "qemu/100")
        // The view model works on the main thread, which the compose rule drives, so the tests wait there.
        scope.launch { viewModel.guest.collect {} }
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun deletingAsksForTheGuestsIdToBeTyped() {
        compose.setContent { LifecycleDialog(LifecycleMenuItem.Delete, stopped, viewModel) { done++ } }
        compose.waitUntil(10_000) { viewModel.guest.value != null }
        val confirm = compose.onNode(hasText(context.getString(R.string.action_delete)) and hasAnyAncestor(isDialog()))

        confirm.assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("10")
        confirm.assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("0")
        confirm.assertIsEnabled()
        confirm.performClick()

        assertEquals(1, done)
        compose.waitUntil(10_000) { fixture.requestedPaths.contains("/nodes/pve1/qemu/100") }
    }

    @Test
    fun aRunningGuestCannotBeDeleted() {
        compose.setContent { LifecycleDialog(LifecycleMenuItem.Delete, stopped.copy(status = "running"), viewModel) { done++ } }

        compose.onNode(hasText(context.getString(R.string.delete_needs_stop))).assertExists()
        compose.onNode(hasText(context.getString(R.string.action_delete)) and hasAnyAncestor(isDialog())).assertIsNotEnabled()
    }
}
