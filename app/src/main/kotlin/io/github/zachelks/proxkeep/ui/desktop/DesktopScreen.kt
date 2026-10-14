@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.desktop

import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Build
import android.os.SystemClock
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.graphics.createBitmap
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.vnc.Cursor
import io.github.zachelks.proxkeep.core.api.vnc.Region
import io.github.zachelks.proxkeep.desktop.DesktopEvent
import io.github.zachelks.proxkeep.desktop.DesktopSession
import io.github.zachelks.proxkeep.desktop.DesktopState
import io.github.zachelks.proxkeep.desktop.Keysyms
import io.github.zachelks.proxkeep.ui.components.KeyCap
import io.github.zachelks.proxkeep.ui.components.ModifierCap
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A virtual machine's display, full screen. Leaving keeps the connection
 * open; disconnecting is in the menu.
 */
@Composable
fun DesktopScreen(session: DesktopSession, onLeave: () -> Unit, onDisconnect: () -> Unit, onReconnect: () -> Unit) {
    val state by session.state.collectAsStateWithLifecycle()
    val controller = remember(session) { DesktopController(session) }
    var showKeys by rememberSaveable { mutableStateOf(true) }
    var showingAbout by remember { mutableStateOf(false) }
    val catcher = remember { mutableStateOf<KeyboardCatcher?>(null) }
    val snackbars = remember { SnackbarHostState() }
    val context = LocalContext.current
    val activity = LocalActivity.current as? ComponentActivity
    val inPictureInPicture = rememberPictureInPicture(activity)

    BackHandler(onBack = onLeave)
    ImmersiveMode(activity)
    var screenBounds by remember { mutableStateOf<android.graphics.Rect?>(null) }
    AutoPictureInPicture(activity, state, screenBounds)

    val copied = stringResource(R.string.desktop_clipboard_copied)
    LaunchedEffect(session) {
        session.events.collect { event ->
            if (event is DesktopEvent.Clipboard) {
                context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(session.title, event.text))
                snackbars.showSnackbar(copied)
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // The toolbar sits above the screen rather than over it, so no part of the guest is hidden.
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            if (!inPictureInPicture) {
                Toolbar(
                    session = session,
                    state = state,
                    controller = controller,
                    showKeys = showKeys,
                    onToggleKeys = { showKeys = !showKeys },
                    onKeyboard = { catcher.value?.showKeyboard() },
                    onPaste = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                        if (!text.isNullOrEmpty()) session.sendClipboard(text)
                    },
                    onPictureInPicture = activity?.let { host -> { enterPictureInPicture(host, state) } },
                    onAbout = { showingAbout = true },
                    onLeave = onLeave,
                    onDisconnect = onDisconnect,
                )
            }
            DesktopCanvas(
                session,
                controller,
                interactive = !inPictureInPicture,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onGloballyPositioned { coordinates ->
                        val bounds = coordinates.boundsInWindow()
                        screenBounds = android.graphics.Rect(bounds.left.toInt(), bounds.top.toInt(), bounds.right.toInt(), bounds.bottom.toInt())
                    },
            )
            if (!inPictureInPicture && showKeys && state is DesktopState.Connected) DesktopKeysBar(controller)
        }
        // Takes keyboard input; it has no size of its own to show.
        AndroidView(
            factory = { KeyboardCatcher(it).apply { this.controller = controller } },
            update = { catcher.value = it },
            modifier = Modifier.size(1.dp),
        )
        when (val current = state) {
            DesktopState.Connecting -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            is DesktopState.Closed -> ClosedCard(current, onReconnect, onDisconnect, Modifier.align(Alignment.Center))
            is DesktopState.Connected -> Unit
        }
        SnackbarHost(hostState = snackbars, modifier = Modifier.align(Alignment.BottomCenter))
    }

    if (showingAbout) {
        AlertDialog(
            onDismissRequest = { showingAbout = false },
            title = { Text(stringResource(R.string.desktop_about)) },
            text = { Text(stringResource(R.string.desktop_about_body)) },
            confirmButton = { TextButton(onClick = { showingAbout = false }) { Text(stringResource(R.string.action_close)) } },
        )
    }
}

@Composable
private fun DesktopCanvas(session: DesktopSession, controller: DesktopController, interactive: Boolean, modifier: Modifier) {
    val frames by session.frames.collectAsStateWithLifecycle()
    val cursor by session.cursor.collectAsStateWithLifecycle()
    val state by session.state.collectAsStateWithLifecycle()
    val frame = remember(session) { FrameBitmap() }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val touchSlop = LocalViewConfiguration.current.touchSlop
    val interpreter = remember(touchSlop) { GestureInterpreter(controller.mode, touchSlop) }
    interpreter.mode = controller.mode

    LaunchedEffect(frames) { frame.refresh(session) }
    val desktop = (state as? DesktopState.Connected)?.desktop
    LaunchedEffect(desktop?.width, desktop?.height, viewSize) {
        if (desktop != null && viewSize != IntSize.Zero) {
            controller.layout(desktop.width, desktop.height, viewSize.width.toFloat(), viewSize.height.toFloat())
        }
    }
    // A finger resting long enough is a right click; the interpreter needs to be told the time passes.
    LaunchedEffect(interpreter) {
        while (true) {
            delay(LONG_PRESS_CHECK_MS)
            controller.apply(interpreter.timeout(SystemClock.uptimeMillis()))
        }
    }
    val cursorBitmap = remember(cursor) { cursor?.toBitmap() }
    val description = stringResource(R.string.desktop_description, session.title)

    Canvas(
        modifier = modifier
            .semantics { contentDescription = description }
            .onSizeChanged { viewSize = it }
            .pointerInput(interactive, interpreter) {
                if (!interactive) return@pointerInput
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        val changes = event.changes
                        if (changes.any { it.type == PointerType.Mouse }) {
                            val change = changes.first()
                            if (event.type == PointerEventType.Scroll) {
                                // A wheel reports whole steps; a touchpad may report fractions, each counted as one.
                                val delta = change.scrollDelta.y
                                if (delta != 0f) controller.scroll(delta.sign.toInt() * abs(delta).roundToInt().coerceAtLeast(1))
                            } else {
                                controller.mouse(change.position.x, change.position.y, event.buttons.toMask())
                            }
                            changes.forEach { it.consume() }
                            continue
                        }
                        for (change in changes) {
                            val id = change.id.value
                            val time = change.uptimeMillis
                            val x = change.position.x
                            val y = change.position.y
                            when {
                                change.changedToDownIgnoreConsumed() -> controller.apply(interpreter.down(id, x, y, time))
                                change.changedToUpIgnoreConsumed() -> controller.apply(interpreter.up(id, x, y, time))
                                change.pressed && change.positionChange() != Offset.Zero -> controller.apply(interpreter.move(id, x, y, time))
                            }
                            change.consume()
                        }
                    }
                }
            },
    ) {
        frame.version.intValue
        val bitmap = frame.bitmap ?: return@Canvas
        val viewport = controller.viewport
        val matrix = Matrix().apply {
            setScale(viewport.scale, viewport.scale)
            postTranslate(viewport.offsetX, viewport.offsetY)
        }
        // Smoothing helps when the screen is shrunk; enlarged, sharp pixels read better.
        frame.paint.isFilterBitmap = viewport.scale < 1f
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawBitmap(bitmap, matrix, frame.paint)
            val pointer = cursor
            val x = viewport.toScreenX(controller.pointerX)
            val y = viewport.toScreenY(controller.pointerY)
            if (pointer != null && cursorBitmap != null) {
                val cursorMatrix = Matrix().apply {
                    setTranslate(-pointer.hotspotX.toFloat(), -pointer.hotspotY.toFloat())
                    postScale(viewport.scale.coerceAtLeast(1f), viewport.scale.coerceAtLeast(1f))
                    postTranslate(x, y)
                }
                canvas.nativeCanvas.drawBitmap(cursorBitmap, cursorMatrix, frame.paint)
            }
        }
        if (cursor == null && state is DesktopState.Connected) {
            // Until the guest sends its pointer, a dot shows where it is.
            drawCircle(Color.White, radius = 6f, center = Offset(viewport.toScreenX(controller.pointerX), viewport.toScreenY(controller.pointerY)))
            drawCircle(Color.Black, radius = 3f, center = Offset(viewport.toScreenX(controller.pointerX), viewport.toScreenY(controller.pointerY)))
        }
    }
}

/** The local copy of the remote screen, refreshed where it changed. */
private class FrameBitmap {
    var bitmap: Bitmap? = null
        private set
    val version = mutableIntStateOf(0)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    fun refresh(session: DesktopSession) {
        val framebuffer = session.framebuffer ?: return
        val changed = framebuffer.withLock {
            if (width <= 0 || height <= 0) return@withLock false
            var target = bitmap
            val replaced = target == null || target.width != width || target.height != height
            if (replaced) {
                target = createBitmap(width, height)
                bitmap = target
            }
            val damage = session.takeDamage()
            val region = if (replaced) Region(0, 0, width, height) else damage?.clampedTo(width, height) ?: return@withLock false
            if (region.width <= 0 || region.height <= 0) return@withLock false
            target.setPixels(pixels, region.y * width + region.x, width, region.x, region.y, region.width, region.height)
            true
        }
        if (changed) version.intValue++
    }

    private fun Region.clampedTo(width: Int, height: Int): Region {
        val left = x.coerceIn(0, width)
        val top = y.coerceIn(0, height)
        return Region(left, top, (right.coerceAtMost(width) - left), (bottom.coerceAtMost(height) - top))
    }
}

private fun Cursor.toBitmap(): Bitmap? =
    if (width <= 0 || height <= 0) null else Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

private fun PointerButtons.toMask(): Int {
    var mask = 0
    if (isPrimaryPressed) mask = mask or MouseButton.Left.mask
    if (isTertiaryPressed) mask = mask or MouseButton.Middle.mask
    if (isSecondaryPressed) mask = mask or MouseButton.Right.mask
    return mask
}

@Composable
private fun Toolbar(
    session: DesktopSession,
    state: DesktopState,
    controller: DesktopController,
    showKeys: Boolean,
    onToggleKeys: () -> Unit,
    onKeyboard: () -> Unit,
    onPaste: () -> Unit,
    onPictureInPicture: (() -> Unit)?,
    onAbout: () -> Unit,
    onLeave: () -> Unit,
    onDisconnect: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val connected = state is DesktopState.Connected
    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.statusBarsPadding().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeave) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(session.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(stateLabel(state), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
            if (connected) {
                val trackpad = controller.mode == PointerMode.Trackpad
                IconButton(onClick = { controller.mode = if (trackpad) PointerMode.Touch else PointerMode.Trackpad }) {
                    Icon(
                        painterResource(if (trackpad) R.drawable.ic_mouse else R.drawable.ic_touch),
                        contentDescription = stringResource(if (trackpad) R.string.desktop_mode_trackpad else R.string.desktop_mode_touch),
                    )
                }
                IconButton(onClick = controller::toggleFit) {
                    Icon(
                        painterResource(R.drawable.ic_fit_screen),
                        contentDescription = stringResource(if (controller.fitting) R.string.desktop_actual_size else R.string.desktop_fit),
                    )
                }
                IconButton(onClick = onKeyboard) {
                    Icon(painterResource(R.drawable.ic_keyboard), contentDescription = stringResource(R.string.desktop_keyboard))
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.action_more))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    if (connected) {
                        DropdownMenuItem(
                            text = { Text(stringResource(if (showKeys) R.string.desktop_hide_keys else R.string.desktop_show_keys)) },
                            onClick = {
                                menuOpen = false
                                onToggleKeys()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.desktop_ctrl_alt_del)) },
                            onClick = {
                                menuOpen = false
                                controller.ctrlAltDelete()
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.desktop_paste)) },
                            onClick = {
                                menuOpen = false
                                onPaste()
                            },
                        )
                        if (onPictureInPicture != null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.desktop_picture_in_picture)) },
                                onClick = {
                                    menuOpen = false
                                    onPictureInPicture()
                                },
                            )
                        }
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.desktop_about)) },
                        onClick = {
                            menuOpen = false
                            onAbout()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.desktop_disconnect)) },
                        onClick = {
                            menuOpen = false
                            onDisconnect()
                        },
                    )
                }
            }
        }
    }
}

/** The keys a phone's keyboard lacks, for a desktop: modifiers, Esc, arrows, function keys. */
@Composable
private fun DesktopKeysBar(controller: DesktopController) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ModifierCap("CTRL", held = controller.ctrl) { controller.ctrl = !controller.ctrl }
            ModifierCap("ALT", held = controller.alt) { controller.alt = !controller.alt }
            ModifierCap("WIN", held = controller.win) { controller.win = !controller.win }
            DESKTOP_KEYS.forEach { (label, keysym, repeats) ->
                KeyCap(label, repeats = repeats) { controller.tap(keysym) }
            }
            KeyCap("CTRL+ALT+DEL", repeats = false) { controller.ctrlAltDelete() }
        }
    }
}

private val DESKTOP_KEYS: List<Triple<String, Int, Boolean>> = listOf(
    Triple("ESC", Keysyms.ESCAPE, false),
    Triple("TAB", Keysyms.TAB, false),
    Triple("←", Keysyms.LEFT, true),
    Triple("↓", Keysyms.DOWN, true),
    Triple("↑", Keysyms.UP, true),
    Triple("→", Keysyms.RIGHT, true),
    Triple("HOME", Keysyms.HOME, false),
    Triple("END", Keysyms.END, false),
    Triple("PGUP", Keysyms.PAGE_UP, true),
    Triple("PGDN", Keysyms.PAGE_DOWN, true),
    Triple("DEL", Keysyms.DELETE, true),
) + (1..12).map { Triple("F$it", Keysyms.function(it), false) }

@Composable
private fun ClosedCard(state: DesktopState.Closed, onReconnect: () -> Unit, onClose: () -> Unit, modifier: Modifier) {
    Surface(shape = MaterialTheme.shapes.large, modifier = modifier.padding(24.dp)) {
        Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stateLabel(state), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
                Button(onClick = onReconnect) { Text(stringResource(R.string.console_reconnect)) }
            }
        }
    }
}

@Composable
private fun stateLabel(state: DesktopState): String = when (state) {
    DesktopState.Connecting -> stringResource(R.string.console_connecting)
    is DesktopState.Connected -> stringResource(R.string.desktop_size, state.desktop.width, state.desktop.height)
    is DesktopState.Closed -> state.reason?.takeIf { it.isNotBlank() }?.let { stringResource(R.string.console_closed_reason, it) }
        ?: stringResource(R.string.console_closed)
}

/** Hides the status and navigation bars while the desktop is on screen. A swipe brings them back briefly. */
@Composable
private fun ImmersiveMode(activity: ComponentActivity?) {
    val view = LocalView.current
    DisposableEffect(activity) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
}

@Composable
private fun rememberPictureInPicture(activity: ComponentActivity?): Boolean {
    var inPictureInPicture by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { inPictureInPicture = it.isInPictureInPictureMode }
        activity?.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity?.removeOnPictureInPictureModeChangedListener(listener) }
    }
    return inPictureInPicture
}

private fun enterPictureInPicture(activity: ComponentActivity, state: DesktopState) {
    val params = pictureInPictureParams(state, sourceBounds = null) ?: return
    activity.enterPictureInPictureMode(params)
}

/**
 * While a desktop is connected and on screen, leaving the app shrinks it
 * into a picture-in-picture window instead of hiding it.
 */
@Composable
private fun AutoPictureInPicture(activity: ComponentActivity?, state: DesktopState, sourceBounds: android.graphics.Rect?) {
    if (activity == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    DisposableEffect(activity, state, sourceBounds) {
        pictureInPictureParams(state, sourceBounds, autoEnter = true)?.let(activity::setPictureInPictureParams)
        onDispose {
            activity.setPictureInPictureParams(PictureInPictureParams.Builder().setAutoEnterEnabled(false).build())
        }
    }
}

private fun pictureInPictureParams(state: DesktopState, sourceBounds: android.graphics.Rect?, autoEnter: Boolean = false): PictureInPictureParams? {
    val desktop = (state as? DesktopState.Connected)?.desktop ?: return null
    // Android allows aspect ratios between 1:2.39 and 2.39:1.
    val ratio = (desktop.width.toFloat() / desktop.height.coerceAtLeast(1)).coerceIn(1 / MAX_PIP_RATIO, MAX_PIP_RATIO)
    val builder = PictureInPictureParams.Builder()
        .setAspectRatio(Rational((ratio * 1000).roundToInt(), 1000))
        .setSourceRectHint(sourceBounds)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) builder.setAutoEnterEnabled(autoEnter)
    return builder.build()
}

private const val LONG_PRESS_CHECK_MS = 50L
private const val MAX_PIP_RATIO = 2.39f
