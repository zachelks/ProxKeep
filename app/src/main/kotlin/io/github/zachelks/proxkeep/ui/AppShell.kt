package io.github.zachelks.proxkeep.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import io.github.zachelks.proxkeep.ui.theme.AppTheme

/**
 * Top-level frame of the app. [NavigationSuiteScaffold] shows a bottom bar on
 * phones and a navigation rail on wider windows.
 */
@Composable
fun AppShell() {
    var current by rememberSaveable { mutableStateOf(TopLevelDestination.Overview) }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                item(
                    selected = destination == current,
                    onClick = { current = destination },
                    icon = {
                        Icon(
                            painter = painterResource(destination.icon),
                            contentDescription = null,
                        )
                    },
                    label = { Text(stringResource(destination.label)) },
                )
            }
        },
    ) {
        EmptyDestination(current)
    }
}

/** Stand-in content until each destination has real screens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyDestination(destination: TopLevelDestination) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text(stringResource(destination.label)) })
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(destination.emptyMessage),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun AppShellPreview() {
    AppTheme(dynamicColor = false) {
        AppShell()
    }
}
