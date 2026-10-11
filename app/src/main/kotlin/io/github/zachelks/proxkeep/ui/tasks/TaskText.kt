package io.github.zachelks.proxkeep.ui.tasks

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.TaskSummary
import io.github.zachelks.proxkeep.ui.cluster.StatusText
import io.github.zachelks.proxkeep.ui.theme.StatusTone

/** Task types whose title names what they act on, usually a guest ID. */
private val titlesWithSubject = mapOf(
    "qmstart" to R.string.task_qmstart,
    "qmstop" to R.string.task_qmstop,
    "qmshutdown" to R.string.task_qmshutdown,
    "qmreboot" to R.string.task_qmreboot,
    "qmreset" to R.string.task_qmreset,
    "qmpause" to R.string.task_qmpause,
    "qmsuspend" to R.string.task_qmsuspend,
    "qmresume" to R.string.task_qmresume,
    "qmcreate" to R.string.task_qmcreate,
    "qmdestroy" to R.string.task_qmdestroy,
    "qmclone" to R.string.task_qmclone,
    "qmigrate" to R.string.task_qmigrate,
    "qmrestore" to R.string.task_qmrestore,
    "qmsnapshot" to R.string.task_qmsnapshot,
    "qmrollback" to R.string.task_qmrollback,
    "qmdelsnapshot" to R.string.task_qmdelsnapshot,
    "vzstart" to R.string.task_vzstart,
    "vzstop" to R.string.task_vzstop,
    "vzshutdown" to R.string.task_vzshutdown,
    "vzreboot" to R.string.task_vzreboot,
    "vzsuspend" to R.string.task_vzsuspend,
    "vzresume" to R.string.task_vzresume,
    "vzcreate" to R.string.task_vzcreate,
    "vzdestroy" to R.string.task_vzdestroy,
    "vzclone" to R.string.task_vzclone,
    "vzmigrate" to R.string.task_vzmigrate,
    "vzrestore" to R.string.task_vzrestore,
    "vzsnapshot" to R.string.task_vzsnapshot,
    "vzrollback" to R.string.task_vzrollback,
    "vzdelsnapshot" to R.string.task_vzdelsnapshot,
    "vncproxy" to R.string.task_vncproxy,
    "spiceproxy" to R.string.task_vncproxy,
    "srvstart" to R.string.task_srvstart,
    "srvstop" to R.string.task_srvstop,
    "srvrestart" to R.string.task_srvrestart,
    "srvreload" to R.string.task_srvreload,
    "download" to R.string.task_download,
)

/** Task types that act on the node or cluster as a whole. */
private val titlesWithoutSubject = mapOf(
    "vncshell" to R.string.task_vncshell,
    "startall" to R.string.task_startall,
    "stopall" to R.string.task_stopall,
    "aptupdate" to R.string.task_aptupdate,
)

/** A readable title for a task, falling back to the server's own type name. */
@Composable
fun taskTitle(type: String, id: String?): String {
    val subject = id?.takeIf { it.isNotBlank() }
    val withoutSubject = titlesWithoutSubject[type]
    val withSubject = titlesWithSubject[type]
    return when {
        type == "vzdump" && subject != null -> stringResource(R.string.task_vzdump, subject)
        type == "vzdump" -> stringResource(R.string.task_vzdump_job)
        withoutSubject != null -> stringResource(withoutSubject)
        withSubject != null && subject != null -> stringResource(withSubject, subject)
        else -> listOfNotNull(type, subject).joinToString(" ")
    }
}

@Composable
fun TaskSummary.statusText(): StatusText = when {
    isRunning -> StatusText(stringResource(R.string.task_status_running), StatusTone.Good)
    succeeded -> StatusText(stringResource(R.string.task_status_ok), StatusTone.Neutral)
    hasWarnings -> StatusText(stringResource(R.string.task_status_warnings), StatusTone.Warning)
    else -> StatusText(stringResource(R.string.task_status_failed), StatusTone.Bad)
}

/** A date and time in the device's own format, from seconds since the epoch. */
@Composable
fun formatTaskTime(epochSeconds: Long): String = DateUtils.formatDateTime(
    LocalContext.current,
    epochSeconds * 1000L,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
)
