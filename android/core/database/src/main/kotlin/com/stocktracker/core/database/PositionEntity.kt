package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import com.stocktracker.core.model.PositionType
import kotlinx.coroutines.flow.Flow

/**
 * Mirrors `Position` in src/types/index.ts — the per-portfolio
 * `stock_tracker_positions_<portfolioId>` sync key, one row per lot.
 * `buyDate`/`sellDate` stay as plain ISO strings — see core:model.Position
 * for why (lexicographic comparison, not parsed instants).
 */
@Entity(tableName = "positions", indices = [Index("portfolioId")])
data class PositionEntity(
    @PrimaryKey val id: String,
    val portfolioId: String,
    val ticker: String,
    val name: String,
    val type: PositionType,
    val quantity: Double,
    val buyPrice: Double,
    val buyDate: String,
    val currency: String,
    val broker: String? = null,
    val isin: String? = null,
    val sellPrice: Double? = null,
    val sellDate: String? = null,
)

@Dao
interface PositionDao {
    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId")
    fun observeByPortfolio(portfolioId: String): Flow<List<PositionEntity>>

    @Query("SELECT * FROM positions WHERE portfolioId = :portfolioId")
    suspend fun getByPortfolio(portfolioId: String): List<PositionEntity>

    @Upsert
    suspend fun upsertAll(positions: List<PositionEntity>)

    @Query("DELETE FROM positions WHERE id = :id")
    suspend fun deleteById(id: String)

    /** Replaces one portfolio's whole lot list — used when a pull overwrites local state. */
    @Query("DELETE FROM positions WHERE portfolioId = :portfolioId")
    suspend fun deleteByPortfolio(portfolioId: String)
}
