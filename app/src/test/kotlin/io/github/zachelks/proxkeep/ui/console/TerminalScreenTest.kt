package io.github.zachelks.proxkeep.ui.console

import android.content.Context
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.ConsoleSession
import io.github.zachelks.proxkeep.core.api.console.ConsoleTarget
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

// The terminal measures its font, which needs real text rendering.
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TerminalScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val shell = ConsoleSession(context, "pve1", ConsoleTarget.NodeShell("pve1"))
    private val other = ConsoleSession(context, "pve2", ConsoleTarget.NodeShell("pve2"))
    private val events = mutableListOf<String>()

    @AfterTest
    fun tearDown() {
        shell.end()
        other.end()
    }

    private fun show() {
        compose.setContent {
            TerminalScreen(
                sessions = listOf(shell, other),
                console = shell,
                onSelect = { events += "select ${it.title}" },
                onEnd = { events += "end ${it.title}" },
                onReconnect = { events += "reconnect ${it.title}" },
                onLeave = { events += "leave" },
            )
        }
    }

    @Test
    fun theTitleSwitchesBetweenOpenConsoles() {
        show()

        compose.onNodeWithText("pve1").performClick()
        compose.onNodeWithText("pve2").performClick()

        assertEquals(listOf("select pve2"), events)
    }

    @Test
    fun leavingKeepsTheConsoleRunning() {
        show()

        compose.onNodeWithContentDescription(context.getString(R.string.action_back)).performClick()

        assertEquals(listOf("leave"), events)
    }

    @Test
    fun endingARunningConsoleAsksFirst() {
        show()

        compose.onNodeWithContentDescription(context.getString(R.string.action_more)).performClick()
        compose.onNodeWithText(context.getString(R.string.console_end_confirm)).performClick()
        assertEquals(emptyList(), events)
        compose.onNode(hasText(context.getString(R.string.console_end_confirm)) and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf("end pve1"), events)
    }
}
