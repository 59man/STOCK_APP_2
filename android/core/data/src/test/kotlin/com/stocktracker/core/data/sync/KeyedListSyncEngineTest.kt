package com.stocktracker.core.data.sync

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Serializable
private data class Thing(val id: String, val value: String)

class KeyedListSyncEngineTest {

    private fun engine(api: FakePersistApi, states: FakeSyncStateDao) =
        KeyedListSyncEngine(api, states, ListSerializer(Thing.serializer()), keyOf = { it: Thing -> it.id })

    @Test
    fun `first push writes the whole local array and clears dirty`() = runTest {
        val api = FakePersistApi()
        val states = FakeSyncStateDao()
        val sync = engine(api, states)
        var local = listOf(Thing("a", "1"))

        val conflicts = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(conflicts.isEmpty())
        assertEquals("""[{"id":"a","value":"1"}]""", api.store["key"])
        assertEquals(false, states.states["key"]?.dirty)
    }

    @Test
    fun `pull is skipped while the key is dirty`() = runTest {
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"server"}]"""))
        val states = FakeSyncStateDao()
        states.markDirty("key")
        val sync = engine(api, states)
        var writeCalled = false

        val pulled = sync.pull("key") { writeCalled = true }

        assertFalse(pulled)
        assertFalse(writeCalled)
    }

    @Test
    fun `pull applies the server state and records the snapshot`() = runTest {
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"server"}]"""))
        val states = FakeSyncStateDao()
        val sync = engine(api, states)
        var local = emptyList<Thing>()

        val pulled = sync.pull("key") { local = it }

        assertTrue(pulled)
        assertEquals(listOf(Thing("a", "server")), local)
        assertEquals("""[{"id":"a","value":"server"}]""", states.states["key"]?.lastSyncedJson)
    }

    @Test
    fun `push merges a concurrent server addition instead of overwriting it`() = runTest {
        // The exact scenario from the blueprint: web added "a" while the phone
        // was offline and only knows about "b".
        val base = """[]"""
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"added on web"}]"""))
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("b", "added on phone"))

        val conflicts = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(conflicts.isEmpty())
        val idsOnServer = Json.decodeFromString(ListSerializer(Thing.serializer()), api.store["key"]!!).map { it.id }.toSet()
        assertEquals(setOf("a", "b"), idsOnServer)
        assertEquals(setOf("a", "b"), local.map { it.id }.toSet())
        assertEquals(false, states.states["key"]?.dirty)
    }

    @Test
    fun `a genuine conflict blocks the push and leaves the key dirty`() = runTest {
        val base = """[{"id":"a","value":"100"}]"""
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"105"}]""")) // changed remotely
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "110")) // changed locally too, differently
        var writeLocalCalled = false

        val conflicts = sync.push("key", readLocal = { local }, writeLocal = { writeLocalCalled = true })

        assertEquals(1, conflicts.size)
        assertFalse(writeLocalCalled)
        assertEquals("""[{"id":"a","value":"105"}]""", api.store["key"]) // server untouched
        assertEquals(true, states.states["key"]?.dirty) // still dirty — the push never completed
    }

    @Test
    fun `resolveConflict keepLocal makes the next push win cleanly with local's value`() = runTest {
        val base = """[{"id":"a","value":"100"}]"""
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"105"}]""")) // changed remotely
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "110")) // changed locally too, differently

        val firstPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })
        assertEquals(1, firstPush.size) // confirms the conflict actually exists before resolving it

        sync.resolveConflict("key", "a", keepLocal = true, readLocal = { local }, writeLocal = { local = it })

        // Local is untouched by a keepLocal resolution — only the bookkeeping snapshot moved.
        assertEquals(listOf(Thing("a", "110")), local)
        assertEquals("""[{"id":"a","value":"105"}]""", states.states["key"]?.lastSyncedJson)
        assertEquals(true, states.states["key"]?.dirty)

        val secondPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(secondPush.isEmpty())
        assertEquals("""[{"id":"a","value":"110"}]""", api.store["key"]) // local's value now on the server
        assertEquals(listOf(Thing("a", "110")), local)
        assertEquals(false, states.states["key"]?.dirty)
    }

    @Test
    fun `resolveConflict keepRemote overwrites local so the next push is a clean no-op`() = runTest {
        val base = """[{"id":"a","value":"100"}]"""
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"105"}]""")) // changed remotely
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "110")) // changed locally too, differently

        val firstPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })
        assertEquals(1, firstPush.size)

        sync.resolveConflict("key", "a", keepLocal = false, readLocal = { local }, writeLocal = { local = it })

        // Local is overwritten to match remote's current value.
        assertEquals(listOf(Thing("a", "105")), local)
        assertEquals("""[{"id":"a","value":"105"}]""", states.states["key"]?.lastSyncedJson)
        assertEquals(true, states.states["key"]?.dirty)

        val secondPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(secondPush.isEmpty())
        assertEquals("""[{"id":"a","value":"105"}]""", api.store["key"]) // server unchanged
        assertEquals(listOf(Thing("a", "105")), local)
        assertEquals(false, states.states["key"]?.dirty)
    }

    @Test
    fun `resolveConflict keepLocal handles the record being deleted on the server`() = runTest {
        val base = """[{"id":"a","value":"100"}]"""
        val api = FakePersistApi(mapOf("key" to """[]""")) // deleted remotely
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "110")) // edited locally — edit-vs-delete conflict

        val firstPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })
        assertEquals(1, firstPush.size)

        sync.resolveConflict("key", "a", keepLocal = true, readLocal = { local }, writeLocal = { local = it })
        assertEquals("""[]""", states.states["key"]?.lastSyncedJson)

        val secondPush = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(secondPush.isEmpty())
        assertEquals("""[{"id":"a","value":"110"}]""", api.store["key"]) // local's edit restores the record
    }
}
