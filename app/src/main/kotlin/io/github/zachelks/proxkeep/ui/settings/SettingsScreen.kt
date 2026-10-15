@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.zachelks.proxkeep.ui.settings

import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.graphics.toColorInt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.TerminalFont
import io.github.zachelks.proxkeep.console.TerminalPalette
import io.github.zachelks.proxkeep.data.LockTimeout
import io.github.zachelks.proxkeep.data.TERMINAL_TEXT_SIZES_SP
import io.github.zachelks.proxkeep.ui.appContainer
import kotlin.math.roundToInt

private const val SOURCE_URL = "https://github.com/zachelks/ProxKeep"

@Composable
fun SettingsScreen(onClose: () -> Unit, onOpenExtraKeys: () -> Unit) {
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
            SectionTitle(stringResource(R.string.settings_alerts_section))
            AlertSettings()
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SectionTitle(stringResource(R.string.settings_terminal))
            TerminalSettings(onOpenExtraKeys)
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

@Composable
private fun TerminalSettings(onOpenExtraKeys: () -> Unit) {
    val settings = appContainer().settings
    val row by settings.extraKeys.collectAsStateWithLifecycle()
    val textSize by settings.terminalTextSize.collectAsStateWithLifecycle()
    val font by settings.terminalFont.collectAsStateWithLifecycle()
    val palette by settings.terminalPalette.collectAsStateWithLifecycle()

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_text_size)) },
        supportingContent = {
            Column {
                Text(stringResource(R.string.settings_text_size_value, textSize))
                Slider(
                    value = textSize.toFloat(),
                    onValueChange = { settings.setTerminalTextSize(it.roundToInt()) },
                    valueRange = TERMINAL_TEXT_SIZES_SP.first.toFloat()..TERMINAL_TEXT_SIZES_SP.last.toFloat(),
                )
            }
        },
    )

    Label(stringResource(R.string.settings_font))
    RadioGroup(
        options = TerminalFont.entries,
        selected = font,
        onSelect = settings::setTerminalFont,
        label = { stringResource(it.label()) },
    )

    Label(stringResource(R.string.settings_colors))
    RadioGroup(
        options = TerminalPalette.entries,
        selected = palette,
        onSelect = settings::setTerminalPalette,
        label = { stringResource(it.label()) },
        trailing = { PalettePreview(it) },
    )

    ListItem(
        headlineContent = { Text(stringResource(R.string.settings_extra_keys)) },
        supportingContent = {
            Text(
                if (row.isEmpty()) {
                    stringResource(R.string.extra_keys_hidden)
                } else {
                    row.joinToString(" ") { it.label }
                },
                maxLines = 1,
            )
        },
        modifier = Modifier.clickable(onClick = onOpenExtraKeys),
    )
}

@Composable
private fun Label(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
    )
}

@Composable
private fun <T> RadioGroup(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: @Composable (T) -> String,
    trailing: @Composable (T) -> Unit = {},
) {
    Column(modifier = Modifier.selectableGroup()) {
        options.forEach { option ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = option == selected, onClick = { onSelect(option) }, role = Role.RadioButton)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = option == selected, onClick = null)
                Spacer(Modifier.width(16.dp))
                Text(text = label(option), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                trailing(option)
            }
        }
    }
}

/** A sample of a palette: its text and a few ANSI colors on its background. */
@Composable
private fun PalettePreview(palette: TerminalPalette) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp))
            .background(Color(palette.background.toColorInt()))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("ls", color = Color(palette.foreground.toColorInt()), fontFamily = FontFamily.Monospace)
        listOf(1, 2, 3, 4).forEach { index ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(palette.ansi[index].toColorInt())))
        }
    }
}

private fun TerminalFont.label(): Int = when (this) {
    TerminalFont.System -> R.string.font_system
    TerminalFont.JetBrainsMono -> R.string.font_jetbrains_mono
}

private fun TerminalPalette.label(): Int = when (this) {
    TerminalPalette.Default -> R.string.palette_default
    TerminalPalette.SolarizedDark -> R.string.palette_solarized_dark
    TerminalPalette.SolarizedLight -> R.string.palette_solarized_light
    TerminalPalette.Dracula -> R.string.palette_dracula
    TerminalPalette.GruvboxDark -> R.string.palette_gruvbox_dark
    TerminalPalette.TangoLight -> R.string.palette_tango_light
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
    CrashReport()
    ListItem(
        headlineContent = { Text(stringResource(R.string.about_license)) },
        supportingContent = { Text(stringResource(R.string.about_license_summary)) },
        modifier = Modifier.clickable { uriHandler.openUri(SOURCE_URL) },
    )
    var showingFontLicense by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(stringResource(R.string.about_font)) },
        supportingContent = { Text(stringResource(R.string.about_font_license)) },
        modifier = Modifier.clickable { showingFontLicense = true },
    )
    if (showingFontLicense) {
        val text = remember {
            context.assets.open("licenses/JetBrainsMono-OFL.txt").bufferedReader().use { it.readText() }
        }
        AlertDialog(
            onDismissRequest = { showingFontLicense = false },
            title = { Text(stringResource(R.string.about_font)) },
            text = {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = { showingFontLicense = false }) { Text(stringResource(R.string.action_close)) }
            },
        )
    }
    Text(
        text = stringResource(R.string.about_trademark),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp),
    )
}

/** The last crash's report, when there is one, to share with whoever fixes the app. */
@Composable
private fun CrashReport() {
    val context = LocalContext.current
    val crashLog = appContainer().crashLog
    var report by remember { mutableStateOf(crashLog.read()) }
    val text = report ?: return
    val chooserTitle = stringResource(R.string.crash_share)
    ListItem(
        headlineContent = { Text(stringResource(R.string.crash_title)) },
        supportingContent = { Text(stringResource(R.string.crash_summary)) },
        trailingContent = {
            TextButton(onClick = {
                crashLog.clear()
                report = null
            }) { Text(stringResource(R.string.crash_clear)) }
        },
        modifier = Modifier.clickable {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            context.startActivity(Intent.createChooser(send, chooserTitle))
        },
    )
}
