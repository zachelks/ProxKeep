package io.github.zachelks.proxkeep.data

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class AppSettingsTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @BeforeTest
    fun setUp() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true) { clear() }
    }

    @Test
    fun theLockIsOffByDefault() {
        val settings = AppSettings(context)

        assertFalse(settings.lockEnabled.value)
        assertEquals(LockTimeout.OneMinute, settings.lockTimeout.value)
    }

    @Test
    fun changesAreSavedAndPublished() {
        val settings = AppSettings(context)

        settings.setLockEnabled(true)
        settings.setLockTimeout(LockTimeout.FifteenMinutes)

        assertTrue(settings.lockEnabled.value)
        assertEquals(LockTimeout.FifteenMinutes, settings.lockTimeout.value)
        val reopened = AppSettings(context)
        assertTrue(reopened.lockEnabled.value)
        assertEquals(LockTimeout.FifteenMinutes, reopened.lockTimeout.value)
    }

    @Test
    fun anUnknownTimeoutFallsBackToTheDefault() {
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit(commit = true) {
            putInt("app_lock_timeout_seconds", 42)
        }

        assertEquals(LockTimeout.Default, AppSettings(context).lockTimeout.value)
    }

    @Test
    fun timeoutsConvertBetweenSecondsAndMillis() {
        assertEquals(LockTimeout.Immediately, LockTimeout.fromSeconds(0))
        assertEquals(LockTimeout.FiveMinutes, LockTimeout.fromSeconds(300))
        assertEquals(900_000L, LockTimeout.FifteenMinutes.millis)
    }
}
