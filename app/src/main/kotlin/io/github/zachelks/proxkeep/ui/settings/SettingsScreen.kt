@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.settings

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.data.LockTimeout
import io.github.zachelks.proxkeep.ui.appContainer

private const val SOURCE_URL = "https://github.com/zachelks/ProxKeep"

@Composable
fun SettingsScreen(onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_back),
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionTitle(stringResource(R.string.settings_security))
            AppLockSettings()
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_about))
            About()
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

@Composable
private fun AppLockSettings() {
    val container = appContainer()
    val settings = container.settings
    val authenticator = container.authenticator
    val context = LocalContext.current
    val activity = LocalActivity.current as? FragmentActivity

    val enabled by settings.lockEnabled.collectAsStateWithLifecycle()
    val timeout by settings.lockTimeout.collectAsStateWithLifecycle()
    val available = authenticator.isAvailable(context)
    val confirmTitle = stringResource(R.string.unlock_prompt_title)

    // Switching the lock on or off both ask for proof first: on, so a lock
    // that cannot be opened is never set; off, so it cannot be removed by
    // whoever happens to be holding the unlocked phone.
    val toggle = {
        if (activity != null) {
            val turnOn = !enabled
            authenticator.authenticate(activity, confirmTitle) { succeeded ->
                if (succeeded) settings.setLockEnabled(turnOn)
            }
        }
    }
    val canToggle = available || enabled

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_app_lock)) },
        supportingContent = {
            Text(stringResource(if (canToggle) R.string.settings_app_lock_summary else R.string.settings_app_lock_unavailable))
        },
        trailingContent = {
            Switch(checked = enabled, onCheckedChange = { toggle() }, enabled = canToggle)
        },
    )

    if (enabled) {
        Text(
            text = stringResource(R.string.settings_lock_after),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Column(modifier = Modifier.selectableGroup()) {
            LockTimeout.entries.forEach { option ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = option == timeout,
                            onClick = { settings.setLockTimeout(option) },
                            role = Role.RadioButton,
                        )
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = option == timeout, onClick = null)
                    Spacer(Modifier.width(16.dp))
                    Text(text = stringResource(option.label()), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

private fun LockTimeout.label(): Int = when (this) {
    LockTimeout.Immediately -> R.string.lock_timeout_immediately
    LockTimeout.OneMinute -> R.string.lock_timeout_1_minute
    LockTimeout.FiveMinutes -> R.string.lock_timeout_5_minutes
    LockTimeout.FifteenMinutes -> R.string.lock_timeout_15_minutes
}

@Composable
private fun About() {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val version = remember {
        val info = try {
            context.packageManager.getPackageInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        info?.versionName.orEmpty()
    }

    ListItem(
        headlineContent = { Text(stringResource(R.string.about_version)) },
        supportingContent = { Text(version) },
    )
    ListItem(
        headlineContent = { Text(stringResource(R.string.about_license)) },
        supportingContent = { Text(stringResource(R.string.about_license_summary)) },
        modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
    )
    Text(
        text = stringResource(R.string.about_trademark),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}
