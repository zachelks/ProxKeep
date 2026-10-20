@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.zachelks.proxkeep.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.zachelks.proxkeep.R
import io.github.zachelks.proxkeep.console.TerminalKey
import io.github.zachelks.proxkeep.ui.appContainer

/** Chooses and orders the keys of the terminal's extra-keys row. */
@Composable
fun ExtraKeysScreen(onClose: () -> Unit) {
    val settings = appContainer().settings
    val row by settings.extraKeys.collectAsStateWithLifecycle()
    val update = settings::setExtraKeys

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_extra_keys)) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                actions = {
                    TextButton(onClick = { update(TerminalKey.DEFAULT_ROW) }, enabled = row != TerminalKey.DEFAULT_ROW) {
                        Text(stringResource(R.string.action_reset_default))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.extra_keys_intro), style = MaterialTheme.typography.bodyMedium)
            OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.horizontalScroll(rememberScrollState()).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (row.isEmpty()) Text(stringResource(R.string.extra_keys_hidden))
                    row.forEach { Text(it.label, fontFamily = FontFamily.Monospace) }
                }
            }

            row.forEachIndexed { index, key ->
                ListItem(
                    headlineContent = { Text(key.label, fontFamily = FontFamily.Monospace) },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { update(row.moved(index, index - 1)) }, enabled = index > 0) {
                                Icon(painterResource(R.drawable.ic_arrow_upward), stringResource(R.string.action_move_key_up, key.label))
                            }
                            IconButton(onClick = { update(row.moved(index, index + 1)) }, enabled = index < row.lastIndex) {
                                Icon(painterResource(R.drawable.ic_arrow_downward), stringResource(R.string.action_move_key_down, key.label))
                            }
                            IconButton(onClick = { update(row - key) }) {
                                Icon(painterResource(R.drawable.ic_close), stringResource(R.string.action_remove_key, key.label))
                            }
                        }
                    },
                )
            }

            val available = TerminalKey.entries - row.toSet()
            if (available.isNotEmpty()) {
                Text(stringResource(R.string.extra_keys_add), style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    available.forEach { key ->
                        AssistChip(
                            onClick = { update(row + key) },
                            label = { Text(key.label, fontFamily = FontFamily.Monospace) },
                        )
                    }
                }
            }
        }
    }
}

/** This list with the item at [from] moved to [to]. Out-of-range moves change nothing. */
internal fun <T> List<T>.moved(from: Int, to: Int): List<T> {
    if (from !in indices || to !in indices || from == to) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}
