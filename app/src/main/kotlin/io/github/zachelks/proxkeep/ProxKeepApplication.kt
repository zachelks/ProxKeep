package io.github.zachelks.proxkeep

import android.app.Application
import android.content.Context
import io.github.zachelks.proxkeep.data.ProfileStore
import io.github.zachelks.proxkeep.data.SecretBox
import io.github.zachelks.proxkeep.data.ServerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class ProxKeepApplication : Application() {
    /** Objects that live as long as the process. */
    val container: AppContainer by lazy { AppContainer(this) }
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
    )

    private companion object {
        const val CONNECT_TIMEOUT_SECONDS = 10L
        const val READ_TIMEOUT_SECONDS = 30L
    }
}
