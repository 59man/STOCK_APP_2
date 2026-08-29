package com.stocktracker.core.database

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * One row per synced storage key (e.g. `stock_tracker_positions_<id>`).
 * [lastSyncedJson] is the three-way merge's "base" — the array exactly as it
 * stood after the last successful pull or push. [dirty] marks a key with a
 * local mutation not yet confirmed pushed; a pull must skip overwriting a
 * dirty key (see the Mobile Sync Blueprint, Phase 3 "Race").
 */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val lastSyncedJson: String?,
    val dirty: Boolean,
)

@Dao
interface SyncStateDao {
    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): SyncStateEntity?

    @Query("SELECT * FROM sync_state WHERE `key` = :key")
    fun observe(key: String): Flow<SyncStateEntity?>

    @Upsert
    suspend fun upsert(state: SyncStateEntity)

    @Query("UPDATE sync_state SET dirty = 1 WHERE `key` = :key")
    suspend fun setDirtyFlag(key: String)

    /**
     * Marks [key] dirty, creating its row first if none exists yet — a plain
     * `UPDATE ... WHERE key = :key` is a no-op for a brand-new portfolio's
     * positions/manual-prices/div-tax keys, which have never been pulled and
     * so have no row. minSdk 26 predates SQLite's UPSERT clause (3.24+), so
     * this is a get-then-branch inside a transaction rather than a single
     * `INSERT ... ON CONFLICT DO UPDATE` statement.
     */
    @Transaction
    suspend fun markDirty(key: String) {
        if (get(key) != null) setDirtyFlag(key) else upsert(SyncStateEntity(key = key, lastSyncedJson = null, dirty = true))
    }

    @Query("SELECT dirty FROM sync_state WHERE `key` = :key")
    suspend fun isDirty(key: String): Boolean?
}
