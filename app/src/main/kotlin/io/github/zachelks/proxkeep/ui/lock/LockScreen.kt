package io.github.zachelks.proxkeep.ui.lock

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.security.AppLock
import io.github.zachelks.proxkeep.security.Authenticator

/**
 * Covers the whole app while it is locked. The system prompt appears once by
 * itself; if it is dismissed, the button brings it back.
 */
@Composable
fun LockScreen(appLock: AppLock, authenticator: Authenticator) {
    val activity = LocalActivity.current as? FragmentActivity
    val title = stringResource(R.string.unlock_prompt_title)
    val prompt = {
        if (activity != null) {
            authenticator.authenticate(activity, title) { succeeded ->
                if (succeeded) appLock.unlock()
            }
        }
    }

    var promptedOnce by rememberSaveable { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        // The prompt can only be shown while the app is in front.
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            if (!promptedOnce) {
                promptedOnce = true
                prompt()
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_security),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Text(text = stringResource(R.string.locked_title), style = MaterialTheme.typography.headlineSmall)
            Button(onClick = prompt) {
                Text(stringResource(R.string.action_unlock))
            }
        }
    }
}
