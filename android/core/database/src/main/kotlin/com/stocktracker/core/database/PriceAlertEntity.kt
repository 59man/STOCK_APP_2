package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * A price alert: notify once when [ticker] crosses [threshold] in [direction]. Device-local and
 * never synced — the server has no alert concept.
 *
 * [armed] is false after the alert fires and flips back only once the price returns across the
 * threshold, so one crossing produces one notification rather than one every check.
 */
@Entity(tableName = "price_alerts")
data class PriceAlertEntity(
    @PrimaryKey val id: String,
    val ticker: String,
    /** true = notify at or above [threshold], false = at or below. */
    val above: Boolean,
    val threshold: Double,
    /** Currency [threshold] is expressed in; the quote is converted into it before comparing. */
    val currency: String,
    val enabled: Boolean = true,
    val armed: Boolean = true,
    val lastTriggeredAt: Long? = null,
)

@Dao
interface PriceAlertDao {
    @Query("SELECT * FROM price_alerts ORDER BY ticker, threshold")
    fun observeAll(): Flow<List<PriceAlertEntity>>

    @Query("SELECT * FROM price_alerts WHERE enabled = 1")
    suspend fun enabled(): List<PriceAlertEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(alert: PriceAlertEntity)

    @Query("DELETE FROM price_alerts WHERE id = :id")
    suspend fun delete(id: String)
}
