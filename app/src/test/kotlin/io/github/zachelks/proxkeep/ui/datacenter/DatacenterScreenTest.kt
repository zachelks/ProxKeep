package io.github.zachelks.proxkeep.ui.datacenter

import android.content.Context
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.waitWithLooper
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class DatacenterScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun withoutASchemaEverySectionIsOfferedAndOpens() {
        val opened = mutableListOf<Route>()
        compose.setContent { DatacenterScreen(onClose = {}, onOpenRoute = { opened += it }) }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.dc_users))).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(context.getString(R.string.dc_users)).performClick()
        assertEquals(Route.Collection("users"), opened.single())

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.dc_endpoint_webhook)))
        compose.onNodeWithText(context.getString(R.string.dc_endpoint_webhook)).performClick()
        assertEquals(Route.Collection("notify-webhook"), opened.last())
    }
}
