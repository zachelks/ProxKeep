package io.github.zachelks.proxkeep.data

import java.io.File

/**
 * Keeps the last crash's report on the device, so the person can share it
 * from Settings. Nothing leaves the device unless they share it.
 *
 * A full stack trace runs to hundreds of lines of framework code, too long
 * to paste into a message, so the report keeps what finds the bug: each
 * exception in the chain with its message, the app's own frames, and the
 * first few frames where each exception was thrown.
 *
 * @param file where the report is kept; only the latest crash is
 * @param about the app version and device, written at the top of the report
 * @param now the time to stamp a crash with, in milliseconds since the epoch
 */
class CrashLog(
    private val file: File,
    private val about: () -> String = { "" },
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Records crashes from any thread, then lets the system end the app as usual. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(threadName: String, error: Throwable) {
        try {
            file.writeText(report(threadName, error))
        } catch (ignored: Exception) {
            // The app is already crashing; a report that cannot be written is lost.
        }
    }

    /** The last crash's report, or null when there is none. */
    fun read(): String? = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() {
        file.delete()
    }

    private fun report(threadName: String, error: Throwable): String = buildString {
        about().takeIf { it.isNotBlank() }?.let { appendLine(it) }
        appendLine("time=${now()} thread=$threadName")
        var cause: Throwable? = error
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (cause != null && depth < MAX_CAUSES && seen.add(cause)) {
            if (depth > 0) append("Caused by: ")
            appendLine("${cause.javaClass.name}: ${cause.message.orEmpty().take(MAX_MESSAGE)}")
            var skipped = 0
            cause.stackTrace.forEachIndexed { index, frame ->
                if (index < THROW_SITE_FRAMES || frame.className.startsWith(APP_PACKAGE)) {
                    if (skipped > 0) appendLine("    … $skipped more")
                    skipped = 0
                    appendLine("    at $frame")
                } else {
                    skipped++
                }
            }
            if (skipped > 0) appendLine("    … $skipped more")
            cause = cause.cause
            depth++
        }
    }

    private companion object {
        const val APP_PACKAGE = "io.github.zachelks.proxkeep"
        const val THROW_SITE_FRAMES = 4
        const val MAX_CAUSES = 6
        const val MAX_MESSAGE = 500
    }
}
