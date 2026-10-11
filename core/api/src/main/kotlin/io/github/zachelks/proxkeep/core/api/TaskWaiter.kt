package io.github.zachelks.proxkeep.core.api

import io.github.zachelks.proxkeep.core.api.model.TaskStatus
import kotlinx.coroutines.delay
import java.io.IOException

private const val MAX_CONSECUTIVE_FAILURES = 5

/**
 * Waits until a task stops and returns its final status.
 *
 * The task runs on the server whether or not anyone is watching, so brief
 * network trouble while waiting is ridden out: only
 * [MAX_CONSECUTIVE_FAILURES] failed checks in a row give up. An answer from
 * the server that is itself an error is never retried.
 *
 * @throws IOException when the status can no longer be read
 */
suspend fun PveClient.awaitTask(
    node: String,
    upid: String,
    pollIntervalMs: Long = 1_000,
): TaskStatus {
    var failures = 0
    while (true) {
        try {
            val status = taskStatus(node, upid)
            if (!status.isRunning) return status
            failures = 0
        } catch (e: PveApiException) {
            throw e
        } catch (e: IOException) {
            failures++
            if (failures >= MAX_CONSECUTIVE_FAILURES) throw e
        }
        delay(pollIntervalMs)
    }
}
