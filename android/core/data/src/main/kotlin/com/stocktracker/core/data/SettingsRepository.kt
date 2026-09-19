package com.stocktracker.core.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import kotlinx.coroutines.flow.map
import java.time.ZoneId
import java.util.UUID
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
    /** How the position list is ordered. Shared by every portfolio. */
    val sortOrder: SortOrder = SortOrder(),
    /** IANA zone id, or empty to follow the device. See [effectiveZoneId]. */
    val timeZoneId: String = "",
)

/**
 * The zone market hours are rendered in.
 *
 * Empty means "follow the device", which is the default: correct when travelling, and no
 * setup for someone who never leaves their own zone. The runCatching guards a stored id that
 * a later tzdb update removed — ZoneId.of would otherwise throw on every read.
 */
fun AppSettings.effectiveZoneId(): ZoneId =
    timeZoneId.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()

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
        val DEVICE_ID = stringPreferencesKey("device_id")
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
        val TIME_ZONE_ID = stringPreferencesKey("time_zone_id")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        AppSettings(
            serverUrl = prefs[Keys.SERVER_URL] ?: "",
            apiKey = prefs[Keys.API_KEY] ?: "",
            displayCurrency = prefs[Keys.DISPLAY_CURRENCY] ?: "CZK",
            lastSyncedAt = prefs[Keys.LAST_SYNCED_AT],
            themeMode = prefs[Keys.THEME_MODE] ?: "system",
            sortOrder = SortOrder(
                // A stored name that no longer parses (a field renamed or removed in a later
                // version) falls back to the default rather than crashing on launch.
                field = prefs[Keys.SORT_FIELD]
                    ?.let { raw -> runCatching { SortField.valueOf(raw) }.getOrNull() }
                    ?: SortField.VALUE,
                ascending = prefs[Keys.SORT_ASCENDING] ?: false,
            ),
            timeZoneId = prefs[Keys.TIME_ZONE_ID] ?: "",
        )
    }

    suspend fun setServerUrl(url: String): Unit { dataStore.edit { it[Keys.SERVER_URL] = url } }
    suspend fun setApiKey(key: String): Unit { dataStore.edit { it[Keys.API_KEY] = key } }
    suspend fun setDisplayCurrency(currency: String): Unit { dataStore.edit { it[Keys.DISPLAY_CURRENCY] = currency } }
    suspend fun setLastSyncedAt(isoTimestamp: String): Unit { dataStore.edit { it[Keys.LAST_SYNCED_AT] = isoTimestamp } }
    suspend fun setThemeMode(mode: String): Unit { dataStore.edit { it[Keys.THEME_MODE] = mode } }
    suspend fun setTimeZoneId(id: String): Unit { dataStore.edit { it[Keys.TIME_ZONE_ID] = id } }
    suspend fun setSortOrder(order: SortOrder): Unit {
        dataStore.edit {
            it[Keys.SORT_FIELD] = order.field.name
            it[Keys.SORT_ASCENDING] = order.ascending
        }
    }

    /**
     * Stable per-install id sent to the server's device registry — generated once,
     * lazily, on first use rather than at app startup, so an app that's never
     * configured a server never bothers creating one.
     */
    suspend fun getOrCreateDeviceId(): String {
        val existing = dataStore.data.first()[Keys.DEVICE_ID]
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        dataStore.edit { it[Keys.DEVICE_ID] = id }
        return id
    }

    /** Clears the device id so a future reconnect registers as a fresh device — see [disconnect] callers in Settings. */
    suspend fun clearDeviceId() = dataStore.edit { it.remove(Keys.DEVICE_ID) }
}
