package io.github.zachelks.proxkeep.ui.guests

import android.content.res.Resources
import androidx.annotation.StringRes
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.GuestAction
import io.github.zachelks.proxkeep.data.ActionEvent

/** The words that go with one power action. */
private class ActionText(
    @StringRes val label: Int,
    @StringRes val inProgress: Int,
    @StringRes val done: Int,
    @StringRes val failed: Int,
    /** Null for actions that need no confirmation. */
    @StringRes val confirmTitle: Int? = null,
    @StringRes val confirmBody: Int? = null,
)

private fun GuestAction.text(): ActionText = when (this) {
    GuestAction.Start -> ActionText(
        R.string.action_start, R.string.progress_start, R.string.done_start, R.string.failed_start,
    )
    GuestAction.Resume -> ActionText(
        R.string.action_resume, R.string.progress_resume, R.string.done_resume, R.string.failed_resume,
    )
    GuestAction.Pause -> ActionText(
        R.string.action_pause, R.string.progress_pause, R.string.done_pause, R.string.failed_pause,
    )
    GuestAction.Shutdown -> ActionText(
        R.string.action_shutdown, R.string.progress_shutdown, R.string.done_shutdown, R.string.failed_shutdown,
        R.string.confirm_shutdown_title, R.string.confirm_shutdown_body,
    )
    GuestAction.Reboot -> ActionText(
        R.string.action_reboot, R.string.progress_reboot, R.string.done_reboot, R.string.failed_reboot,
        R.string.confirm_reboot_title, R.string.confirm_reboot_body,
    )
    GuestAction.Stop -> ActionText(
        R.string.action_stop, R.string.progress_stop, R.string.done_stop, R.string.failed_stop,
        R.string.confirm_stop_title, R.string.confirm_stop_body,
    )
    GuestAction.Reset -> ActionText(
        R.string.action_reset, R.string.progress_reset, R.string.done_reset, R.string.failed_reset,
        R.string.confirm_reset_title, R.string.confirm_reset_body,
    )
}

@get:StringRes
val GuestAction.label: Int
    get() = text().label

@get:StringRes
val GuestAction.inProgressLabel: Int
    get() = text().inProgress

/** Title of the confirmation dialog, or null when the action runs without asking. */
@get:StringRes
val GuestAction.confirmTitle: Int?
    get() = text().confirmTitle

@get:StringRes
val GuestAction.confirmBody: Int?
    get() = text().confirmBody

/** One line saying how a power action ended, with the server's reason when it failed. */
fun ActionEvent.message(resources: Resources): String {
    val text = action.text()
    if (succeeded) return resources.getString(text.done, guestName)
    val reason = detail?.trim().orEmpty()
    // The server gives a guest a limited time to answer a shutdown. Many still shut down after that.
    if (action in GRACEFUL && reason.contains("timeout", ignoreCase = true)) {
        return resources.getString(R.string.action_graceful_timed_out, guestName)
    }
    val summary = resources.getString(text.failed, guestName)
    return if (reason.isEmpty()) summary else "$summary\n$reason"
}

private val GRACEFUL = setOf(GuestAction.Shutdown, GuestAction.Reboot)
