package io.github.zachelks.proxkeep.console

import android.content.Context
import android.graphics.Typeface
import androidx.test.core.app.ApplicationProvider
import com.termux.terminal.TerminalColors
import com.termux.terminal.TextStyle
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

@RunWith(RobolectricTestRunner::class)
class TerminalAppearanceTest {

    @AfterTest
    fun tearDown() {
        TerminalPalette.Default.applyToTerminals()
    }

    @Test
    fun aPaletteBecomesEveryTerminalsColors() {
        TerminalPalette.Dracula.applyToTerminals()

        val colors = TerminalColors.COLOR_SCHEME.mDefaultColors
        assertEquals(0xff282a36.toInt(), colors[TextStyle.COLOR_INDEX_BACKGROUND])
        assertEquals(0xfff8f8f2.toInt(), colors[TextStyle.COLOR_INDEX_FOREGROUND])
        assertEquals(0xffff5555.toInt(), colors[1])
        assertEquals(0xffffffff.toInt(), colors[TextStyle.COLOR_INDEX_CURSOR], "a light cursor on a dark background")
    }

    @Test
    fun aLightPaletteGetsADarkCursor() {
        TerminalPalette.SolarizedLight.applyToTerminals()

        assertEquals(0xff000000.toInt(), TerminalColors.COLOR_SCHEME.mDefaultColors[TextStyle.COLOR_INDEX_CURSOR])
    }

    @Test
    fun everyPaletteIsComplete() {
        TerminalPalette.entries.forEach { palette ->
            assertEquals(16, palette.ansi.size, palette.name)
            palette.applyToTerminals()
        }
    }

    @Test
    fun theBundledFontLoads() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        assertEquals(Typeface.MONOSPACE, TerminalFont.System.typeface(context))
        assertNotSame(Typeface.MONOSPACE, TerminalFont.JetBrainsMono.typeface(context))
    }
}
