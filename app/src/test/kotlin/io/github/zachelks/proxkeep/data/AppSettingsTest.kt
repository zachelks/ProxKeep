package io.github.zachelks.proxkeep.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.console.TerminalFont
import io.github.zachelks.proxkeep.console.TerminalKey
import io.github.zachelks.proxkeep.console.TerminalPalette
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    @Test
    fun theLockIsOffByDefault() {
        val settings = AppSettings(context)

        assertFalse(settings.lockEnabled.value)
        assertEquals(LockTimeout.OneMinute, settings.lockTimeout.value)
    }

    @Test
    fun changesAreSavedAndPublished() {
        val settings = AppSettings(context)

        settings.setLockEnabled(true)
        settings.setLockTimeout(LockTimeout.FifteenMinutes)

        assertTrue(settings.lockEnabled.value)
        assertEquals(LockTimeout.FifteenMinutes, settings.lockTimeout.value)
        val reopened = AppSettings(context)
        assertTrue(reopened.lockEnabled.value)
        assertEquals(LockTimeout.FifteenMinutes, reopened.lockTimeout.value)
    }

    @Test
    fun anUnknownTimeoutFallsBackToTheDefault() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true) {
            putInt("app_lock_timeout_seconds", 42)
        }

        assertEquals(LockTimeout.Default, AppSettings(context).lockTimeout.value)
    }

    @Test
    fun theExtraKeysRowIsSavedInOrder() {
        assertEquals(TerminalKey.DEFAULT_ROW, AppSettings(context).extraKeys.value)

        AppSettings(context).setExtraKeys(listOf(TerminalKey.F2, TerminalKey.ESC, TerminalKey.F2))

        assertEquals(listOf(TerminalKey.F2, TerminalKey.ESC), AppSettings(context).extraKeys.value)
    }

    @Test
    fun anEmptyRowStaysEmpty() {
        AppSettings(context).setExtraKeys(emptyList())

        assertEquals(emptyList(), AppSettings(context).extraKeys.value)
    }

    @Test
    fun terminalAppearanceIsSaved() {
        val settings = AppSettings(context)
        assertEquals(DEFAULT_TEXT_SIZE_SP, settings.terminalTextSize.value)
        assertEquals(TerminalFont.System, settings.terminalFont.value)
        assertEquals(TerminalPalette.Default, settings.terminalPalette.value)

        settings.setTerminalTextSize(18)
        settings.setTerminalFont(TerminalFont.JetBrainsMono)
        settings.setTerminalPalette(TerminalPalette.Dracula)

        val reopened = AppSettings(context)
        assertEquals(18, reopened.terminalTextSize.value)
        assertEquals(TerminalFont.JetBrainsMono, reopened.terminalFont.value)
        assertEquals(TerminalPalette.Dracula, reopened.terminalPalette.value)
    }

    @Test
    fun theTextSizeStaysInRange() {
        val settings = AppSettings(context)

        settings.setTerminalTextSize(200)
        assertEquals(TERMINAL_TEXT_SIZES_SP.last, settings.terminalTextSize.value)
        settings.setTerminalTextSize(1)
        assertEquals(TERMINAL_TEXT_SIZES_SP.first, settings.terminalTextSize.value)
    }

    @Test
    fun timeoutsConvertBetweenSecondsAndMillis() {
        assertEquals(LockTimeout.Immediately, LockTimeout.fromSeconds(0))
        assertEquals(LockTimeout.FiveMinutes, LockTimeout.fromSeconds(300))
        assertEquals(900_000L, LockTimeout.FifteenMinutes.millis)
    }
}
