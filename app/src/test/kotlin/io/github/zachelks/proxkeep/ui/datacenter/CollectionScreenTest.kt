package io.github.zachelks.proxkeep.ui.datacenter

import android.content.Context
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
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
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

@RunWith(RobolectricTestRunner::class)
class CollectionScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val cacheDir = Files.createTempDirectory("schema").toFile()
    private var viewModel: CollectionViewModel? = null

    @AfterTest
    fun tearDown() {
        viewModel?.viewModelScope?.cancel()
        scope.cancel()
        fixture.close()
        cacheDir.deleteRecursively()
    }

    private fun show(specId: String, list: String, path: String) {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/pve-docs/api-viewer/apidoc.js", "", code = 404)
        fixture.route(path, list)
        val repository = runBlocking { fixture.signedInRepository(scope) }
        val model = CollectionViewModel(repository, SchemaRepository(repository, cacheDir), RefreshSignal(), Collections.get(specId)!!, emptyMap())
        viewModel = model
        scope.launch { model.items.collect {} }
        compose.setContent { CollectionScreen(specId, emptyMap(), null, onClose = {}, onOpenRoute = {}, viewModel = model) }
    }

    @Test
    fun usersAreListed() {
        show(
            "users",
            """{"data":[{"userid":"root@pam","enable":1,"expire":0,"firstname":"","email":"root@example.com","tokens":[{"tokenid":"ci"}]},
               {"userid":"alice@pve","enable":1,"expire":0,"groups":"admins"}]}""",
            "/access/users",
        )
        compose.waitWithLooper { compose.onAllNodes(hasText("alice@pve")).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("root@pam")).fetchSemanticsNodes().isNotEmpty()
    }

    @Test
    fun theScreenBuildsItsOwnViewModel() {
        compose.setContent { CollectionScreen("users", emptyMap(), null, onClose = {}, onOpenRoute = {}) }
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.dc_users))).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun anEmptyListSaysSo() {
        show("replication", """{"data":[]}""", "/cluster/replication")
        compose.waitWithLooper { compose.onAllNodes(hasText(context.getString(R.string.dc_empty))).fetchSemanticsNodes().isNotEmpty() }
    }
}
