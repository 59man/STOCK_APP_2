package com.stocktracker.feature.settings

sealed interface ConnectionTestState {
    data object Idle : ConnectionTestState
    data object Testing : ConnectionTestState
    data object Success : ConnectionTestState
    data class Failed(val message: String) : ConnectionTestState
}

data class SettingsUiState(
    val serverUrl: String = "",
    val apiKey: String = "",
    val displayCurrency: String = "CZK",
    val lastSyncedAt: String? = null,
    val connectionTest: ConnectionTestState = ConnectionTestState.Idle,
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
)

sealed interface SettingsAction {
    data class ServerUrlChanged(val value: String) : SettingsAction
    data class ApiKeyChanged(val value: String) : SettingsAction
    data class DisplayCurrencyChanged(val value: String) : SettingsAction
    data class ThemeModeChanged(val value: String) : SettingsAction
    data object TestConnection : SettingsAction
    data object SyncNow : SettingsAction
}
