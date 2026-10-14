package io.github.zachelks.proxkeep.alerts

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.Alert
import io.github.zachelks.proxkeep.data.AlertInterval
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AlertSchedulingTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun state(): WorkInfo.State? = WorkManager.getInstance(context).getWorkInfosForUniqueWork("alerts").get().singleOrNull()?.state

    @Test
    fun checksAreScheduledWhileAlertsAreOn() {
        AlertWorker.schedule(context, enabled = true, interval = AlertInterval.OneHour)
        assertEquals(WorkInfo.State.ENQUEUED, state())
        AlertWorker.schedule(context, enabled = false, interval = AlertInterval.OneHour)
        assertEquals(WorkInfo.State.CANCELLED, state())
    }

    @Test
    fun alertsArePostedWithTheirDetailsKeptOffTheLockScreen() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AlertNotifier(context).notify(
            listOf(
                Alert.TaskFailed("p", "Home lab", "UPID:pve1:1:2:3:vzdump:100:root@pam:", "pve1", "vzdump", "100", "job errors"),
                Alert.NodeOffline("p", "Home lab", "pve2"),
            ),
        )
        val manager = context.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        val titles = posted.map { it.extras.getString("android.title") }
        assertTrue(context.getString(R.string.alert_node_offline, "pve2") in titles)
        assertTrue(posted.all { it.publicVersion != null })
    }
}
