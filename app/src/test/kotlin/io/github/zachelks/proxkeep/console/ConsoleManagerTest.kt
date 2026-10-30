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
import kotlin.test.assertTrue

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
        manager.closeAll()
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
    fun aNewConsoleKeepsTheOthersRunning() {
        manager.open(shell, title = "pve1")
        val first = assertNotNull(manager.current.value)

        manager.open(ConsoleTarget.Guest("pve1", ResourceKind.Qemu, 100), title = "web")

        assertEquals(listOf("pve1", "web"), manager.sessions.value.map { it.title })
        assertEquals("web", manager.current.value?.title)
        assertTrue(first.terminal.isRunning, "switching never disconnects")
    }

    @Test
    fun selectingPutsAnotherConsoleOnScreen() {
        manager.open(shell, title = "pve1")
        val first = assertNotNull(manager.current.value)
        manager.open(ConsoleTarget.NodeShell("pve2"), title = "pve2")

        manager.select(first)

        assertSame(first, manager.current.value)
    }

    @Test
    fun reconnectingReplacesTheConsoleInPlace() {
        manager.open(shell, title = "pve1")
        val first = assertNotNull(manager.current.value)
        manager.open(ConsoleTarget.Guest("pve1", ResourceKind.Qemu, 100), title = "web")

        manager.reconnect(first)

        val fresh = manager.sessions.value.first()
        assertNotSame(first, fresh)
        assertEquals(first.target, fresh.target)
        assertEquals("pve1", fresh.title)
        assertSame(fresh, manager.current.value)
        assertFalse(first.terminal.isRunning)
        assertEquals(2, manager.sessions.value.size)
    }

    @Test
    fun reconnectingWithNothingOpenDoesNothing() {
        manager.reconnect()

        assertNull(manager.current.value)
    }

    @Test
    fun closingEndsTheConsoleAndShowsTheOneBesideIt() {
        manager.open(ConsoleTarget.NodeShell("a"), title = "a")
        manager.open(ConsoleTarget.NodeShell("b"), title = "b")
        manager.open(ConsoleTarget.NodeShell("c"), title = "c")
        val middle = manager.sessions.value[1]
        manager.select(middle)

        manager.close(middle)

        assertFalse(middle.terminal.isRunning)
        assertEquals(listOf("a", "c"), manager.sessions.value.map { it.title })
        assertEquals("c", manager.current.value?.title)
    }

    @Test
    fun closingTheLastConsoleLeavesNoneOnScreen() {
        manager.open(shell, title = "pve1")

        manager.close()

        assertNull(manager.current.value)
        assertEquals(emptyList(), manager.sessions.value)
    }

    @Test
    fun signingOutClosesEveryConsole(): Unit = runBlocking {
        manager.open(shell, title = "pve1")
        manager.open(ConsoleTarget.NodeShell("pve2"), title = "pve2")
        val opened = manager.sessions.value

        repository.signOut()

        assertNull(manager.current.value)
        assertEquals(emptyList(), manager.sessions.value)
        assertTrue(opened.none { it.terminal.isRunning })
    }

    @Test
    fun consolesWithTheSameTitleAreNumbered() {
        // Two guests on different nodes can share a name.
        manager.open(ConsoleTarget.Guest("pve1", ResourceKind.Lxc, 200), title = "web")
        manager.open(shell, title = "pve1")
        manager.open(ConsoleTarget.Guest("pve2", ResourceKind.Lxc, 201), title = "web")

        val labels = io.github.zachelks.proxkeep.ui.console.sessionLabels(manager.sessions.value)

        assertEquals(listOf("web (1)", "pve1", "web (2)"), manager.sessions.value.map { labels[it] })
    }

    @Test
    fun openingAConsoleAgainReturnsToTheOneStillOpen() {
        // This node's console is still connecting, so it counts as open.
        val waiting = ConsoleTarget.NodeShell("pve3")
        manager.open(waiting, title = "pve3")
        val first = assertNotNull(manager.current.value)
        manager.open(ConsoleTarget.Guest("pve1", ResourceKind.Qemu, 100), title = "web")

        manager.open(waiting, title = "pve3")

        assertSame(first, manager.current.value, "a second shell would start a second task on the server")
        assertEquals(2, manager.sessions.value.size)
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
