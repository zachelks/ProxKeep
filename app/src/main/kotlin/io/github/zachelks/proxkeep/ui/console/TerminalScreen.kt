@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.console

import android.graphics.Typeface
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.termux.terminal.KeyHandler
import com.termux.view.TerminalView
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.ConsoleSession
import io.github.zachelks.proxkeep.console.ConsoleState

private val DefaultTextSize = 14.sp
private val SmallestTextSize = 7.sp
private val LargestTextSize = 36.sp

/**
 * A full-screen terminal for one console: the terminal itself, a row of the
 * keys an on-screen keyboard lacks, and the connection's state.
 */
@Composable
fun TerminalScreen(console: ConsoleSession, onClose: () -> Unit, onReconnect: () -> Unit) {
    val state by console.state.collectAsStateWithLifecycle()
    var confirmingClose by remember { mutableStateOf(false) }

    // Leaving ends the shell, so ask first unless it has already ended.
    val requestClose = {
        if (state is ConsoleState.Closed) onClose() else confirmingClose = true
    }
    BackHandler(onBack = requestClose)

    val density = LocalDensity.current
    val modifiers = remember { ModifierKeys() }
    val viewClient = remember {
        with(density) {
            ConsoleViewClient(
                modifiers = modifiers,
                textSize = DefaultTextSize.roundToPx(),
                textSizeRange = SmallestTextSize.roundToPx()..LargestTextSize.roundToPx(),
            )
        }
    }

    DisposableEffect(console) {
        onDispose {
            console.view = null
            viewClient.view = null
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(console.title)
                        Text(text = stateLabel(state), style = MaterialTheme.typography.labelMedium)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = requestClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.action_close),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = console::paste, enabled = state is ConsoleState.Connected) {
                        Text(stringResource(R.string.action_paste))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
                .imePadding(),
        ) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    TerminalView(context, null).apply {
                        setTerminalViewClient(viewClient)
                        setTextSize(viewClient.textSize)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                        viewClient.view = this
                        console.view = this
                        attachSession(console.terminal)
                        post { viewClient.showKeyboard() }
                    }
                },
            )
            if (state is ConsoleState.Closed) {
                ClosedBar(onReconnect = onReconnect, onClose = onClose)
            } else {
                ExtraKeysRow(modifiers = modifiers, view = { viewClient.view })
            }
        }
    }

    if (confirmingClose) {
        AlertDialog(
            onDismissRequest = { confirmingClose = false },
            title = { Text(stringResource(R.string.console_end_title)) },
            text = { Text(stringResource(R.string.console_end_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingClose = false
                        onClose()
                    },
                ) {
                    Text(stringResource(R.string.console_end_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingClose = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun stateLabel(state: ConsoleState): String = when (state) {
    ConsoleState.Connecting -> stringResource(R.string.console_connecting)
    ConsoleState.Connected -> stringResource(R.string.console_connected)
    is ConsoleState.Closed -> {
        val reason = state.reason
        if (reason.isNullOrBlank()) {
            stringResource(R.string.console_closed)
        } else {
            stringResource(R.string.console_closed_reason, reason)
        }
    }
}

@Composable
private fun ClosedBar(onReconnect: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            TextButton(onClick = onReconnect) { Text(stringResource(R.string.console_reconnect)) }
        }
    }
}

/** One key of the extra-keys row. */
private sealed interface ExtraKey {
    val label: String

    /** A key the terminal knows by key code, such as an arrow. */
    data class Code(override val label: String, val keyCode: Int) : ExtraKey

    /** A key that types a character. */
    data class Character(override val label: String, val codePoint: Int = label.codePointAt(0)) : ExtraKey
}

private val extraKeys: List<ExtraKey> = listOf(
    ExtraKey.Code("ESC", KeyEvent.KEYCODE_ESCAPE),
    ExtraKey.Code("TAB", KeyEvent.KEYCODE_TAB),
    ExtraKey.Code("←", KeyEvent.KEYCODE_DPAD_LEFT),
    ExtraKey.Code("↓", KeyEvent.KEYCODE_DPAD_DOWN),
    ExtraKey.Code("↑", KeyEvent.KEYCODE_DPAD_UP),
    ExtraKey.Code("→", KeyEvent.KEYCODE_DPAD_RIGHT),
    ExtraKey.Code("HOME", KeyEvent.KEYCODE_MOVE_HOME),
    ExtraKey.Code("END", KeyEvent.KEYCODE_MOVE_END),
    ExtraKey.Code("PGUP", KeyEvent.KEYCODE_PAGE_UP),
    ExtraKey.Code("PGDN", KeyEvent.KEYCODE_PAGE_DOWN),
    ExtraKey.Character("-"),
    ExtraKey.Character("/"),
    ExtraKey.Character("|"),
    ExtraKey.Character("~"),
)

/**
 * The keys an on-screen keyboard does not have. Ctrl and Alt apply to the
 * next key pressed, here or on the keyboard, and then release.
 */
@Composable
private fun ExtraKeysRow(modifiers: ModifierKeys, view: () -> TerminalView?) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            KeyCap(label = "CTRL", held = modifiers.ctrl, role = Role.Switch) { modifiers.ctrl = !modifiers.ctrl }
            KeyCap(label = "ALT", held = modifiers.alt, role = Role.Switch) { modifiers.alt = !modifiers.alt }
            extraKeys.forEach { key ->
                KeyCap(label = key.label, held = false, role = Role.Button) {
                    val target = view() ?: return@KeyCap
                    // Nothing can be typed until the terminal has been laid out.
                    if (target.mEmulator == null) return@KeyCap
                    target.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    when (key) {
                        is ExtraKey.Code -> {
                            var keyModifiers = 0
                            if (modifiers.takeCtrl()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_CTRL
                            if (modifiers.takeAlt()) keyModifiers = keyModifiers or KeyHandler.KEYMOD_ALT
                            target.handleKeyCode(key.keyCode, keyModifiers)
                        }
                        // The view itself applies and releases Ctrl and Alt for typed characters.
                        is ExtraKey.Character -> target.inputCodePoint(
                            TerminalView.KEY_EVENT_SOURCE_VIRTUAL_KEYBOARD,
                            key.codePoint,
                            false,
                            false,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyCap(label: String, held: Boolean, role: Role, onClick: () -> Unit) {
    val background = if (held) MaterialTheme.colorScheme.primary else Color.Transparent
    val foreground = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .clickable(role = role, onClick = onClick)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = foreground,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
