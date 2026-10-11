package io.github.zachelks.proxkeep.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** How long the app may stay in the background before it locks. */
enum class LockTimeout(val seconds: Int) {
    Immediately(0),
    OneMinute(60),
    FiveMinutes(300),
    FifteenMinutes(900),
    ;

    val millis: Long
        get() = seconds * 1000L

    companion object {
        val Default = OneMinute

        fun fromSeconds(seconds: Int): LockTimeout = entries.firstOrNull { it.seconds == seconds } ?: Default
    }
}

/** The app's own preferences, kept in app-private storage. */
class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lockEnabled = MutableStateFlow(preferences.getBoolean(KEY_LOCK_ENABLED, false))
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _lockTimeout = MutableStateFlow(
        LockTimeout.fromSeconds(preferences.getInt(KEY_LOCK_TIMEOUT, LockTimeout.Default.seconds)),
    )
    val lockTimeout: StateFlow<LockTimeout> = _lockTimeout.asStateFlow()

    fun setLockEnabled(enabled: Boolean) {
        preferences.edit { putBoolean(KEY_LOCK_ENABLED, enabled) }
        _lockEnabled.value = enabled
    }

    fun setLockTimeout(timeout: LockTimeout) {
        preferences.edit { putInt(KEY_LOCK_TIMEOUT, timeout.seconds) }
        _lockTimeout.value = timeout
    }

    private companion object {
        const val KEY_LOCK_ENABLED = "app_lock_enabled"
        const val KEY_LOCK_TIMEOUT = "app_lock_timeout_seconds"
    }
}
