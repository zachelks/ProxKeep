package io.github.zachelks.proxkeep.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The keys of the extra-keys rows of the terminal and the remote desktop.

/** A sticky modifier key, such as Ctrl, shown held while it applies. */
@Composable
fun ModifierCap(label: String, held: Boolean, onToggle: () -> Unit) {
    CapSurface(label, held, Modifier.toggleable(value = held, role = Role.Switch, onValueChange = { onToggle() }))
}

/** A key that acts on press, and again and again while held if it [repeats]. */
@Composable
fun KeyCap(label: String, repeats: Boolean, onPress: () -> Unit) {
    val press by rememberUpdatedState(onPress)
    var pressed by remember { mutableStateOf(false) }
    CapSurface(
        label = label,
        held = pressed,
        modifier = Modifier
            .semantics {
                role = Role.Button
                onClick {
                    press()
                    true
                }
            }
            .pointerInput(repeats) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        press()
                        coroutineScope {
                            val repeating = if (repeats) {
                                launch {
                                    delay(REPEAT_DELAY_MS)
                                    while (true) {
                                        press()
                                        delay(REPEAT_INTERVAL_MS)
                                    }
                                }
                            } else {
                                null
                            }
                            tryAwaitRelease()
                            repeating?.cancel()
                        }
                        pressed = false
                    },
                )
            },
    )
}

@Composable
private fun CapSurface(label: String, held: Boolean, modifier: Modifier) {
    val background = if (held) MaterialTheme.colorScheme.primary else Color.Transparent
    val foreground = if (held) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 44.dp, minHeight = 40.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .then(modifier)
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

private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 50L
