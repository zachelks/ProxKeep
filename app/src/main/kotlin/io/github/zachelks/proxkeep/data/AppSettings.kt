package io.github.zachelks.proxkeep.data

import android.content.Context
import androidx.core.content.edit
import io.github.zachelks.proxkeep.console.TerminalFont
import io.github.zachelks.proxkeep.console.TerminalKey
import io.github.zachelks.proxkeep.console.TerminalPalette
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

/** The text sizes the terminal can take, in sp. */
val TERMINAL_TEXT_SIZES_SP = 7..36

const val DEFAULT_TEXT_SIZE_SP = 14

/** The app's own preferences, kept in app-private storage. */
class AppSettings(context: Context) {
    private val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _lockEnabled = MutableStateFlow(preferences.getBoolean(KEY_LOCK_ENABLED, false))
    val lockEnabled: StateFlow<Boolean> = _lockEnabled.asStateFlow()

    private val _lockTimeout = MutableStateFlow(
        LockTimeout.fromSeconds(preferences.getInt(KEY_LOCK_TIMEOUT, LockTimeout.Default.seconds)),
    )
    val lockTimeout: StateFlow<LockTimeout> = _lockTimeout.asStateFlow()

    private val _extraKeys = MutableStateFlow(
        preferences.getString(KEY_EXTRA_KEYS, null)?.let(TerminalKey::parse) ?: TerminalKey.DEFAULT_ROW,
    )

    /** The terminal's extra-keys row, in order. Empty hides the row. */
    val extraKeys: StateFlow<List<TerminalKey>> = _extraKeys.asStateFlow()

    fun setExtraKeys(row: List<TerminalKey>) {
        val distinct = row.distinct()
        preferences.edit { putString(KEY_EXTRA_KEYS, TerminalKey.encode(distinct)) }
        _extraKeys.value = distinct
    }

    private val _terminalTextSize = MutableStateFlow(
        preferences.getInt(KEY_TEXT_SIZE, DEFAULT_TEXT_SIZE_SP).coerceIn(TERMINAL_TEXT_SIZES_SP),
    )

    /** The terminal's text size in sp. Pinching the terminal changes it too. */
    val terminalTextSize: StateFlow<Int> = _terminalTextSize.asStateFlow()

    fun setTerminalTextSize(sizeSp: Int) {
        val size = sizeSp.coerceIn(TERMINAL_TEXT_SIZES_SP)
        if (size == _terminalTextSize.value) return
        preferences.edit { putInt(KEY_TEXT_SIZE, size) }
        _terminalTextSize.value = size
    }

    private val _terminalFont = MutableStateFlow(
        TerminalFont.entries.firstOrNull { it.name == preferences.getString(KEY_FONT, null) } ?: TerminalFont.System,
    )
    val terminalFont: StateFlow<TerminalFont> = _terminalFont.asStateFlow()

    fun setTerminalFont(font: TerminalFont) {
        preferences.edit { putString(KEY_FONT, font.name) }
        _terminalFont.value = font
    }

    private val _terminalPalette = MutableStateFlow(
        TerminalPalette.entries.firstOrNull { it.name == preferences.getString(KEY_PALETTE, null) } ?: TerminalPalette.Default,
    )
    val terminalPalette: StateFlow<TerminalPalette> = _terminalPalette.asStateFlow()

    fun setTerminalPalette(palette: TerminalPalette) {
        preferences.edit { putString(KEY_PALETTE, palette.name) }
        _terminalPalette.value = palette
    }

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
        const val KEY_EXTRA_KEYS = "terminal_extra_keys"
        const val KEY_TEXT_SIZE = "terminal_text_size_sp"
        const val KEY_FONT = "terminal_font"
        const val KEY_PALETTE = "terminal_palette"
    }
}
