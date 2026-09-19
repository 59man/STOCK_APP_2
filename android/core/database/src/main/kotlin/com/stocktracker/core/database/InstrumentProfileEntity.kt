package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Cached instrument metadata, keyed by ticker.
 *
 * Device-local and never synced: it is derived data in the same category as quotes and FX
 * rates, refetched from Yahoo whenever it goes stale.
 *
 * Trading periods are stored as six nullable epoch columns rather than a serialized blob, so
 * the table needs no TypeConverter and stays readable in a database dump.
 */
@Entity(tableName = "instrument_profiles")
data class InstrumentProfileEntity(
    @PrimaryKey val ticker: String,
    val longName: String? = null,
    val exchange: String? = null,
    val instrumentType: String? = null,
    val currency: String? = null,
    val exchangeTimeZone: String? = null,
    val preStart: Long? = null,
    val preEnd: Long? = null,
    val regularStart: Long? = null,
    val regularEnd: Long? = null,
    val postStart: Long? = null,
    val postEnd: Long? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val volume: Long? = null,
    val firstTradeDate: Long? = null,
    val description: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val website: String? = null,
    val country: String? = null,
    val fundFamily: String? = null,
    val legalType: String? = null,
    val expenseRatio: Double? = null,
    val totalNetAssets: Double? = null,
    val category: String? = null,
    val fetchedAt: Long,
)

@Dao
interface InstrumentProfileDao {
    @Query("SELECT * FROM instrument_profiles WHERE ticker = :ticker")
    suspend fun get(ticker: String): InstrumentProfileEntity?

    @Query("SELECT * FROM instrument_profiles WHERE ticker = :ticker")
    fun observe(ticker: String): Flow<InstrumentProfileEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: InstrumentProfileEntity)
}
