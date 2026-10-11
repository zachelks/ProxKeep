package io.github.zachelks.proxkeep.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides when the app is locked.
 *
 * The app starts locked whenever the lock is switched on, and locks again
 * after it has been in the background for at least the chosen time. It never
 * unlocks by itself: only [unlock], called after the person has
 * authenticated, does that.
 *
 * All calls must come from the main thread.
 *
 * @param isEnabled whether the person has switched the lock on
 * @param timeoutMs how long the app may be in the background before it locks
 * @param now a clock that keeps counting while the device sleeps
 */
class AppLock(
    private val isEnabled: () -> Boolean,
    private val timeoutMs: () -> Long,
    private val now: () -> Long,
) {
    private val _locked = MutableStateFlow(isEnabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var backgroundedAt: Long? = null
    private var authenticating = false

    /** The app is no longer visible. */
    fun onBackground() {
        // On some Android versions the system's own unlock screen covers the
        // app completely. That is not the person leaving. A prompt excuses
        // one absence only, so a prompt that never reports back cannot keep
        // the app from locking later.
        if (authenticating) {
            authenticating = false
            return
        }
        if (!_locked.value) backgroundedAt = now()
    }

    /** The app is visible again. */
    fun onForeground() {
        val since = backgroundedAt ?: return
        backgroundedAt = null
        if (isEnabled() && now() - since >= timeoutMs()) _locked.value = true
    }

    /** Call when a system authentication prompt is about to be shown. */
    fun onAuthenticationStarted() {
        authenticating = true
    }

    /** Call when the prompt has gone, whatever the outcome. */
    fun onAuthenticationFinished() {
        authenticating = false
    }

    /** Call only after the person has authenticated. */
    fun unlock() {
        backgroundedAt = null
        _locked.value = false
    }
}
