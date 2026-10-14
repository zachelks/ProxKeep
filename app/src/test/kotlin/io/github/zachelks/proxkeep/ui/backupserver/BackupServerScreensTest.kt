package io.github.zachelks.proxkeep.ui.backupserver

import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.hasScrollToNodeAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.pbs.DatastoreUsage
import io.github.zachelks.proxkeep.core.api.pbs.PruneOptions
import io.github.zachelks.proxkeep.ui.datacenter.SectionList
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.waitWithLooper
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class BackupServerScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun administrationOpensTheServersOwnLists() {
        val opened = mutableListOf<Route>()
        compose.setContent { SectionList(BACKUP_SERVER_GROUPS, onOpenRoute = { opened += it }, snackbar = SnackbarHostState()) }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.pbs_sync_jobs))).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText(context.getString(R.string.pbs_sync_jobs)).performClick()
        assertEquals(Route.Collection("pbs-sync"), opened.single())

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.node_tab_services)))
        compose.onNodeWithText(context.getString(R.string.node_tab_services)).performClick()
        assertEquals(Route.Collection("pbs-services", mapOf("node" to "localhost")), opened.last())

        compose.onNode(hasScrollToNodeAction()).performScrollToNode(hasText(context.getString(R.string.pbs_dns)))
        compose.onNodeWithText(context.getString(R.string.pbs_dns)).performClick()
        assertEquals(Route.SchemaEdit(context.getString(R.string.pbs_dns), "/nodes/localhost/dns", "/nodes/{node}/dns", "PUT"), opened.last())
    }

    @Test
    fun anUnavailableDatastoreSaysWhy() {
        compose.setContent { DatastoreCard(DatastoreUsage("usb", error = "datastore is not mounted"), onClick = null) }
        compose.waitWithLooper { compose.onAllNodes(hasText("datastore is not mounted")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.pbs_datastore_unavailable)).assertExists()
    }

    @Test
    fun theDatastoresSpaceIsShown() {
        compose.setContent { DatastoreCard(DatastoreUsage("store1", total = 4L shl 30, used = 1L shl 30), onClick = null) }
        compose.waitWithLooper { compose.onAllNodes(hasText("store1")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.usage_space)).assertExists()
    }

    @Test
    fun aPruneStartsWithCommonCounts() {
        var chosen: PruneOptions? = null
        compose.setContent {
            PruneDialog(title = "Prune", body = "Body", confirmLabel = context.getString(R.string.pbs_action_preview), onDismiss = {}, onConfirm = { chosen = it })
        }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.pbs_action_preview))).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText(context.getString(R.string.pbs_action_preview)).performClick()
        assertEquals(PruneOptions(daily = 7, weekly = 4, monthly = 6), chosen)
    }
}
