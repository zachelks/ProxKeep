package io.github.zachelks.proxkeep.data

import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash's stack trace on the device, so the person can share
 * it from Settings. Nothing leaves the device unless they share it.
 *
 * @param file where the trace is kept; only the latest crash is
 * @param now the time to stamp a crash with, in milliseconds since the epoch
 */
class CrashLog(private val file: File, private val now: () -> Long = System::currentTimeMillis) {

    /** Records crashes from any thread, then lets the system end the app as usual. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(threadName: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        try {
            file.writeText("time=${now()}\nthread=$threadName\n\n$trace")
        } catch (ignored: Exception) {
            // The app is already crashing; a trace that cannot be written is lost.
        }
    }

    /** The last crash's report, or null when there is none. */
    fun read(): String? = file.takeIf { it.isFile }?.readText()?.takeIf { it.isNotBlank() }

    fun clear() {
        file.delete()
    }
}
