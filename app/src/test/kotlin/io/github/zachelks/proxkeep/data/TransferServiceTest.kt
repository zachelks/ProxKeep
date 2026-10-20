package io.github.zachelks.proxkeep.data

import android.app.NotificationManager
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.ProxKeepApplication
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TransferServiceTest {

    private val app = ApplicationProvider.getApplicationContext<ProxKeepApplication>()

    @Test
    fun theServiceStopsWithoutTransfers() {
        val service = Robolectric.buildService(TransferService::class.java).create().get()
        shadowOf(Looper.getMainLooper()).idle()

        assertTrue(shadowOf(service).isForegroundStopped, "it went into the foreground, then left it")
        assertTrue(shadowOf(service).isStoppedBySelf, "nothing to keep alive")
        assertNotNull(app.getSystemService(NotificationManager::class.java).getNotificationChannel("transfers"))
    }
}
