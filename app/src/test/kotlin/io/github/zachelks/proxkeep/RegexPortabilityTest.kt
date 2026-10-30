package io.github.zachelks.proxkeep

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Android compiles regular expressions with ICU, which refuses a `{` or `}`
 * that is neither escaped nor part of a quantifier such as `{2}`. The JVM
 * that runs these tests accepts them, so a pattern like `\{(\w+)}` passes
 * every other test and crashes on a phone. This reads every pattern in the
 * app's and the API module's sources and holds it to ICU's rule.
 */
class RegexPortabilityTest {

    @Test
    fun everyPatternInTheSourcesCompilesOnAndroid() {
        val roots = listOf(File("src/main/kotlin"), File("../core/api/src/main/kotlin"))
        roots.forEach { assertTrue(it.isDirectory, "sources at ${it.absolutePath}") }
        val problems = roots.flatMap { root -> root.walk().filter { it.extension == "kt" }.toList() }.flatMap { file ->
            patterns(file.readText()).mapNotNull { pattern -> icuProblem(pattern)?.let { "${file.name}: $pattern ($it)" } }
        }
        assertEquals(emptyList(), problems)
    }

    @Test
    fun theCheckCatchesWhatAndroidRefuses() {
        assertEquals("unescaped } at 9", icuProblem("""\{([^}]+)}"""))
        assertEquals("unescaped { at 0", icuProblem("""{name}"""))
        assertEquals(null, icuProblem("""\{([^}]+)\}"""))
        assertEquals(null, icuProblem("""RFB (\d{3})\.(\d{3})"""))
        assertEquals(null, icuProblem(""":\d{1,5}$"""))
        assertEquals(listOf("""\{([^}]+)}""", """a{2}"""), patterns("Regex(\"\\\\{([^}]+)}\") + Regex(\"\"\"a{2}\"\"\")"))
    }

    /** The patterns of every `Regex(…)` and `toRegex()` with a literal string, as the regex engine sees them. */
    private fun patterns(source: String): List<String> {
        val raw = Regex("\"\"\"(.*?)\"\"\"(?=\\)|\\.toRegex)").findAll(source)
            .filter { source.regionMatches(it.range.first - 6, "Regex(", 0, 6) || source.startsWith(".toRegex", it.range.last + 1) }
            .map { it.groupValues[1] }
        val plain = Regex("(?:Regex\\()\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(source)
            .filterNot { source.startsWith("\"\"", it.range.first + 7) }
            .map { unescapeKotlin(it.groupValues[1]) }
        return (plain + raw).toList()
    }

    private fun unescapeKotlin(text: String): String = buildString {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c == '\\' && i + 1 < text.length) {
                append(
                    when (val next = text[i + 1]) {
                        'n' -> '\n'
                        't' -> '\t'
                        else -> next
                    },
                )
                i += 2
            } else {
                append(c)
                i++
            }
        }
    }

    /** Why ICU would refuse [pattern]'s braces, or null when it reads them. */
    private fun icuProblem(pattern: String): String? {
        val quantifier = Regex("""\{\d+(,\d*)?\}""")
        var i = 0
        var inClass = false
        while (i < pattern.length) {
            when (val c = pattern[i]) {
                '\\' -> i++
                '[' -> inClass = true
                ']' -> inClass = false
                '{' -> if (!inClass) {
                    val match = quantifier.matchAt(pattern, i) ?: return "unescaped { at $i"
                    i = match.range.last
                }
                '}' -> if (!inClass) return "unescaped } at $i"
                else -> Unit
            }
            i++
        }
        return null
    }
}
