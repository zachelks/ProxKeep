package io.github.zachelks.proxkeep.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.Alert
import io.github.zachelks.proxkeep.ui.tasks.taskTitle

/** Posts a notification for each alert. Their details stay off the lock screen. */
class AlertNotifier(private val context: Context) {

    fun ensureChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.alerts_channel), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.alerts_channel_description)
        manager.createNotificationChannel(channel)
    }

    fun canNotify(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    @android.annotation.SuppressLint("MissingPermission")
    fun notify(alerts: List<Alert>) {
        if (alerts.isEmpty() || !canNotify()) return
        ensureChannel()
        val manager = NotificationManagerCompat.from(context)
        alerts.forEach { alert ->
            val (title, text) = describe(alert)
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TASKS).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val public = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert)
                .setContentTitle(context.getString(R.string.alerts_public_title))
                .build()
            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alert)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setSubText(alert.serverName)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setContentIntent(open)
                .setAutoCancel(true)
                .setGroup(GROUP)
                .build()
            manager.notify(notificationId(alert), notification)
        }
    }

    private fun describe(alert: Alert): Pair<String, String> = when (alert) {
        is Alert.TaskFailed -> {
            val what = taskTitle(context.resources, alert.type, alert.guest)
            context.getString(R.string.alert_task_failed, what) to context.getString(R.string.alert_task_detail, alert.node, alert.status)
        }
        is Alert.NodeOffline -> context.getString(R.string.alert_node_offline, alert.node) to context.getString(R.string.alert_node_offline_detail)
        is Alert.NodeOnline -> context.getString(R.string.alert_node_online, alert.node) to context.getString(R.string.alert_node_online_detail)
    }

    private fun notificationId(alert: Alert): Int = when (alert) {
        is Alert.TaskFailed -> alert.upid.hashCode()
        // A node's offline and online notices replace each other.
        is Alert.NodeOffline -> "${alert.profileId}/node/${alert.node}".hashCode()
        is Alert.NodeOnline -> "${alert.profileId}/node/${alert.node}".hashCode()
    }

    companion object {
        const val CHANNEL_ID = "alerts"
        private const val GROUP = "io.github.zachelks.proxkeep.ALERTS"
    }
}
