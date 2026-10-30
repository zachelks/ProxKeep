package io.github.zachelks.proxkeep.ui.datacenter

import androidx.lifecycle.viewModelScope
import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.access.UserTfa
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.MemoryProfileStorage
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.PlainSecrets
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.lifecycle.Loadable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Rule
import java.net.URLDecoder
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwoFactorViewModelTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val forms = CopyOnWriteArrayList<Map<String, String>>()

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    private fun form(body: String): Map<String, String> = body.split("&").filter { it.isNotEmpty() }.associate {
        val (key, value) = it.split("=", limit = 2)
        key to URLDecoder.decode(value, Charsets.UTF_8)
    }

    /** Signed in as a user other than root@pam, who must confirm changes with a password. */
    private suspend fun signedInAsOps(): ServerRepository {
        val repository = ServerRepository(MemoryProfileStorage(), PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        repository.saveAndActivate(fixture.profile.copy(principal = "ops@pve", name = "Home lab"), TicketCredentials("ops@pve", "PVE:ops@pve:6712F3A1::c2ln", "csrf"))
        return repository
    }

    @Test
    fun theSignedInUserIsListedEvenWithoutSecondFactors(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/access/tfa", """{"data":[{"userid":"zed@pve","entries":[{"id":"totp-1","type":"totp"}]}]}""")
        val viewModel = TwoFactorViewModel(fixture.signedInRepository(scope), RefreshSignal())
        try {
            scope.launch { viewModel.users.collect {} }
            val users = assertIs<Loadable.Loaded<List<UserTfa>>>(withTimeout(TIMEOUT_MS) { viewModel.users.first { it is Loadable.Loaded } })
            assertEquals(listOf("root@pam", "zed@pve"), users.value.map { it.userid }, "an API token's user, without the token's name")
            assertFalse(viewModel.needsPassword, "root@pam needs no password")
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun aWrongCodeKeepsTheDialogOpenAndAGoodOneClosesIt(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/access/tfa", """{"data":[]}""")
        var accept = false
        fixture.route("/access/tfa/ops@pve") { request ->
            forms += form(request.body.readUtf8())
            if (accept) response("""{"data":{"id":"totp-1"}}""") else response("""{"data":null,"message":"failed to verify code"}""", code = 400)
        }
        val viewModel = TwoFactorViewModel(signedInAsOps(), RefreshSignal())
        try {
            assertTrue(viewModel.needsPassword)
            viewModel.addTotp("ops@pve")
            val dialog = assertIs<TfaDialog.AddTotp>(viewModel.dialog.value)

            viewModel.confirmTotp("Phone", "000000", "hunter2")
            val error = withTimeout(TIMEOUT_MS) { viewModel.dialogError.filterNotNull().first() }
            assertEquals(R.string.error_server_response, error.id)
            assertEquals(dialog, viewModel.dialog.value, "the same secret, to try another code")
            withTimeout(TIMEOUT_MS) { viewModel.busy.first { !it } }

            accept = true
            val message = async(start = CoroutineStart.UNDISPATCHED) { withTimeout(TIMEOUT_MS) { viewModel.messages.first() } }
            viewModel.confirmTotp("Phone", "123456", "hunter2")
            assertEquals(R.string.tfa_added, message.await().id)
            assertNull(viewModel.dialog.value)
            assertEquals(
                mapOf("type" to "totp", "totp" to dialog.totp.uri("Home lab", "ops@pve"), "value" to "123456", "description" to "Phone", "password" to "hunter2"),
                forms.last(),
            )
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun newRecoveryKeysAreShownOnce(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.0.3"}}""")
        fixture.route("/access/tfa", """{"data":[]}""")
        fixture.route("/access/tfa/root@pam") { request ->
            forms += form(request.body.readUtf8())
            response("""{"data":{"id":"recovery","recovery":["aaaa-bbbb","cccc-dddd"]}}""")
        }
        val viewModel = TwoFactorViewModel(fixture.signedInRepository(scope), RefreshSignal())
        try {
            viewModel.addRecoveryKeys("root@pam")
            viewModel.confirmRecoveryKeys("", "ignored")
            val shown = withTimeout(TIMEOUT_MS) { viewModel.dialog.first { it is TfaDialog.RecoveryKeys } }
            assertEquals(listOf("aaaa-bbbb", "cccc-dddd"), assertIs<TfaDialog.RecoveryKeys>(shown).keys)
            assertEquals(mapOf("type" to "recovery"), forms.single(), "root@pam sends no password")
            viewModel.dismiss()
            assertNull(viewModel.dialog.value)
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
