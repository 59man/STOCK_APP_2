package com.stocktracker.core.data.sync

import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.database.SyncStateEntity
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistBody
import com.stocktracker.core.network.PersistJson
import kotlinx.serialization.KSerializer

/**
 * Generic pull/push for one "whole array per key" storage key, wrapping
 * [threeWayMerge] with the outbox (dirty flag) and last-synced snapshot from
 * SyncStateEntity. One instance is configured per domain type (Position,
 * Portfolio, ManualPriceEntry, ...) by its repository — see
 * PositionRepository for the reference wiring.
 */
class KeyedListSyncEngine<T>(
    private val persistApi: PersistApi,
    private val syncStateDao: SyncStateDao,
    private val serializer: KSerializer<List<T>>,
    private val keyOf: (T) -> String,
    private val resolveConflict: ((local: T, remote: T) -> T)? = null,
) {
    /**
     * Refreshes local state from the server. Returns false without touching
     * local state if [storageKey] is currently dirty (an unpushed local
     * change exists) — the pending push must win that race, not a stale pull.
     */
    suspend fun pull(storageKey: String, writeLocal: suspend (List<T>) -> Unit): Boolean {
        val state = syncStateDao.get(storageKey)
        if (state?.dirty == true) return false

        val remoteJson = persistApi.get(storageKey).value ?: return false
        val remote = PersistJson.decodeFromString(serializer, remoteJson)
        writeLocal(remote)
        syncStateDao.upsert(SyncStateEntity(storageKey, remoteJson, dirty = false))
        return true
    }

    /**
     * Pushes local state, merged against whatever changed on the server
     * since the last sync. Returns the list of unresolved conflicts (empty
     * on a clean push) — a non-empty result means nothing was written to the
     * server or to local state; the key stays dirty for a retry once the
     * caller resolves the conflicts (see ConflictResolution).
     */
    suspend fun push(storageKey: String, readLocal: suspend () -> List<T>, writeLocal: suspend (List<T>) -> Unit): List<MergeConflict<T>> {
        val state = syncStateDao.get(storageKey)
        val base = state?.lastSyncedJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyList()
        val local = readLocal()
        val remoteJson = persistApi.get(storageKey).value
        val remote = remoteJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyList()

        val result = threeWayMerge(base, local, remote, keyOf, resolveConflict)
        if (result.conflicts.isNotEmpty()) return result.conflicts

        val mergedJson = PersistJson.encodeToString(serializer, result.merged)
        persistApi.set(storageKey, PersistBody(mergedJson))
        writeLocal(result.merged)
        syncStateDao.upsert(SyncStateEntity(storageKey, mergedJson, dirty = false))
        return emptyList()
    }

    /** Called right after a local mutation, before the push worker runs. */
    suspend fun markDirty(storageKey: String) = syncStateDao.markDirty(storageKey)

    /**
     * Resolves one reported [MergeConflict] by reconciling local bookkeeping
     * state so the *next* [push] succeeds cleanly instead of reporting the
     * same conflict again — see ConflictCenter. Does not touch the merge
     * algorithm itself; it only patches the record this key disagrees on.
     *
     * - `keepLocal = true`: the base snapshot's copy of this record is
     *   updated to match remote's current value (acknowledging the remote
     *   change), leaving local untouched. On the next push this key reads as
     *   "changed on the local side only", so local wins and gets written to
     *   the server.
     * - `keepLocal = false`: local is overwritten with remote's current
     *   value (or removed if remote no longer has it), and the base snapshot
     *   is updated to match. On the next push this key is unchanged on both
     *   sides — a clean no-op.
     */
    suspend fun resolveConflict(
        storageKey: String,
        recordKey: String,
        keepLocal: Boolean,
        readLocal: suspend () -> List<T>,
        writeLocal: suspend (List<T>) -> Unit,
    ) {
        val state = syncStateDao.get(storageKey)
        val base = state?.lastSyncedJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyList()
        val remoteJson = persistApi.get(storageKey).value
        val remote = remoteJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyList()
        val remoteRecord = remote.firstOrNull { keyOf(it) == recordKey }

        if (!keepLocal) {
            val local = readLocal()
            val newLocal = local.filterNot { keyOf(it) == recordKey } + listOfNotNull(remoteRecord)
            writeLocal(newLocal)
        }

        val newBase = base.filterNot { keyOf(it) == recordKey } + listOfNotNull(remoteRecord)
        val newBaseJson = PersistJson.encodeToString(serializer, newBase)
        syncStateDao.upsert(SyncStateEntity(storageKey, newBaseJson, dirty = true))
    }
}
