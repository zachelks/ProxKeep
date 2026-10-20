package io.github.zachelks.proxkeep.ui.datacenter

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.waitWithLooper
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
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class TwoFactorScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var viewModel: TwoFactorViewModel? = null

    @AfterTest
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aSecondFactorIsDeletedAfterConfirming() {
        val writes = CopyOnWriteArrayList<String>()
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route(
            "/access/tfa",
            """{"data":[{"userid":"root@pam","entries":[{"id":"totp-1","type":"totp","description":"Phone","created":1700000000,"enable":1}]}]}""",
        )
        fixture.route("/access/tfa/root@pam/totp-1") { request ->
            writes += request.method!!
            response("""{"data":null}""")
        }
        val model = TwoFactorViewModel(runBlocking { fixture.signedInRepository(scope) }, RefreshSignal())
        viewModel = model
        scope.launch { model.users.collect {} }
        compose.setContent { TwoFactorScreen(onClose = {}, viewModel = model) }

        compose.waitWithLooper { compose.onAllNodes(hasText("Phone")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription(context.getString(R.string.action_more_for, "Phone")).performClick()
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.tfa_delete_title, "Phone"))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.action_delete)).performClick()
        compose.waitWithLooper { writes.isNotEmpty() && model.dialog.value == null }
        assertEquals(listOf("DELETE"), writes.toList())
    }
}
