package io.github.zachelks.proxkeep.core.api.model

import io.github.zachelks.proxkeep.core.api.json.LenientIntSerializer
import io.github.zachelks.proxkeep.core.api.json.LenientLongSerializer
import kotlinx.serialization.Serializable

private const val EXIT_OK = "OK"
private const val EXIT_WARNINGS = "WARNINGS"

/**
 * One row of `GET /cluster/tasks`: a recent or running background job, such
 * as starting a guest or taking a backup.
 *
 * @property upid the task's unique ID
 * @property type the kind of job, for example `qmstart` or `vzdump`
 * @property id what the job acts on, usually a guest ID
 * @property starttime seconds since the epoch
 * @property endtime seconds since the epoch; absent while the task runs
 * @property status `OK`, a warning count, or an error message; absent while the task runs
 */
@Serializable
data class TaskSummary(
    val upid: String,
    val node: String,
    val type: String,
    val id: String? = null,
    val user: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val starttime: Long? = null,
    @Serializable(with = LenientLongSerializer::class)
    val endtime: Long? = null,
    val status: String? = null,
) {
    val isRunning: Boolean
        get() = endtime == null && status == null

    val succeeded: Boolean
        get() = status == EXIT_OK

    val hasWarnings: Boolean
        get() = status?.startsWith(EXIT_WARNINGS) == true

    val failed: Boolean
        get() = !isRunning && !succeeded && !hasWarnings
}

/** Response of `GET /nodes/{node}/tasks/{upid}/status`. */
@Serializable
data class TaskStatus(
    /** `running` or `stopped`. */
    val status: String,
    /** `OK`, a warning count, or an error message. Absent while the task runs. */
    val exitstatus: String? = null,
    val type: String? = null,
    val id: String? = null,
    val user: String? = null,
    val node: String? = null,
    @Serializable(with = LenientLongSerializer::class)
    val starttime: Long? = null,
) {
    val isRunning: Boolean
        get() = status == "running"

    /** True when the task finished and did what it was asked, warnings included. */
    val succeeded: Boolean
        get() = !isRunning && (exitstatus == EXIT_OK || exitstatus?.startsWith(EXIT_WARNINGS) == true)
}

/**
 * One line of a task's log.
 *
 * @property n the line number, starting at 1
 * @property t the text
 */
@Serializable
data class TaskLogLine(
    @Serializable(with = LenientIntSerializer::class)
    val n: Int,
    val t: String = "",
)
