package com.stocktracker.core.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore by preferencesDataStore(name = "settings")

data class AppSettings(
    val serverUrl: String = "",
    val apiKey: String = "",
    val displayCurrency: String = "CZK",
    val lastSyncedAt: String? = null,
    /** "system" | "light" | "dark" */
    val themeMode: String = "system",
)

/**
 * Server URL / API key / display currency — read on every network and sync
 * call (not baked into a Retrofit base URL), so changing them in Settings
 * takes effect immediately with no app restart.
 */
@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val dataStore = context.dataStore

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val DISPLAY_CURRENCY = stringPreferencesKey("display_currency")
        val LAST_SYNCED_AT = stringPreferencesKey("last_synced_at")
        val THEME_MODE = stringPreferencesKey("theme_mode")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: "",
            apiKey = prefs[Keys.API_KEY] ?: "",
            displayCurrency = prefs[Keys.DISPLAY_CURRENCY] ?: "CZK",
            lastSyncedAt = prefs[Keys.LAST_SYNCED_AT],
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
        )
    }

    suspend fun setServerUrl(url: String) = dataStore.edit { it[Keys.SERVER_URL] = url }
    suspend fun setApiKey(key: String) = dataStore.edit { it[Keys.API_KEY] = key }
    suspend fun setDisplayCurrency(currency: String) = dataStore.edit { it[Keys.DISPLAY_CURRENCY] = currency }
    suspend fun setLastSyncedAt(isoTimestamp: String) = dataStore.edit { it[Keys.LAST_SYNCED_AT] = isoTimestamp }
    suspend fun setThemeMode(mode: String) = dataStore.edit { it[Keys.THEME_MODE] = mode }
}
