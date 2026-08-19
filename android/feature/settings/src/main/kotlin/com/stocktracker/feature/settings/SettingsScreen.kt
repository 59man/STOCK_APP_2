package com.stocktracker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CURRENCIES = listOf("CZK", "USD", "EUR")

@Composable
fun SettingsRoute(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    SettingsScreen(uiState = uiState, onAction = viewModel::onAction)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(uiState: SettingsUiState, onAction: (SettingsAction) -> Unit) {
    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).padding(16.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Get your API key from the 🔑 button in the web app, then paste both here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = uiState.serverUrl,
                onValueChange = { onAction(SettingsAction.ServerUrlChanged(it)) },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = uiState.apiKey,
                onValueChange = { onAction(SettingsAction.ApiKeyChanged(it)) },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )

            var currencyExpanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = currencyExpanded, onExpandedChange = { currencyExpanded = it }) {
                OutlinedTextField(
                    value = uiState.displayCurrency,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Display currency") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = currencyExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                )
                DropdownMenu(expanded = currencyExpanded, onDismissRequest = { currencyExpanded = false }) {
                    CURRENCIES.forEach { currency ->
                        DropdownMenuItem(
                            text = { Text(currency) },
                            onClick = { onAction(SettingsAction.DisplayCurrencyChanged(currency)); currencyExpanded = false },
                        )
                    }
                }
            }

            Button(onClick = { onAction(SettingsAction.TestConnection) }, modifier = Modifier.fillMaxWidth()) {
                Text("Test connection")
            }
            ConnectionStatusLabel(uiState.connectionTest)

            Button(onClick = { onAction(SettingsAction.SyncNow) }, modifier = Modifier.fillMaxWidth()) {
                Text("Sync now")
            }
            Text(
                text = uiState.lastSyncedAt?.let { "Last synced: $it" } ?: "Never synced",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ConnectionStatusLabel(state: ConnectionTestState) {
    val text = when (state) {
        ConnectionTestState.Idle -> null
        ConnectionTestState.Testing -> "Testing…"
        ConnectionTestState.Success -> "Connected"
        is ConnectionTestState.Failed -> "Failed: ${state.message}"
    }
    if (text != null) Text(text)
}
