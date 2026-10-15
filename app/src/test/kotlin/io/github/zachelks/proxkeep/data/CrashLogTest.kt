package io.github.zachelks.proxkeep.data

import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CrashLogTest {

    private val dir = Files.createTempDirectory("crash").toFile()
    private val log = CrashLog(dir.resolve("last-crash.txt")) { 1_700_000_000_000 }

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
        assertTrue(report.startsWith("time=1700000000000\nthread=main\n"))
        assertTrue("IllegalArgumentException: Key \"a\" was already used" in report, "only the latest crash is kept")
        assertTrue("first" !in report)
        assertTrue("CrashLogTest" in report, "the stack trace names where it happened")

        log.clear()
        assertEquals(null, log.read())
    }
}
