@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.console

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
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
 * A full-screen terminal: the console on screen, a row of the keys an
 * on-screen keyboard lacks, and a switcher between the open consoles.
 * Leaving the screen keeps every console running; ending one is a separate,
 * confirmed action.
 */
@Composable
fun TerminalScreen(
    sessions: List<ConsoleSession>,
    console: ConsoleSession,
    onSelect: (ConsoleSession) -> Unit,
    onEnd: (ConsoleSession) -> Unit,
    onReconnect: (ConsoleSession) -> Unit,
    onLeave: () -> Unit,
) {
    val state by console.state.collectAsStateWithLifecycle()
    var confirmingEnd by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var switcherOpen by remember { mutableStateOf(false) }
    val labels = sessionLabels(sessions)

    BackHandler(onBack = onLeave)

    // Without the permission the console notification is hidden, though consoles still stay connected.
    val context = LocalContext.current
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
                    Box {
                        Column(
                            modifier = Modifier.clickable(
                                enabled = sessions.size > 1,
                                onClickLabel = stringResource(R.string.console_switch),
                            ) { switcherOpen = true },
                        ) {
                            Text(labels[console] ?: console.title)
                            Text(
                                text = if (sessions.size > 1) {
                                    pluralStringResource(R.plurals.console_open_count, sessions.size, stateLabel(state), sessions.size)
                                } else {
                                    stateLabel(state)
                                },
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        DropdownMenu(expanded = switcherOpen, onDismissRequest = { switcherOpen = false }) {
                            sessions.forEach { session ->
                                val sessionState by session.state.collectAsStateWithLifecycle()
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(labels[session] ?: session.title)
                                            Text(stateLabel(sessionState), style = MaterialTheme.typography.labelSmall)
                                        }
                                    },
                                    leadingIcon = {
                                        RadioButton(selected = session === console, onClick = null)
                                    },
                                    onClick = {
                                        switcherOpen = false
                                        onSelect(session)
                                    },
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onLeave) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    TextButton(onClick = console::paste, enabled = state is ConsoleState.Connected) {
                        Text(stringResource(R.string.action_paste))
                    }
                    Box {
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.action_more),
                            )
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.console_end_confirm)) },
                                onClick = {
                                    menuOpen = false
                                    if (state is ConsoleState.Closed) onEnd(console) else confirmingEnd = true
                                },
                            )
                        }
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
            // A new view for each console, so switching never mixes their screens.
            key(console.id) {
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
            }
            if (state is ConsoleState.Closed) {
                ClosedBar(onReconnect = { onReconnect(console) }, onClose = { onEnd(console) })
            } else {
                ExtraKeysRow(modifiers = modifiers, view = { viewClient.view })
            }
        }
    }

    if (confirmingEnd) {
        AlertDialog(
            onDismissRequest = { confirmingEnd = false },
            title = { Text(stringResource(R.string.console_end_title)) },
            text = { Text(stringResource(R.string.console_end_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingEnd = false
                        onEnd(console)
                    },
                ) {
                    Text(stringResource(R.string.console_end_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingEnd = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }
}

/** Names for the open consoles. Consoles with the same title are numbered in the order they were opened. */
internal fun sessionLabels(sessions: List<ConsoleSession>): Map<ConsoleSession, String> {
    val counts = sessions.groupingBy { it.title }.eachCount()
    val seen = mutableMapOf<String, Int>()
    return sessions.associateWith { session ->
        if ((counts[session.title] ?: 0) < 2) {
            session.title
        } else {
            val number = (seen[session.title] ?: 0) + 1
            seen[session.title] = number
            "${session.title} ($number)"
        }
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
