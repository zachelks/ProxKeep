package io.github.zachelks.proxkeep.ui.datacenter

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.AuthMethod
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.waitWithLooper
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test

/** The datacenter's lists, opened the way a person does, in the real activity signed in to a server. */
@RunWith(RobolectricTestRunner::class)
class DatacenterFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private var scenario: ActivityScenario<MainActivity>? = null

    @AfterTest
    fun tearDown() {
        scenario?.close()
        fixture.close()
    }

    @Test
    fun eachListOpensAndReturns() {
        fixture.route("/version", """{"data":{"version":"9.0.3","release":"9.0","repoid":"abc"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", "", code = 404)
        fixture.route("/cluster/resources", """{"data":[{"id":"node/pve1","type":"node","node":"pve1","status":"online"}]}""")
        fixture.route("/cluster/tasks", """{"data":[]}""")
        fixture.route("/access/permissions", """{"data":{"/":{"Sys.Audit":1}}}""")
        fixture.route("/access/users", """{"data":[{"userid":"root@pam","enable":1,"expire":0,"groups":[],"realm-type":"pam","tfa-locked-until":null}]}""")
        fixture.route("/cluster/replication", """{"data":[]}""")
        fixture.route("/access/groups", """{"data":[{"groupid":"admins","users":"root@pam"}]}""")
        val container = (context as ProxKeepApplication).container
        runBlocking {
            container.repository.loaded.value
            container.repository.saveAndActivate(
                fixture.profile.copy(authMethod = AuthMethod.Password, principal = "root@pam"),
                TicketCredentials("root@pam", "PVE:root@pam:6712F3A1::c2ln", "csrf"),
            )
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        for ((label, expected) in listOf(R.string.dc_replication to context.getString(R.string.dc_empty), R.string.dc_users to "root@pam", R.string.dc_groups to "admins")) {
            container.pendingNavigation.request(Route.Datacenter)
            compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(label))).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(context.getString(label)).performClick()
            compose.waitWithLooper { compose.onAllNodes(hasText(expected)).fetchSemanticsNodes().isNotEmpty() }
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            scenario!!.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        }
    }
}
