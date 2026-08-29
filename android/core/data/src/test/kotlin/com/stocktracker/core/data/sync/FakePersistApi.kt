package com.stocktracker.core.data.sync

import com.stocktracker.core.database.SyncStateDao
import com.stocktracker.core.database.SyncStateEntity
import com.stocktracker.core.network.PersistAck
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistBody
import com.stocktracker.core.network.PersistResponse

/** In-memory fake of the server's key-value store — no mocking library, just a map. */
class FakePersistApi(initial: Map<String, String> = emptyMap()) : PersistApi {
    val store = initial.toMutableMap()

    override suspend fun get(key: String): PersistResponse = PersistResponse(store[key])

    override suspend fun set(key: String, body: PersistBody): PersistAck {
        store[key] = body.value
        return PersistAck(ok = true)
    }
}

/** In-memory fake of SyncStateDao. */
class FakeSyncStateDao : SyncStateDao {
    val states = mutableMapOf<String, SyncStateEntity>()

    override suspend fun get(key: String): SyncStateEntity? = states[key]
    override fun observe(key: String) = throw NotImplementedError("unused in these tests")
    override suspend fun upsert(state: SyncStateEntity) { states[state.key] = state }
    override suspend fun setDirtyFlag(key: String) { states[key] = states.getValue(key).copy(dirty = true) }
    override suspend fun markDirty(key: String) {
        val existing = states[key]
        states[key] = existing?.copy(dirty = true) ?: SyncStateEntity(key, null, dirty = true)
    }
    override suspend fun isDirty(key: String): Boolean? = states[key]?.dirty
}
