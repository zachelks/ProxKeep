package io.github.zachelks.proxkeep.ui.addserver

import io.github.zachelks.proxkeep.MainDispatcherRule
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.auth.SecondFactorKind
import io.github.zachelks.proxkeep.core.api.auth.TicketCredentials
import io.github.zachelks.proxkeep.data.AuthMethod
import io.github.zachelks.proxkeep.data.MemoryProfileStorage
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.PlainSecrets
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.Session
import io.github.zachelks.proxkeep.data.StoredServers
import io.github.zachelks.proxkeep.data.response
import io.github.zachelks.proxkeep.ui.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Rule
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The add-server and sign-in flow, against a server with a self-signed certificate. */
class AddServerStateTest {

    @get:Rule
    val main = MainDispatcherRule()

    private val fixture = PinnedServer()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val storage = MemoryProfileStorage()
    private val address = "${fixture.server.hostName}:${fixture.server.port}"

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun anUnknownCertificateIsShownBeforeAnythingIsSent(): Unit = runBlocking {
        val state = newServer()

        state.host = address
        state.submitAddress()
        state.awaitIdle()

        assertEquals(AddServerState.Step.Certificate, state.step)
        assertEquals(fixture.fingerprint, state.certificate?.sha256Fingerprint)
        assertFalse(state.certificateChanged)
        assertTrue(fixture.requestedPaths.isEmpty(), "nothing reaches a server that is not trusted")
    }

    @Test
    fun anInvalidAddressIsRefusedAtOnce() {
        val state = newServer()

        state.host = "https://"
        state.submitAddress()

        assertEquals(UiMessage(R.string.error_invalid_address), state.error)
        assertEquals(AddServerState.Step.Address, state.step)
        assertFalse(state.busy)
    }

    @Test
    fun addingAServerWithAnApiToken(): Unit = runBlocking {
        fixture.route("/version", """{"data":{"version":"9.2.1"}}""")
        val state = trustedAtSignIn()

        state.method = AuthMethod.ApiToken
        state.tokenId = " root@pam!app "
        state.tokenSecret = "s3cret"
        state.submitSignIn()
        state.awaitIdle()

        assertEquals(AddServerState.Step.Done, state.step)
        assertEquals("", state.tokenSecret, "the secret is not kept once saved")
        val saved = storage.read().profiles.single()
        assertEquals(fixture.fingerprint, saved.pinnedSha256)
        assertEquals(AuthMethod.ApiToken, saved.authMethod)
        assertEquals("root@pam!app", saved.principal)
        assertEquals(fixture.server.hostName, saved.name, "an unnamed server is named after its host")
        assertEquals(saved.id, assertIs<Session.Active>(repository.session.value).profile.id)
    }

    @Test
    fun aMalformedTokenIdIsRefusedAtOnce(): Unit = runBlocking {
        val state = trustedAtSignIn()
        val requestsBefore = fixture.requestedPaths.size

        state.method = AuthMethod.ApiToken
        state.tokenId = "root@pam"
        state.tokenSecret = "s3cret"
        state.submitSignIn()

        assertEquals(UiMessage(R.string.error_invalid_token), state.error)
        assertEquals(requestsBefore, fixture.requestedPaths.size)
    }

    @Test
    fun signInRealmsLeaveOutWebLoginAndKeepTheChosenRealm(): Unit = runBlocking {
        val state = trustedAtSignIn()

        assertEquals(listOf("pam", "pve"), state.realms.map { it.realm })
        assertEquals("pam", state.realm)
    }

    @Test
    fun theServersDefaultRealmIsPickedWhenPamIsMissing(): Unit = runBlocking {
        val state = trustedAtSignIn(realms = """[{"realm":"pve","type":"pve"},{"realm":"corp","type":"ad","default":1}]""")

        assertEquals("corp", state.realm)
    }

    @Test
    fun aPasswordSignInWithASecondFactor(): Unit = runBlocking {
        fixture.route("/access/ticket") { request ->
            if ("tfa-challenge" in request.body.readUtf8()) {
                response("""{"data":{"username":"root@pam","ticket":"$TICKET","CSRFPreventionToken":"csrf","clustername":"lab"}}""")
            } else {
                response("""{"data":{"username":"root@pam","ticket":"$TOTP_CHALLENGE"}}""")
            }
        }
        val state = trustedAtSignIn()

        state.username = "root"
        state.password = "password"
        state.submitSignIn()
        state.awaitIdle()

        assertEquals(AddServerState.Step.SecondFactor, state.step)
        assertEquals(listOf(SecondFactorKind.Totp, SecondFactorKind.Recovery), state.factorKinds)

        state.code = "123456"
        state.submitSecondFactor()
        state.awaitIdle()

        assertEquals(AddServerState.Step.Done, state.step)
        assertEquals("", state.password)
        assertEquals("", state.code)
        val session = assertIs<Session.Active>(repository.session.value)
        assertEquals(TicketCredentials("root@pam", TICKET, "csrf"), session.client.credentials)
        assertEquals("lab", session.profile.name, "an unnamed server is named after its cluster")
        assertNull(storage.read().profiles.single().encryptedSecret, "passwords and tickets are never stored")
    }

    @Test
    fun aRejectedPasswordStaysOnSignIn(): Unit = runBlocking {
        fixture.route("/access/ticket", """{"data":null}""", code = 401)
        val state = trustedAtSignIn()

        state.username = "root"
        state.password = "wrong"
        state.submitSignIn()
        state.awaitIdle()

        assertEquals(AddServerState.Step.SignIn, state.step)
        assertEquals(UiMessage(R.string.error_sign_in_rejected), state.error)
        assertEquals(Session.None, repository.session.value)
    }

    @Test
    fun aMissingPasswordIsRefusedAtOnce(): Unit = runBlocking {
        val state = trustedAtSignIn()

        state.username = "root"
        state.submitSignIn()

        assertEquals(UiMessage(R.string.error_missing_credentials), state.error)
    }

    @Test
    fun aSecurityKeyAloneCannotBeAnswered(): Unit = runBlocking {
        fixture.route("/access/ticket", """{"data":{"username":"root@pam","ticket":"$WEBAUTHN_CHALLENGE"}}""")
        val state = trustedAtSignIn()

        state.username = "root"
        state.password = "password"
        state.submitSignIn()
        state.awaitIdle()

        assertEquals(AddServerState.Step.SignIn, state.step)
        assertEquals(UiMessage(R.string.error_security_key_unsupported), state.error)
    }

    @Test
    fun backStepsThroughTheFlow(): Unit = runBlocking {
        fixture.route("/access/ticket", """{"data":{"username":"root@pam","ticket":"$TOTP_CHALLENGE"}}""")
        val state = trustedAtSignIn()
        state.username = "root"
        state.password = "password"
        state.submitSignIn()
        state.awaitIdle()
        state.code = "12"

        assertTrue(state.back())
        assertEquals(AddServerState.Step.SignIn, state.step)
        assertEquals("", state.code)
        assertTrue(state.back())
        assertEquals(AddServerState.Step.Address, state.step)
        assertFalse(state.back(), "the first step leaves the flow")
    }

    @Test
    fun cancellingForgetsEverySecret() {
        val state = newServer()
        state.password = "password"
        state.tokenSecret = "s3cret"
        state.code = "123456"

        state.cancel()

        assertEquals(listOf("", "", ""), listOf(state.password, state.tokenSecret, state.code))
    }

    @Test
    fun signingInToASavedServerStartsAtOnceWithItsPin(): Unit = runBlocking {
        fixture.route("/access/domains", """{"data":$REALMS}""")
        val saved = fixture.profile.copy(authMethod = AuthMethod.Password, principal = "admin@pve")
        storage.write(StoredServers(listOf(saved), saved.id))
        val repository = startedRepository()

        val viewModel = AddServerViewModel(repository, saved.id)
        viewModel.state.awaitIdle()

        assertEquals(AddServerState.Step.SignIn, viewModel.state.step, "a pinned certificate is not asked about again")
        assertFalse(viewModel.state.isNewServer)
        assertEquals(address, viewModel.state.host)
        assertEquals("admin", viewModel.state.username)
        assertEquals("pve", viewModel.state.realm)
    }

    @Test
    fun aSavedServerWithANewCertificateSaysSo(): Unit = runBlocking {
        val saved = fixture.profile.copy(pinnedSha256 = "00:11:22", authMethod = AuthMethod.Password, principal = "root@pam")
        storage.write(StoredServers(listOf(saved), saved.id))

        val viewModel = AddServerViewModel(startedRepository(), saved.id)
        viewModel.state.awaitIdle()

        assertEquals(AddServerState.Step.Certificate, viewModel.state.step)
        assertTrue(viewModel.state.certificateChanged)
    }

    private lateinit var repository: ServerRepository

    private fun newServer(): AddServerState {
        repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        return AddServerState(repository, scope, existing = null)
    }

    private suspend fun startedRepository(): ServerRepository {
        repository = ServerRepository(storage, PlainSecrets(), OkHttpClient(), scope)
        repository.loaded.first { it }
        return repository
    }

    /** A new server whose certificate the person has just trusted. */
    private suspend fun trustedAtSignIn(realms: String = REALMS): AddServerState {
        fixture.route("/access/domains", """{"data":$realms}""")
        val state = newServer()
        repository.loaded.first { it }
        state.host = address
        state.submitAddress()
        state.awaitIdle()
        state.trustCertificate()
        state.awaitIdle()
        assertEquals(AddServerState.Step.SignIn, state.step)
        return state
    }

    private suspend fun AddServerState.awaitIdle() {
        withTimeout(TIMEOUT_MS) { while (busy) delay(10) }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val REALMS = """[{"realm":"pam","type":"pam"},{"realm":"pve","type":"pve","default":1},{"realm":"sso","type":"openid"}]"""
        const val TICKET = "PVE:root@pam:6712F3A1::c2lnbmF0dXJl"
        const val TOTP_CHALLENGE = "PVE:!tfa!%7B%22totp%22%3Atrue%2C%22recovery%22%3A%5B0%5D%7D:6712F3A1::Y2hhbGxlbmdl"
        const val WEBAUTHN_CHALLENGE = "PVE:!tfa!%7B%22webauthn%22%3A%7B%22challenge%22%3A%22x%22%7D%7D:6712F3A1::Y2hhbGxlbmdl"
    }
}
