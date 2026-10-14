package io.github.zachelks.proxkeep.ui.storage

import android.content.res.Resources
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.TransferEvent

/** One line saying how an upload or download ended, with the server's reason when it failed. */
fun TransferEvent.message(resources: Resources): String {
    if (succeeded) return resources.getString(R.string.transfer_done, fileName, storage)
    val summary = resources.getString(R.string.transfer_failed, fileName, storage)
    val reason = detail?.trim().orEmpty()
    return if (reason.isEmpty()) summary else "$summary\n$reason"
}
