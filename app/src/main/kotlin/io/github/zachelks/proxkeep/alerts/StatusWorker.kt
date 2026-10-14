package io.github.zachelks.proxkeep.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.data.Alert
import io.github.zachelks.proxkeep.data.AlertChecker
import io.github.zachelks.proxkeep.data.AlertInterval
import io.github.zachelks.proxkeep.data.AlertKinds
import io.github.zachelks.proxkeep.data.AlertStore
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.StatusSnapshot
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks servers in the background: every saved server it can reach
 * without the person for alerts, and the signed-in server for the widget
 * and tile. A server can be reached when it is signed in now, or has an
 * API token or a saved password.
 */
class StatusWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ProxKeepApplication).container
        if (container.settings.alertsEnabled.value) {
            val alerts = checkAll(container.repository, PreferencesAlertStore(applicationContext), container.settings.alertKinds.value)
            AlertNotifier(applicationContext).notify(alerts)
        }
        if (container.statusSurfaces.placed.value) {
            container.statusSurfaces.update(snapshot(container.repository))
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "alerts"
        private const val ONCE_NAME = "status-once"

        /** Runs one check of every server, and records what it saw. A server that cannot be reached is skipped. */
        suspend fun checkAll(servers: ServerRepository, store: AlertStore, kinds: AlertKinds): List<Alert> {
            servers.loaded.first { it }
            val profiles = servers.servers.value.profiles
            val state = store.read().filterKeys { id -> profiles.any { it.id == id } }.toMutableMap()
            val alerts = mutableListOf<Alert>()
            for (profile in profiles) {
                try {
                    val client = servers.backgroundClient(profile) ?: continue
                    val (found, next) = AlertChecker.check(profile, client, state[profile.id], kinds)
                    alerts += found
                    state[profile.id] = next
                } catch (e: IOException) {
                    // Unreachable now; the next check tries again and still reports what it missed.
                }
            }
            store.write(state)
            return alerts
        }

        /** The signed-in server's status, or null when no server is saved. A server that cannot be reached keeps its last status. */
        suspend fun snapshot(servers: ServerRepository): StatusSnapshot? {
            servers.loaded.first { it }
            val profile = servers.servers.value.active ?: return null
            return try {
                val client = servers.backgroundClient(profile) ?: return null
                StatusSnapshot.of(profile, client.clusterResources(), System.currentTimeMillis())
            } catch (e: IOException) {
                null
            }
        }

        /** Checks once soon, so a newly placed widget or tile has something to show. */
        fun runOnce(context: Context) {
            val request = OneTimeWorkRequestBuilder<StatusWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(ONCE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        /** Schedules the checks, or cancels them when nothing needs them. */
        fun schedule(context: Context, enabled: Boolean, interval: AlertInterval) {
            // Nothing can be scheduled before WorkManager starts, so there is nothing to cancel either.
            if (!enabled && !WorkManager.isInitialized()) return
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<StatusWorker>(interval.minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
