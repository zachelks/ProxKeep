package io.github.zachelks.proxkeep.data

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogTest {

    private val dir = Files.createTempDirectory("crash").toFile()
    private val log = CrashLog(dir.resolve("last-crash.txt"), about = { "ProxKeep 1.0, Android 16" }, now = { 1_700_000_000_000 })

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun theLastCrashIsKeptUntilCleared() {
        assertNull(log.read())

        log.record("main", IllegalStateException("first"))
        log.record("main", IllegalArgumentException("Key \"a\" was already used"))

        val report = log.read()!!
        assertTrue(report.startsWith("ProxKeep 1.0, Android 16\ntime=1700000000000 thread=main\n"))
        assertTrue("IllegalArgumentException: Key \"a\" was already used" in report, "only the latest crash is kept")
        assertTrue("first" !in report)
        assertTrue("CrashLogTest" in report, "the stack trace names where it happened")

        log.clear()
        assertEquals(null, log.read())
    }

    @Test
    fun aDeepTraceIsCutToWhatFindsTheBug() {
        val framework = (1..200).map { StackTraceElement("androidx.compose.Runtime", "step$it", "Runtime.kt", it) }
        val app = StackTraceElement("io.github.zachelks.proxkeep.ui.SomeScreen", "draw", "SomeScreen.kt", 42)
        val inner = IllegalStateException("inner").apply { stackTrace = (framework.take(10) + app).toTypedArray() }
        val outer = RuntimeException("outer", inner).apply { stackTrace = (framework + app + framework).toTypedArray() }

        log.record("main", outer)

        val report = log.read()!!
        assertTrue("SomeScreen.draw(SomeScreen.kt:42)" in report, "the app's own frames are kept")
        assertTrue("Caused by: java.lang.IllegalStateException: inner" in report)
        assertTrue("step1(" in report, "where it was thrown is kept")
        assertTrue("step100(" !in report, "framework frames in between are left out")
        assertTrue(report.lines().size < 30, "short enough to paste: ${report.lines().size} lines")
    }
}
