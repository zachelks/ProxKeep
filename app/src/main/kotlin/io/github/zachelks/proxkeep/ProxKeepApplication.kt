package io.github.zachelks.proxkeep

import android.app.Application
import android.content.Context
import android.os.SystemClock
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.zachelks.proxkeep.console.ConsoleManager
import io.github.zachelks.proxkeep.console.ConsoleService
import io.github.zachelks.proxkeep.data.AppSettings
import io.github.zachelks.proxkeep.desktop.DesktopManager
import io.github.zachelks.proxkeep.data.ClusterRepository
import io.github.zachelks.proxkeep.data.GuestActions
import io.github.zachelks.proxkeep.data.GuestLifecycle
import io.github.zachelks.proxkeep.data.KeyChainKeys
import io.github.zachelks.proxkeep.data.ProfileStore
import io.github.zachelks.proxkeep.data.RefreshSignal
import io.github.zachelks.proxkeep.data.SecretBox
import io.github.zachelks.proxkeep.data.SchemaRepository
import io.github.zachelks.proxkeep.data.ServerAccessRepository
import io.github.zachelks.proxkeep.data.StorageTransfers
import io.github.zachelks.proxkeep.data.ServerRepository
import io.github.zachelks.proxkeep.security.AppLock
import io.github.zachelks.proxkeep.security.Authenticator
import io.github.zachelks.proxkeep.ui.navigation.PendingNavigation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
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

    val cluster = ClusterRepository(repository, refresh, scope)

    val access = ServerAccessRepository(repository, scope)

    val schemas = SchemaRepository(repository, File(context.cacheDir, "api-schema"))

    val guestActions = GuestActions(repository, refresh, scope)

    val guestLifecycle = GuestLifecycle(repository, refresh, scope)

    val transfers = StorageTransfers(repository, refresh, scope)

    val consoles = ConsoleManager(context.applicationContext, repository, scope)

    val desktops = DesktopManager(repository, scope)

    val pendingNavigation = PendingNavigation()

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
        // Open consoles keep the process alive in the background. The service stops itself after the last one.
        scope.launch {
            consoles.sessions
                .map { it.isNotEmpty() }
                .distinctUntilChanged()
                .filter { it }
                .collect { ConsoleService.start(context.applicationContext) }
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}
