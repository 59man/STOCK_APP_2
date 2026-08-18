package com.stocktracker.core.data.sync

import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.database.SyncStateEntity
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistBody
import com.stocktracker.core.network.PersistJson
import kotlinx.serialization.KSerializer

/**
 * Same contract as [KeyedListSyncEngine], for the two storage keys that are
 * server-side JSON *objects* rather than arrays — manual prices
 * (`Record<ticker, {price, updatedAt}>`) and dividend-tax overrides
 * (`Record<"TICKER::DATE", rate>`). Reuses [threeWayMerge] by treating each
 * map entry as a `Pair<String, V>` keyed by its own map key.
 */
class KeyedMapSyncEngine<V>(
    private val persistApi: PersistApi,
    private val syncStateDao: SyncStateDao,
    private val serializer: KSerializer<Map<String, V>>,
    private val resolveConflict: ((local: V, remote: V) -> V)? = null,
) {
    suspend fun pull(storageKey: String, writeLocal: suspend (Map<String, V>) -> Unit): Boolean {
        val state = syncStateDao.get(storageKey)
        if (state?.dirty == true) return false

        val remoteJson = persistApi.get(storageKey).value ?: return false
        val remote = PersistJson.decodeFromString(serializer, remoteJson)
        writeLocal(remote)
        syncStateDao.upsert(SyncStateEntity(storageKey, remoteJson, dirty = false))
        return true
    }

    suspend fun push(
        storageKey: String,
        readLocal: suspend () -> Map<String, V>,
        writeLocal: suspend (Map<String, V>) -> Unit,
    ): List<MergeConflict<Pair<String, V>>> {
        val state = syncStateDao.get(storageKey)
        val base = state?.lastSyncedJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyMap()
        val local = readLocal()
        val remoteJson = persistApi.get(storageKey).value
        val remote = remoteJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyMap()

        val pairResolve: ((Pair<String, V>, Pair<String, V>) -> Pair<String, V>)? =
            resolveConflict?.let { rc -> { l, r -> l.first to rc(l.second, r.second) } }

        val result = threeWayMerge(base.toList(), local.toList(), remote.toList(), keyOf = { it.first }, resolveConflict = pairResolve)
        if (result.conflicts.isNotEmpty()) return result.conflicts

        val mergedMap = result.merged.toMap()
        val mergedJson = PersistJson.encodeToString(serializer, mergedMap)
        persistApi.set(storageKey, PersistBody(mergedJson))
        writeLocal(mergedMap)
        syncStateDao.upsert(SyncStateEntity(storageKey, mergedJson, dirty = false))
        return emptyList()
    }

    suspend fun markDirty(storageKey: String) = syncStateDao.markDirty(storageKey)

    /** Map-keyed counterpart of [KeyedListSyncEngine.resolveConflict] — see that doc for the reconciliation strategy. */
    suspend fun resolveConflict(
        storageKey: String,
        recordKey: String,
        keepLocal: Boolean,
        readLocal: suspend () -> Map<String, V>,
        writeLocal: suspend (Map<String, V>) -> Unit,
    ) {
        val state = syncStateDao.get(storageKey)
        val base = state?.lastSyncedJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyMap()
        val remoteJson = persistApi.get(storageKey).value
        val remote = remoteJson?.let { PersistJson.decodeFromString(serializer, it) } ?: emptyMap()
        val remoteValue = remote[recordKey]

        if (!keepLocal) {
            val local = readLocal()
            val newLocal = if (remoteValue != null) local + (recordKey to remoteValue) else local - recordKey
            writeLocal(newLocal)
        }

        val newBase = if (remoteValue != null) base + (recordKey to remoteValue) else base - recordKey
        val newBaseJson = PersistJson.encodeToString(serializer, newBase)
        syncStateDao.upsert(SyncStateEntity(storageKey, newBaseJson, dirty = true))
    }
}
