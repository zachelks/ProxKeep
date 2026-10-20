package io.github.zachelks.proxkeep

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.zachelks.proxkeep.alerts.StatusWorker
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.console.ConsoleService
import io.github.zachelks.proxkeep.data.AppSettings
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.CrashLog
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.KeyChainKeys
import io.github.zachelks.proxkeep.data.ProfileStore
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.SecretBox
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.data.StatusSnapshot
import io.github.zachelks.proxkeep.data.StorageTransfers
import io.github.zachelks.proxkeep.data.TransferService
import io.github.zachelks.proxkeep.desktop.DesktopManager
import io.github.zachelks.proxkeep.security.AppLock
import io.github.zachelks.proxkeep.security.Authenticator
import io.github.zachelks.proxkeep.status.StatusSurfaces
import io.github.zachelks.proxkeep.ui.guests.removeGuestShortcuts
import io.github.zachelks.proxkeep.ui.navigation.PendingNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class ProxKeepApplication : Application() {
    /** Objects that live as long as the process. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        CrashLog(crashLogFile(this), about = { aboutForCrashes(this) }).install()
        container = AppContainer(this)

        // The process lifecycle starts and stops with the app as a whole, not
        // with single screens, so rotating the device is not an absence.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) = container.appLock.onForeground()

                override fun onStop(owner: LifecycleOwner) = container.appLock.onBackground()
            },
        )
    }
}

/** Where the last crash's report is kept. */
fun crashLogFile(context: Context): File = File(context.filesDir, "last-crash.txt")

/** The app's version and the device, at the top of a crash report. */
private fun aboutForCrashes(context: Context): String {
    val version = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }
    return "ProxKeep ${version.orEmpty()}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}"
}

class AppContainer(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Shared so every server reuses one thread pool. Each server gets its own
    // copy carrying that server's trust settings.
    private val http = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    val repository = ServerRepository(
        store = ProfileStore(File(context.filesDir, "servers.json")),
        secrets = SecretBox(),
        http = http,
        scope = scope,
        clientKeys = KeyChainKeys(context),
    )

    val refresh = RefreshSignal()

    val statusSurfaces = StatusSurfaces(context.applicationContext)

    // While the app shows the cluster, its widget and tile follow along without a background check.
    val cluster = ClusterRepository(repository, refresh, scope) { loaded ->
        val profile = repository.servers.value.profiles.firstOrNull { it.id == loaded.profileId } ?: return@ClusterRepository
        if (statusSurfaces.placed.value) {
            scope.launch { statusSurfaces.update(StatusSnapshot.of(profile, loaded.resources, System.currentTimeMillis())) }
        }
    }

    val access = ServerAccessRepository(repository, scope)

    val schemas = SchemaRepository(repository, File(context.cacheDir, "api-schema"))

    val guestActions = GuestActions(repository, refresh, scope)

    val guestLifecycle = GuestLifecycle(repository, refresh, scope)

    val transfers = StorageTransfers(repository, refresh, scope)

    val consoles = ConsoleManager(context.applicationContext, repository, scope)

    val desktops = DesktopManager(repository, scope)

    val pendingNavigation = PendingNavigation()

    val crashLog = CrashLog(crashLogFile(context))

    val settings = AppSettings(context)

    val appLock = AppLock(
        isEnabled = { settings.lockEnabled.value },
        timeoutMs = { settings.lockTimeout.value.millis },
        now = SystemClock::elapsedRealtime,
    )

    val authenticator = Authenticator(appLock)

    init {
        scope.launch {
            settings.terminalPalette.collect { palette ->
                palette.applyToTerminals()
                consoles.refreshColors()
            }
        }
        // Background checks run for alerts and for a placed widget or tile; a change replaces the schedule.
        scope.launch {
            combine(settings.alertsEnabled, settings.alertInterval, statusSurfaces.placed) { alerts, interval, placed -> (alerts || placed) to interval }
                .distinctUntilChanged()
                .collect { (enabled, interval) -> StatusWorker.schedule(context.applicationContext, enabled, interval) }
        }
        // Shortcuts to a removed server's guests would open nothing.
        scope.launch {
            var known: Set<String>? = null
            repository.servers.collect { stored ->
                val ids = stored.profiles.map { it.id }.toSet()
                known?.minus(ids)?.forEach { removeGuestShortcuts(context.applicationContext, it) }
                if (repository.loaded.value) known = ids
            }
        }
        // The widget and tile hide server details while the app lock is on.
        scope.launch {
            settings.lockEnabled.drop(1).collect { if (statusSurfaces.placed.value) statusSurfaces.redraw() }
        }
        // Open consoles keep the process alive in the background. The service stops itself after the last one.
        scope.launch {
            consoles.sessions
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .filter { it }
                .collect { ConsoleService.start(context.applicationContext) }
        }
        // Uploads and downloads do the same, so leaving the app does not cut them off.
        scope.launch {
            transfers.transfers
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .filter { it }
                .collect { TransferService.start(context.applicationContext) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}
