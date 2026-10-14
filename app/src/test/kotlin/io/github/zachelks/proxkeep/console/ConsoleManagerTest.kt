package io.github.zachelks.proxkeep.console

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.core.api.auth.ApiTokenCredentials
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import io.github.zachelks.proxkeep.core.api.model.ResourceKind
import io.github.zachelks.proxkeep.data.PinnedServer
import io.github.zachelks.proxkeep.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
class ConsoleManagerTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val fixture = PinnedServer()

    // Unconfined, so the manager is watching the session as soon as it exists.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private lateinit var repository: ServerRepository
    private lateinit var manager: ConsoleManager

    private val shell = ConsoleTarget.NodeShell("pve1")

    @BeforeTest
    fun setUp(): Unit = runBlocking {
        repository = fixture.signedInRepository(scope)
        manager = ConsoleManager(context, repository, scope)
        fixture.route("/nodes/pve1/termproxy", """{"data":null,"message":"Permission check failed (/nodes/pve1, Sys.Console)"}""", code = 403)
        fixture.route("/nodes/pve1/qemu/100/termproxy", """{"data":null,"message":"no serial port"}""", code = 500)
    }

    @AfterTest
    fun tearDown() {
        manager.close()
        scope.cancel()
        fixture.close()
    }

    @Test
    fun aConsoleTheServerRefusesSaysWhy(): Unit = runBlocking {
        manager.open(shell, title = "pve1")

        val session = assertNotNull(manager.current.value)
        assertEquals("pve1", session.title)
        assertEquals(shell, session.target)
        val closed = withTimeout(TIMEOUT_MS) { session.state.first { it is ConsoleState.Closed } }
        assertEquals(ConsoleState.Closed("Permission check failed (/nodes/pve1, Sys.Console)"), closed)
    }

    @Test
    fun nothingOpensWhileSignedOut(): Unit = runBlocking {
        repository.signOut()

        manager.open(shell, title = "pve1")

        assertNull(manager.current.value)
        assertEquals(0, fixture.requestCount("/nodes/pve1/termproxy"))
    }

    @Test
    fun aNewConsoleReplacesTheOpenOne() {
        manager.open(shell, title = "pve1")
        val first = assertNotNull(manager.current.value)

        manager.open(ConsoleTarget.Guest("pve1", ResourceKind.Qemu, 100), title = "web")

        assertEquals("web", manager.current.value?.title)
        assertFalse(first.terminal.isRunning, "the replaced console is ended")
    }

    @Test
    fun reconnectingOpensAFreshConsoleOnTheSameTarget() {
        manager.open(shell, title = "pve1")
        val first = assertNotNull(manager.current.value)

        manager.reconnect()

        val second = assertNotNull(manager.current.value)
        assertNotSame(first, second)
        assertEquals(first.target, second.target)
        assertEquals(first.title, second.title)
    }

    @Test
    fun reconnectingWithNothingOpenDoesNothing() {
        manager.reconnect()

        assertNull(manager.current.value)
    }

    @Test
    fun closingEndsTheConsole() {
        manager.open(shell, title = "pve1")
        val session = assertNotNull(manager.current.value)

        manager.close()

        assertNull(manager.current.value)
        assertFalse(session.terminal.isRunning)
    }

    @Test
    fun signingOutClosesTheConsole(): Unit = runBlocking {
        manager.open(shell, title = "pve1")
        val session = manager.current.value

        repository.signOut()

        assertNull(manager.current.value)
        assertFalse(session!!.terminal.isRunning)
    }

    @Test
    fun switchingServerClosesTheConsole(): Unit = runBlocking {
        PinnedServer("second").use { second ->
            manager.open(shell, title = "pve1")

            repository.saveAndActivate(second.profile, ApiTokenCredentials(second.profile.principal, "secret"))

            assertNull(manager.current.value)
        }
    }

    @Test
    fun signingInAgainToTheSameServerKeepsTheConsole(): Unit = runBlocking {
        manager.open(shell, title = "pve1")
        val session = manager.current.value

        repository.saveAndActivate(fixture.profile, ApiTokenCredentials(fixture.profile.principal, "new secret"))

        assertSame(session, manager.current.value)
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
