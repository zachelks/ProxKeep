package io.github.zachelks.proxkeep.ui.cluster

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.core.api.model.ClusterResource
import io.github.zachelks.proxkeep.ui.theme.StatusTone

/** A status in words, with the tone its dot should take. */
data class StatusText(val text: String, val tone: StatusTone)

/** Maps the server's status word for a node, guest or storage to display text. */
@Composable
fun ClusterResource.statusText(): StatusText {
    if (isTemplate) return StatusText(stringResource(R.string.status_template), StatusTone.Neutral)
    return when (val raw = status?.lowercase()) {
        "running" -> StatusText(stringResource(R.string.status_running), StatusTone.Good)
        "online" -> StatusText(stringResource(R.string.status_online), StatusTone.Good)
        "available" -> StatusText(stringResource(R.string.status_available), StatusTone.Good)
        "stopped" -> StatusText(stringResource(R.string.status_stopped), StatusTone.Neutral)
        "paused", "suspended" -> StatusText(stringResource(R.string.status_paused), StatusTone.Warning)
        "offline" -> StatusText(stringResource(R.string.status_offline), StatusTone.Bad)
        null, "", "unknown" -> StatusText(stringResource(R.string.status_unknown), StatusTone.Neutral)
        else -> StatusText(raw.orEmpty().replaceFirstChar { it.uppercase() }, StatusTone.Neutral)
    }
}
