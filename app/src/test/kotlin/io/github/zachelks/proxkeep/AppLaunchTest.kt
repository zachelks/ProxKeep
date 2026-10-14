package io.github.zachelks.proxkeep

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.data.LockTimeout
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.AfterTest
import kotlin.test.Test

/** The whole app, from its real application class and activity. */
@RunWith(RobolectricTestRunner::class)
class AppLaunchTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private val app = ApplicationProvider.getApplicationContext<ProxKeepApplication>()
    private var scenario: ActivityScenario<MainActivity>? = null

    @AfterTest
    fun tearDown() {
        scenario?.close()
    }

    @Test
    fun aFirstStartAsksForAServer() {
        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithText(text(R.string.empty_overview)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_add_server)).performClick()

        compose.onNodeWithText(text(R.string.address_intro)).assertIsDisplayed()
    }

    @Test
    fun nothingIsShownBehindTheLock() {
        app.container.settings.setLockEnabled(true)
        app.container.settings.setLockTimeout(LockTimeout.Immediately)
        app.container.appLock.onBackground()
        app.container.appLock.onForeground()

        scenario = ActivityScenario.launch(MainActivity::class.java)

        compose.onNodeWithText(text(R.string.locked_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.action_unlock)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.empty_overview)).assertDoesNotExist()
    }

    private fun text(id: Int): String = (app as Context).getString(id)
}
