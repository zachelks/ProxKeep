package io.github.zachelks.proxkeep.console

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.MainActivity
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.ui.navigation.Route
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ConsoleServiceTest {

    private val app = ApplicationProvider.getApplicationContext<ProxKeepApplication>()

    @Test
    fun theServiceStopsWithoutConsoles() {
        val service = Robolectric.buildService(ConsoleService::class.java).create().get()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(service).isForegroundStopped, "it went into the foreground, then left it")
        assertTrue(shadowOf(service).isStoppedBySelf, "nothing to keep alive")
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("consoles"))
    }

    @Test
    fun endAllFromTheNotificationClosesEveryConsole() {
        val controller = Robolectric.buildService(
            ConsoleService::class.java,
            Intent(app, ConsoleService::class.java).setAction(ConsoleService.ACTION_END_ALL),
        )

        controller.create().startCommand(0, 1)

        assertEquals(emptyList(), app.container.consoles.sessions.value)
    }

    @Test
    fun theNotificationAsksForTheTerminal() {
        // Created but not yet on screen, so nothing has taken the request yet.
        Robolectric.buildActivity(
            MainActivity::class.java,
            Intent(app as Context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_TERMINAL),
        ).create()

        assertEquals(Route.Terminal, app.container.pendingNavigation.take())
        assertNull(app.container.pendingNavigation.take(), "a request is taken once")
    }
}
