package io.github.zachelks.proxkeep.status

import android.content.Context
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.alerts.StatusWorker
import io.github.zachelks.proxkeep.data.StatusSnapshot

/**
 * The signed-in server at a glance: how many nodes are online and guests
 * running. With the app lock on, it shows no server details.
 */
class StatusWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val container = (context.applicationContext as ProxKeepApplication).container
        val locked = container.settings.lockEnabled.value
        val snapshot = container.statusSurfaces.store.read()
        val lines = describe(context, snapshot, locked)
        provideContent {
            GlanceTheme {
                Content(lines)
            }
        }
    }

    @Composable
    private fun Content(lines: StatusLines) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .cornerRadius(16.dp)
                .padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            Text(lines.title, style = TextStyle(fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
            lines.body.forEach { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurface)) }
            lines.footer?.let { Text(it, style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant)) }
        }
    }
}

/** What the widget and tile say. */
data class StatusLines(val title: String, val body: List<String>, val footer: String?)

/** Words for [snapshot], or the reason there are none. */
fun describe(context: Context, snapshot: StatusSnapshot?, locked: Boolean): StatusLines {
    val app = context.getString(R.string.app_name)
    if (locked) return StatusLines(app, listOf(context.getString(R.string.status_locked)), null)
    if (snapshot == null) return StatusLines(app, listOf(context.getString(R.string.status_none)), null)
    val nodes = if (snapshot.allNodesOnline) {
        context.resources.getQuantityString(R.plurals.status_nodes_online, snapshot.nodesTotal, snapshot.nodesTotal)
    } else {
        context.resources.getQuantityString(R.plurals.status_nodes_offline, snapshot.nodesTotal, snapshot.nodesTotal - snapshot.nodesOnline, snapshot.nodesTotal)
    }
    val guests = context.resources.getQuantityString(R.plurals.status_guests_running, snapshot.guestsTotal, snapshot.guestsRunning, snapshot.guestsTotal)
    val time = DateUtils.formatDateTime(context, snapshot.updatedAt, DateUtils.FORMAT_SHOW_TIME)
    return StatusLines(snapshot.serverName, listOf(nodes, guests), context.getString(R.string.status_updated, time))
}

/** Places and removes the widget. While one is placed, background checks keep its status fresh. */
class StatusWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = StatusWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        (context.applicationContext as ProxKeepApplication).container.statusSurfaces.setWidgetPlaced(true)
        StatusWorker.runOnce(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        (context.applicationContext as ProxKeepApplication).container.statusSurfaces.setWidgetPlaced(false)
    }
}
