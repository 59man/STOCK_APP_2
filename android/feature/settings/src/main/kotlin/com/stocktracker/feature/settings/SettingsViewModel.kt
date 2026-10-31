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
    private val syncError = MutableStateFlow<String?>(null)

    val uiState: StateFlow<SettingsUiState> = combine(
        settingsRepository.settings, connectionTest, syncError, syncCoordinator.pendingChanges, syncCoordinator.lastPushFailure,
    ) { settings, test, error, pending, pushFailure ->
        SettingsUiState(
            serverUrl = settings.serverUrl,
            apiKey = settings.apiKey,
            displayCurrency = settings.displayCurrency,
            lastSyncedAt = settings.lastSyncedAt,
            connectionTest = test,
            syncError = error,
            pendingChanges = pending,
            lastPushFailure = pushFailure,
            themeMode = settings.themeMode,
            timeZoneId = settings.timeZoneId,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

    fun onAction(action: SettingsAction) {
        when (action) {
            is SettingsAction.ServerUrlChanged -> viewModelScope.launch {
                settingsRepository.setServerUrl(action.value)
                syncCoordinator.forgetMergeBases()
            }
            is SettingsAction.ApiKeyChanged -> viewModelScope.launch { settingsRepository.setApiKey(action.value) }
            is SettingsAction.DisplayCurrencyChanged -> viewModelScope.launch { settingsRepository.setDisplayCurrency(action.value) }
            is SettingsAction.ThemeModeChanged -> viewModelScope.launch { settingsRepository.setThemeMode(action.value) }
            is SettingsAction.TimeZoneChanged -> viewModelScope.launch { settingsRepository.setTimeZoneId(action.id) }
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
            syncCoordinator.forgetMergeBases()
            settingsRepository.setApiKey("")
        }
    }

    private fun testConnection() {
        viewModelScope.launch {
            connectionTest.update { ConnectionTestState.Testing }
            val result = HealthCheck.check(uiState.value.serverUrl, uiState.value.apiKey)
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
            // Keys with unsent changes skip their pull, so with only those a wrong key or a dead server
            // would otherwise look like a successful sync.
            val failure = HealthCheck.check(uiState.value.serverUrl, uiState.value.apiKey).exceptionOrNull()
                ?: syncCoordinator.pullAllPortfolios()
            syncError.value = failure?.let { "Sync failed: ${it.message ?: it::class.simpleName}" }
            if (failure == null) settingsRepository.setLastSyncedAt(Instant.now().toString())
        }
    }
}
