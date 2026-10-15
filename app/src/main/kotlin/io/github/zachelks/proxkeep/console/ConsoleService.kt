package io.github.zachelks.proxkeep.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.ui.console.sessionLabels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps the app's process, and so its consoles, alive while the app is in
 * the background. It runs exactly while at least one console is open, and
 * its notification leads back to the terminal or ends every console.
 */
class ConsoleService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val consoles: ConsoleManager
        get() = (application as ProxKeepApplication).container.consoles

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // Android requires the notification within seconds of starting, before the first update below.
        startInForeground(notification(consoles.sessions.value))
        scope.launch {
            consoles.sessions.collectLatest { sessions ->
                if (sessions.isEmpty()) {
                    ServiceCompat.stopForeground(this@ConsoleService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(sessions))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_END_ALL) consoles.closeAll()
        // If the system stops the process, its consoles are gone, so there is nothing to restart.
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(sessions: List<ConsoleSession>): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val endAll = PendingIntent.getService(
            this,
            0,
            Intent(this, ConsoleService::class.java).setAction(ACTION_END_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val labels = sessionLabels(sessions)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_terminal)
            .setContentTitle(resources.getQuantityString(R.plurals.console_notification_title, sessions.size, sessions.size))
            .setContentText(sessions.joinToString(", ") { labels[it] ?: it.title })
            .setContentIntent(open)
            .addAction(0, getString(R.string.console_notification_end_all), endAll)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    companion object {
        const val ACTION_END_ALL = "io.github.zachelks.proxkeep.action.END_ALL_CONSOLES"
        private const val CHANNEL_ID = "consoles"
        private const val NOTIFICATION_ID = 1

        /** Starts the service. Call when the first console opens, from the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ConsoleService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.console_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.console_notification_channel_description) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
