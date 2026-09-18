package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Mirrors the `Record<"TICKER::YYYY-MM-DD", number>` shape in
 * useManualDividendTaxes.ts — `stock_tracker_div_tax_<portfolioId>`.
 * No `updatedAt` here (unlike ManualPriceEntity) — the source doesn't carry
 * one either, so a same-key sync conflict can't auto-resolve by recency; see
 * the Mobile Sync Blueprint, Phase 3 "Merge".
 */
@Entity(tableName = "div_tax_overrides", primaryKeys = ["portfolioId", "ticker", "date"])
data class DivTaxOverrideEntity(
    val portfolioId: String,
    val ticker: String,
    val date: String,
    val rate: Double,
)

@Dao
interface DivTaxOverrideDao {
    @Query("SELECT * FROM div_tax_overrides WHERE portfolioId = :portfolioId")
    fun observeByPortfolio(portfolioId: String): Flow<List<DivTaxOverrideEntity>>

    @Query("SELECT * FROM div_tax_overrides WHERE portfolioId = :portfolioId")
    suspend fun getByPortfolio(portfolioId: String): List<DivTaxOverrideEntity>

    @Upsert
    suspend fun upsertAll(overrides: List<DivTaxOverrideEntity>)

    @Query("DELETE FROM div_tax_overrides WHERE portfolioId = :portfolioId AND ticker = :ticker AND date = :date")
    suspend fun delete(portfolioId: String, ticker: String, date: String)

    @Query("DELETE FROM div_tax_overrides WHERE portfolioId = :portfolioId")
    suspend fun deleteByPortfolio(portfolioId: String)

    /**
     * Delete-then-insert as one transaction, so Room's observers see a single
     * change instead of an intermediate empty list — without it every sync pull
     * briefly blanks the UI (the list flashes empty on each app resume).
     */
    @Transaction
    suspend fun replaceByPortfolio(portfolioId: String, rows: List<DivTaxOverrideEntity>) {
        deleteByPortfolio(portfolioId)
        upsertAll(rows)
    }
}
