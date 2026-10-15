package io.github.zachelks.proxkeep.ui.search

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SearchScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val opened = mutableListOf<ClusterResource>()
    private lateinit var viewModel: SearchViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route(
            "/cluster/resources",
            """{"data":[
                {"id":"qemu/100","type":"qemu","node":"pve1","vmid":100,"name":"web","status":"running"},
                {"id":"lxc/200","type":"lxc","node":"pve1","vmid":200,"name":"dns","status":"stopped"}]}""",
        )
        val refresh = RefreshSignal()
        viewModel = SearchViewModel(ClusterRepository(fixture.signedInRepository(scope), refresh, scope), refresh)
        compose.setContent { SearchScreen(viewModel, onClose = {}, onOpen = { opened += it }) }
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun typingFindsAGuestAndPickingItOpensIt() {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextExists(context.getString(R.string.search_prompt)) }

        compose.onNodeWithText(context.getString(R.string.search_hint)).performTextInput("dn")
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextExists("dns") }
        compose.onNodeWithText("web").assertDoesNotExist()
        compose.onNodeWithText("dns").performClick()

        assertEquals(listOf("lxc/200"), opened.map { it.id })
    }

    @Test
    fun aQueryWithoutMatchesSaysSo() {
        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextExists(context.getString(R.string.search_prompt)) }

        compose.onNodeWithText(context.getString(R.string.search_hint)).performTextInput("mail")

        compose.waitUntil(TIMEOUT_MS) { compose.onAllNodesWithTextExists(context.getString(R.string.search_no_results, "mail")) }
        compose.onNodeWithText(context.getString(R.string.search_no_results, "mail")).assertIsDisplayed()
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}

private fun androidx.compose.ui.test.junit4.ComposeTestRule.onAllNodesWithTextExists(text: String): Boolean =
    onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().isNotEmpty()
