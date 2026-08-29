package com.stocktracker.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.DeviceRegistry
import com.stocktracker.core.data.SettingsRepository
import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.network.HealthCheck
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val syncCoordinator: SyncCoordinator,
    private val deviceRegistry: DeviceRegistry,
) : ViewModel() {

    private val connectionTest = MutableStateFlow<ConnectionTestState>(ConnectionTestState.Idle)

    val uiState: StateFlow<SettingsUiState> = combine(settingsRepository.settings, connectionTest) { settings, test ->
        SettingsUiState(
            serverUrl = settings.serverUrl,
            apiKey = settings.apiKey,
            displayCurrency = settings.displayCurrency,
            lastSyncedAt = settings.lastSyncedAt,
            connectionTest = test,
            themeMode = settings.themeMode,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.ServerUrlChanged -> viewModelScope.launch { settingsRepository.setServerUrl(action.value) }
            is SettingsAction.ApiKeyChanged -> viewModelScope.launch { settingsRepository.setApiKey(action.value) }
            is SettingsAction.DisplayCurrencyChanged -> viewModelScope.launch { settingsRepository.setDisplayCurrency(action.value) }
            is SettingsAction.ThemeModeChanged -> viewModelScope.launch { settingsRepository.setThemeMode(action.value) }
            SettingsAction.TestConnection -> testConnection()
            SettingsAction.SyncNow -> syncNow()
            SettingsAction.Disconnect -> disconnect()
        }
    }

    /**
     * Stops the app syncing: best-effort unregisters this device from the
     * server, then clears the stored Server URL + API key — that's what
     * actually stops future push/pull attempts. See DeviceRegistry.unregister.
     */
    private fun disconnect() {
        viewModelScope.launch {
            deviceRegistry.unregister()
            settingsRepository.setServerUrl("")
            settingsRepository.setApiKey("")
        }
    }

    private fun testConnection() {
        viewModelScope.launch {
            connectionTest.update { ConnectionTestState.Testing }
            val url = uiState.value.serverUrl
            val result = HealthCheck.check(url)
            connectionTest.update {
                result.fold(
                    onSuccess = { ConnectionTestState.Success },
                    onFailure = { ConnectionTestState.Failed(it.message ?: "Connection failed") },
                )
            }
        }
    }

    private fun syncNow() {
        viewModelScope.launch {
            syncCoordinator.pullAllPortfolios()
            settingsRepository.setLastSyncedAt(Instant.now().toString())
        }
    }
}
