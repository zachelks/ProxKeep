package io.github.zachelks.proxkeep

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import org.robolectric.shadows.ShadowLooper

/**
 * Waits for [condition] while running the main looper.
 *
 * View model coroutines resume on the main looper after each network reply.
 * The v2 compose rule's own wait does not always run the looper once Compose
 * is idle, so a reply can sit there unread until the wait times out.
 */
fun ComposeContentTestRule.waitWithLooper(timeoutMs: Long = 30_000, condition: () -> Boolean) = waitUntil(timeoutMs) {
    ShadowLooper.idleMainLooper()
    condition()
}
