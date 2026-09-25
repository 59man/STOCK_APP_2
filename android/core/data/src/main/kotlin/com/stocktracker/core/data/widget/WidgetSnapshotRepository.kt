package com.stocktracker.core.data.widget

import android.content.Context
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.widgetStore by preferencesDataStore(name = "widget_snapshot")

/** The figures the home-screen widget shows, exactly as the app last computed them. */
data class WidgetSnapshot(
    val portfolioName: String,
    val totalValue: Double,
    val dailyChange: Double,
    val dailyChangePercent: Double,
    val currency: String,
    val updatedAtMillis: Long,
)

/**
 * The widget never computes anything itself: recomputing in the background without midnight
 * anchors would give a different today's change than the app shows. The app writes a snapshot
 * after each refresh and the widget displays it with its age.
 */
@Singleton
class WidgetSnapshotRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.widgetStore

    private object Keys {
        val NAME = stringPreferencesKey("name")
        val VALUE = doublePreferencesKey("value")
        val CHANGE = doublePreferencesKey("change")
        val PERCENT = doublePreferencesKey("percent")
        val CURRENCY = stringPreferencesKey("currency")
        val UPDATED = longPreferencesKey("updated")
    }

    val snapshot: Flow<WidgetSnapshot?> = store.data.map { p ->
        val name = p[Keys.NAME] ?: return@map null
        WidgetSnapshot(
            portfolioName = name,
            totalValue = p[Keys.VALUE] ?: 0.0,
            dailyChange = p[Keys.CHANGE] ?: 0.0,
            dailyChangePercent = p[Keys.PERCENT] ?: 0.0,
            currency = p[Keys.CURRENCY] ?: "CZK",
            updatedAtMillis = p[Keys.UPDATED] ?: 0L,
        )
    }

    suspend fun write(s: WidgetSnapshot) {
        store.edit {
            it[Keys.NAME] = s.portfolioName
            it[Keys.VALUE] = s.totalValue
            it[Keys.CHANGE] = s.dailyChange
            it[Keys.PERCENT] = s.dailyChangePercent
            it[Keys.CURRENCY] = s.currency
            it[Keys.UPDATED] = s.updatedAtMillis
        }
    }
}
