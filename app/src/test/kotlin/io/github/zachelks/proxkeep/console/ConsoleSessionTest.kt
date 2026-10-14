package io.github.zachelks.proxkeep.console

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The glue between a console connection and the terminal emulator. */
@RunWith(RobolectricTestRunner::class)
class ConsoleSessionTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val session = ConsoleSession(context, "pve1", ConsoleTarget.NodeShell("pve1"))

    @Test
    fun aSessionConnectsWhenTheServerIsReady() {
        assertEquals(ConsoleState.Connecting, session.state.value)

        session.channelListener.onConnected()
        idleMainThread()

        assertEquals(ConsoleState.Connected, session.state.value)
    }

    @Test
    fun outputFromTheServerIsShownInTheTerminal() {
        session.terminal.updateSize(80, 24, 10, 20)

        session.channelListener.onOutput("root@pve1:~# ".toByteArray())
        idleMainThread()

        assertTrue("root@pve1:~#" in screenText())
    }

    @Test
    fun outputThatArrivesBeforeTheViewIsLaidOutIsKept() {
        session.channelListener.onOutput("Linux pve1 6.14".toByteArray())
        idleMainThread()

        session.terminal.updateSize(80, 24, 10, 20)
        idleMainThread()

        assertTrue("Linux pve1 6.14" in screenText())
    }

    @Test
    fun aClosedConnectionSaysWhy() {
        session.terminal.updateSize(80, 24, 10, 20)

        session.channelListener.onClosed("Connection reset")
        idleMainThread()

        assertEquals(ConsoleState.Closed("Connection reset"), session.state.value)
        assertFalse(session.terminal.isRunning)
        assertTrue("[Connection reset]" in screenText())
    }

    @Test
    fun theFirstReasonForClosingIsKept() {
        session.fail("Permission check failed")
        session.channelListener.onClosed("Connection reset")
        idleMainThread()

        assertEquals(ConsoleState.Closed("Permission check failed"), session.state.value)
    }

    @Test
    fun endingStopsTheTerminalOnce() {
        session.end()
        session.end()

        assertFalse(session.terminal.isRunning)
    }

    @Test
    fun copiedTextGoesToTheClipboard() {
        session.onCopyTextToClipboard(session.terminal, "uptime")

        val clipboard = context.getSystemService(ClipboardManager::class.java)
        assertEquals("uptime", clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    private fun screenText(): String = session.terminal.emulator.screen.transcriptText

    private fun idleMainThread() = shadowOf(Looper.getMainLooper()).idle()
}
