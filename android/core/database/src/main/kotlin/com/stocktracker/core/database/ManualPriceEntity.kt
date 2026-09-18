package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors the per-ticker `{ price, updatedAt }` shape in useManualPrices.ts — `stock_tracker_manual_prices_<portfolioId>`. */
@Entity(tableName = "manual_prices", primaryKeys = ["portfolioId", "ticker"])
data class ManualPriceEntity(
    val portfolioId: String,
    val ticker: String,
    val price: Double,
    val updatedAt: String,
)

@Dao
interface ManualPriceDao {
    @Query("SELECT * FROM manual_prices WHERE portfolioId = :portfolioId")
    fun observeByPortfolio(portfolioId: String): Flow<List<ManualPriceEntity>>

    @Query("SELECT * FROM manual_prices WHERE portfolioId = :portfolioId")
    suspend fun getByPortfolio(portfolioId: String): List<ManualPriceEntity>

    @Upsert
    suspend fun upsertAll(prices: List<ManualPriceEntity>)

    @Query("DELETE FROM manual_prices WHERE portfolioId = :portfolioId AND ticker = :ticker")
    suspend fun delete(portfolioId: String, ticker: String)

    @Query("DELETE FROM manual_prices WHERE portfolioId = :portfolioId")
    suspend fun deleteByPortfolio(portfolioId: String)

    /**
     * Delete-then-insert as one transaction, so Room's observers see a single
     * change instead of an intermediate empty list — without it every sync pull
     * briefly blanks the UI (the list flashes empty on each app resume).
     */
    @Transaction
    suspend fun replaceByPortfolio(portfolioId: String, rows: List<ManualPriceEntity>) {
        deleteByPortfolio(portfolioId)
        upsertAll(rows)
    }
}
