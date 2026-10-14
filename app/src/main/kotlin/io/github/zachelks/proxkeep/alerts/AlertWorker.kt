package io.github.zachelks.proxkeep.alerts

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.data.AlertChecker
import io.github.zachelks.proxkeep.data.AlertInterval
import io.github.zachelks.proxkeep.data.AlertKinds
import io.github.zachelks.proxkeep.data.AlertStore
import io.github.zachelks.proxkeep.data.Alert
import io.github.zachelks.proxkeep.data.ServerRepository
import kotlinx.coroutines.flow.first
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Checks every saved server it can reach without the person: those
 * signed in now, and those with an API token or a saved password.
 */
class AlertWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as ProxKeepApplication).container
        if (!container.settings.alertsEnabled.value) return Result.success()
        val alerts = checkAll(container.repository, PreferencesAlertStore(applicationContext), container.settings.alertKinds.value)
        AlertNotifier(applicationContext).notify(alerts)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "alerts"

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

        /** Schedules the checks, or cancels them when alerts are off. */
        fun schedule(context: Context, enabled: Boolean, interval: AlertInterval) {
            // Nothing can be scheduled before WorkManager starts, so there is nothing to cancel either.
            if (!enabled && !WorkManager.isInitialized()) return
            val work = WorkManager.getInstance(context)
            if (!enabled) {
                work.cancelUniqueWork(WORK_NAME)
                return
            }
            val request = PeriodicWorkRequestBuilder<AlertWorker>(interval.minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }
    }
}
