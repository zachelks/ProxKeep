package io.github.zachelks.proxkeep.ui.storage

import android.content.Context
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.StorageTransfers
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

@RunWith(RobolectricTestRunner::class)
class StorageScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val deletes = CopyOnWriteArrayList<String>()
    private lateinit var viewModel: StorageViewModel

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        fixture.route("/cluster/resources", """{"data":[]}""")
        fixture.route("/nodes/pve1/storage/local/status", """{"data":{"type":"dir","content":"iso,vztmpl,backup","total":1000000000,"avail":600000000,"active":1}}""")
        fixture.route("/nodes/pve1/storage/local/content", """{"data":[
            {"volid":"local:iso/debian-13.iso","content":"iso","size":789577728,"ctime":1760400000},
            {"volid":"local:backup/vzdump-qemu-100.vma.zst","content":"backup","size":1000,"vmid":100,"protected":1}]}""")
        // The volume ID is one path segment, so its slash arrives encoded.
        fixture.route("/nodes/pve1/storage/local/content/local:iso%2Fdebian-13.iso") { request ->
            deletes += request.method!!
            response("""{"data":null}""")
        }
        val repository = fixture.signedInRepository(scope)
        val refresh = RefreshSignal()
        viewModel = StorageViewModel(repository, ClusterRepository(repository, refresh, scope), StorageTransfers(repository, refresh, scope), refresh, "pve1", "local")
    }

    @AfterTest
    fun tearDown() {
        viewModel.viewModelScope.cancel()
        scope.cancel()
        fixture.close()
    }

    private fun string(id: Int, vararg args: Any) = context.getString(id, *args)

    @Test
    fun volumesAreFilteredAndDeletedAfterAsking() {
        compose.setContent { StorageScreen("pve1", "local", onClose = {}, onOpenRoute = {}, viewModel = viewModel) }
        compose.waitWithLooper { compose.onAllNodes(hasText("debian-13.iso")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("vzdump-qemu-100.vma.zst").assertExists()

        // The chip, not the label on the volume's row.
        compose.onNode(hasText(string(R.string.content_iso)) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox)).performClick()
        compose.onNodeWithText("vzdump-qemu-100.vma.zst").assertDoesNotExist()

        compose.onNode(hasContentDescription(string(R.string.action_more_for, "debian-13.iso"))).performClick()
        compose.onNodeWithText(string(R.string.action_delete)).performClick()
        compose.onNode(hasText(string(R.string.action_delete)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitWithLooper { deletes.isNotEmpty() }
    }

    @Test
    fun aProtectedBackupCannotBeDeleted() {
        compose.setContent { StorageScreen("pve1", "local", onClose = {}, onOpenRoute = {}, viewModel = viewModel) }
        compose.waitWithLooper { compose.onAllNodes(hasText("vzdump-qemu-100.vma.zst")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription(string(R.string.action_more_for, "vzdump-qemu-100.vma.zst"))).assertDoesNotExist()
    }
}
