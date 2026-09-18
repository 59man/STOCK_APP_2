package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Mirrors `Portfolio` in src/hooks/usePortfolios.ts — the `stock_tracker_portfolios` sync key. */
@Entity(tableName = "portfolios")
data class PortfolioEntity(
    @PrimaryKey val id: String,
    val name: String,
)

@Dao
interface PortfolioDao {
    @Query("SELECT * FROM portfolios")
    fun observeAll(): Flow<List<PortfolioEntity>>

    @Query("SELECT * FROM portfolios")
    suspend fun getAll(): List<PortfolioEntity>

    @Upsert
    suspend fun upsertAll(portfolios: List<PortfolioEntity>)

    @Query("DELETE FROM portfolios WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Replaces the whole table — used when a pull overwrites local state with the server's array. */
    @Query("DELETE FROM portfolios")
    suspend fun deleteAll()

    /**
     * Delete-then-insert as one transaction, so Room's observers see a single
     * change instead of an intermediate empty list — without it every sync pull
     * briefly blanks the UI (the list flashes empty on each app resume).
     */
    @Transaction
    suspend fun replaceAll(rows: List<PortfolioEntity>) {
        deleteAll()
        upsertAll(rows)
    }
}
