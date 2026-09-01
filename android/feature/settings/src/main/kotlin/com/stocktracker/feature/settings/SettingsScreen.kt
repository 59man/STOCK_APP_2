package com.stocktracker.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

private val CURRENCIES = listOf("CZK", "USD", "EUR")
private val THEME_MODES = listOf("system" to "System", "light" to "Light", "dark" to "Dark")

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
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                "Get your API key from the 🔑 button in the web app, then paste both here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Local-first text state: `uiState.serverUrl`/`apiKey` round-trip through an async
            // DataStore write (SettingsViewModel.onAction -> settingsRepository.setX). Binding
            // the field's `value` straight to that re-emitted state races fast typing/backspace
            // against the write landing — a late, stale emission snaps the field back and eats
            // keystrokes. Once the user has touched a field, its local copy wins over any further
            // uiState re-emission; before that, it tracks uiState so the persisted value still
            // shows up once DataStore's first real emission lands.
            var localServerUrl by remember { mutableStateOf<String?>(null) }
            var localApiKey by remember { mutableStateOf<String?>(null) }
            var apiKeyVisible by remember { mutableStateOf(false) }

            OutlinedTextField(
                value = localServerUrl ?: uiState.serverUrl,
                onValueChange = {
                    localServerUrl = it
                    onAction(SettingsAction.ServerUrlChanged(it))
                },
                label = { Text("Server URL") },
                placeholder = { Text("http://192.168.1.10:8080") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                value = localApiKey ?: uiState.apiKey,
                onValueChange = {
                    localApiKey = it
                    onAction(SettingsAction.ApiKeyChanged(it))
                },
                label = { Text("API key") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (apiKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = {
                    TextButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Text(if (apiKeyVisible) "Hide" else "Show")
                    }
                },
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

            Column {
                Text("Theme", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    THEME_MODES.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = uiState.themeMode == value,
                            onClick = { onAction(SettingsAction.ThemeModeChanged(value)) },
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = THEME_MODES.size),
                        ) {
                            Text(label)
                        }
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

            var showDisconnectConfirm by remember { mutableStateOf(false) }
            Button(
                onClick = { showDisconnectConfirm = true },
                enabled = uiState.serverUrl.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Disconnect")
            }
            if (showDisconnectConfirm) {
                AlertDialog(
                    onDismissRequest = { showDisconnectConfirm = false },
                    title = { Text("Disconnect from server?") },
                    text = { Text("This stops syncing until you reconnect in Settings.") },
                    confirmButton = {
                        TextButton(onClick = { showDisconnectConfirm = false; onAction(SettingsAction.Disconnect) }) { Text("Disconnect") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDisconnectConfirm = false }) { Text("Cancel") }
                    },
                )
            }
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
