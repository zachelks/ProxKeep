package io.github.zachelks.proxkeep.console

import android.content.Context
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.toColorInt
import com.termux.terminal.TerminalColors
import io.github.zachelks.proxkeep.R
import java.util.Properties

/** The terminal's typeface. */
enum class TerminalFont {
    /** The device's own monospace font. */
    System,

    /** JetBrains Mono, bundled with the app under the SIL Open Font License. */
    JetBrainsMono,
    ;

    fun typeface(context: Context): Typeface = when (this) {
        System -> Typeface.MONOSPACE
        JetBrainsMono -> ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
    }
}

/**
 * The terminal's colors: background, foreground and the 16 ANSI colors that
 * programs pick from. Each is a well-known published scheme.
 *
 * @property ansi colors 0 to 15, as `#rrggbb`
 */
enum class TerminalPalette(val background: String, val foreground: String, val ansi: List<String>) {
    /** Termux's own colors: light text on black. */
    Default(
        background = "#000000",
        foreground = "#ffffff",
        ansi = listOf(
            "#000000", "#cd0000", "#00cd00", "#cdcd00", "#6495ed", "#cd00cd", "#00cdcd", "#e5e5e5",
            "#7f7f7f", "#ff0000", "#00ff00", "#ffff00", "#5c5cff", "#ff00ff", "#00ffff", "#ffffff",
        ),
    ),
    SolarizedDark(
        background = "#002b36",
        foreground = "#839496",
        ansi = SOLARIZED,
    ),
    SolarizedLight(
        background = "#fdf6e3",
        foreground = "#657b83",
        ansi = SOLARIZED,
    ),
    Dracula(
        background = "#282a36",
        foreground = "#f8f8f2",
        ansi = listOf(
            "#21222c", "#ff5555", "#50fa7b", "#f1fa8c", "#bd93f9", "#ff79c6", "#8be9fd", "#f8f8f2",
            "#6272a4", "#ff6e6e", "#69ff94", "#ffffa5", "#d6acff", "#ff92df", "#a4ffff", "#ffffff",
        ),
    ),
    GruvboxDark(
        background = "#282828",
        foreground = "#ebdbb2",
        ansi = listOf(
            "#282828", "#cc241d", "#98971a", "#d79921", "#458588", "#b16286", "#689d6a", "#a89984",
            "#928374", "#fb4934", "#b8bb26", "#fabd2f", "#83a598", "#d3869b", "#8ec07c", "#ebdbb2",
        ),
    ),
    TangoLight(
        background = "#ffffff",
        foreground = "#2e3436",
        ansi = listOf(
            "#2e3436", "#cc0000", "#4e9a06", "#c4a000", "#3465a4", "#75507b", "#06989a", "#d3d7cf",
            "#555753", "#ef2929", "#8ae234", "#fce94f", "#729fcf", "#ad7fa8", "#34e2e2", "#eeeeec",
        ),
    ),
    ;

    /** [background] as a color int. */
    val backgroundColor: Int
        get() = background.toColorInt()

    /** Makes this the colors of every terminal, including those already open once [ConsoleManager.refreshColors] runs. */
    fun applyToTerminals() {
        val properties = Properties().apply {
            setProperty("background", background)
            setProperty("foreground", foreground)
            ansi.forEachIndexed { index, color -> setProperty("color$index", color) }
        }
        // Termux keeps one scheme for every terminal. The cursor follows the background's brightness.
        TerminalColors.COLOR_SCHEME.updateWith(properties)
    }
}

private val SOLARIZED = listOf(
    "#073642", "#dc322f", "#859900", "#b58900", "#268bd2", "#d33682", "#2aa198", "#eee8d5",
    "#002b36", "#cb4b16", "#586e75", "#657b83", "#839496", "#6c71c4", "#93a1a1", "#fdf6e3",
)
