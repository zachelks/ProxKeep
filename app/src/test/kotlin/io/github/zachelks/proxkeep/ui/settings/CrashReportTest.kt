package io.github.zachelks.proxkeep.ui.settings

import android.content.Context
import android.content.Intent
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import io.github.zachelks.proxkeep.ProxKeepApplication
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.waitWithLooper
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class CrashReportTest {

    @get:Rule
    val compose = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<ProxKeepApplication>()
    private fun text(id: Int) = (app as Context).getString(id)

    @Test
    fun theLastCrashCanBeSharedAndCleared() {
        app.container.crashLog.record("main", IllegalStateException("boom"))
        compose.setContent { SettingsScreen(onClose = {}, onOpenExtraKeys = {}) }
        compose.onNodeWithText(text(R.string.crash_title)).performScrollTo().performClick()
        val chooser = shadowOf(app).nextStartedActivity
        val shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals(true, shared.getStringExtra(Intent.EXTRA_TEXT)?.contains("IllegalStateException: boom"))

        compose.onNodeWithText(text(R.string.crash_clear)).performScrollTo().performClick()
        compose.waitWithLooper { compose.onAllNodes(hasText(text(R.string.crash_title))).fetchSemanticsNodes().isEmpty() }
        assertNull(app.container.crashLog.read())
    }
}
