package io.github.zachelks.proxkeep

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import io.github.zachelks.proxkeep.ui.AppRoot
import io.github.zachelks.proxkeep.ui.TopLevelDestination
import io.github.zachelks.proxkeep.ui.navigation.Route
import io.github.zachelks.proxkeep.ui.theme.AppTheme
import kotlinx.coroutines.launch

// A FragmentActivity because the system authentication prompt needs one.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // A recreated activity has handled its intent already.
        if (savedInstanceState == null) handle(intent)

        val settings = (application as ProxKeepApplication).container.settings
        lifecycleScope.launch {
            settings.lockEnabled.collect { enabled -> hideFromRecents(enabled) }
        }

        setContent {
            AppTheme {
                AppRoot()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        val navigation = (application as ProxKeepApplication).container.pendingNavigation
        when (intent?.action) {
            ACTION_OPEN_TERMINAL -> navigation.request(Route.Terminal)
            ACTION_OPEN_TASKS -> navigation.requestDestination(TopLevelDestination.Tasks)
        }
    }

    /**
     * With the lock on, keeps the app's contents out of the recent-apps
     * overview, where they would be visible without unlocking. Android 13 and
     * later can do exactly that. Older versions can only do it by also
     * blocking screenshots.
     */
    private fun hideFromRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    companion object {
        /** Opens the terminal, from the console notification. */
        const val ACTION_OPEN_TERMINAL = "io.github.zachelks.proxkeep.action.OPEN_TERMINAL"

        /** Opens the task list, from an alert. */
        const val ACTION_OPEN_TASKS = "io.github.zachelks.proxkeep.action.OPEN_TASKS"
    }
}
