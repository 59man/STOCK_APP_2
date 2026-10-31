package com.stocktracker.core.data.sync

import com.stocktracker.core.database.SyncStateEntity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyedMapSyncEngineTest {

    private fun engine(api: FakePersistApi, states: FakeSyncStateDao) =
        KeyedMapSyncEngine(api, states, MapSerializer(String.serializer(), Double.serializer()))

    @Test
    fun `push to a server that has lost the key keeps local entries and restores them`() = runTest {
        val api = FakePersistApi()
        val states = FakeSyncStateDao()
        states.states["key"] = SyncStateEntity("key", """{"A":0.15,"B":0.3}""", dirty = true)
        val sync = engine(api, states)
        var local = mapOf("A" to 0.15, "B" to 0.3, "C" to 0.2)

        val conflicts = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(conflicts.isEmpty())
        assertEquals(mapOf("A" to 0.15, "B" to 0.3, "C" to 0.2), local)
        assertTrue(api.store["key"]!!.contains("\"B\""))
    }

    @Test
    fun `resolveConflict keepRemote does not delete the local entry when the server has lost the key`() = runTest {
        val api = FakePersistApi()
        val states = FakeSyncStateDao()
        states.states["key"] = SyncStateEntity("key", """{"A":0.15}""", dirty = true)
        val sync = engine(api, states)
        var local = mapOf("A" to 0.25)

        sync.resolveConflict("key", "A", keepLocal = false, readLocal = { local }, writeLocal = { local = it })

        assertEquals(mapOf("A" to 0.15), local)
    }
}
