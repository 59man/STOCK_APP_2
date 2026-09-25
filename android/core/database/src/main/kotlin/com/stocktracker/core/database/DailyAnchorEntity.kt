package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The price a ticker was worth at the user's local midnight, for one local date.
 *
 * Cached in Room rather than memory because an anchor is fixed for the whole day: a cold start
 * in the morning would otherwise refetch every holding's anchor for no new information.
 *
 * `price` null means Yahoo definitively does not carry the ticker (HTTP 404) and is cached
 * deliberately so it is not retried on every refresh. Transient failures are never written here.
 */
@Entity(tableName = "daily_anchors", primaryKeys = ["ticker", "localDate"])
data class DailyAnchorEntity(
    val ticker: String,
    /** ISO date in the user's zone — the anchor belongs to a calendar day, not an instant. */
    val localDate: String,
    val price: Double?,
    val lastTradedAt: Long?,
)

@Dao
interface DailyAnchorDao {
    @Query("SELECT * FROM daily_anchors WHERE ticker = :ticker AND localDate = :localDate")
    suspend fun get(ticker: String, localDate: String): DailyAnchorEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: DailyAnchorEntity)

    /** Yesterday's anchors are never read again; keeping them would grow the table forever. */
    @Query("DELETE FROM daily_anchors WHERE localDate < :keepFrom")
    suspend fun pruneBefore(keepFrom: String)
}
