package io.github.zachelks.proxkeep.data

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps the app's process, and so its uploads and downloads, alive while
 * the app is in the background, like [io.github.zachelks.proxkeep.console.ConsoleService]
 * does for consoles. It runs exactly while a transfer is under way, and its
 * notification shows the progress.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val transfers: StorageTransfers
        get() = (application as ProxKeepApplication).container.transfers

    override fun onCreate() {
        super.onCreate()
        createChannel(this)
        // Android requires the notification within seconds of starting, before the first update below.
        startInForeground(notification(transfers.transfers.value))
        scope.launch {
            // Progress changes every percent, and Android drops notification updates that come faster than a few a
            // second. The flow keeps only the latest list while this waits.
            transfers.transfers.collect { list ->
                if (list.isEmpty()) {
                    ServiceCompat.stopForeground(this@TransferService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(list))
                    delay(UPDATE_INTERVAL_MS)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int =
        // If the system stops the process, its transfers are gone, so there is nothing to restart.
        START_NOT_STICKY

    /** Android 15 limits how long data transfers may run in the background each day. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun startInForeground(notification: Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun notification(list: List<Transfer>): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        // One upload shows how far it is; anything else only that it runs.
        val fraction = list.singleOrNull()?.fraction
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_arrow_upward)
            .setContentTitle(resources.getQuantityString(R.plurals.transfer_notification_title, list.size, list.size))
            .setContentText(list.joinToString(", ") { it.fileName })
            .setProgress(PROGRESS_MAX, ((fraction ?: 0f) * PROGRESS_MAX).toInt(), fraction == null)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "transfers"
        private const val NOTIFICATION_ID = 2
        private const val PROGRESS_MAX = 100
        private const val UPDATE_INTERVAL_MS = 500L

        /** Starts the service. Call when the first transfer starts, from the foreground. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, TransferService::class.java))
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.transfer_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = context.getString(R.string.transfer_notification_channel_description) }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }
}
