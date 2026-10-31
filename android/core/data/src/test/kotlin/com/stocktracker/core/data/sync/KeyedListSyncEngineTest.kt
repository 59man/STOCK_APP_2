package com.stocktracker.core.data.sync

import com.stocktracker.core.database.SyncStateEntity
import com.stocktracker.core.network.PersistApi
import com.stocktracker.core.network.PersistResponse
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
    fun `push to a server that has lost the key keeps local records and restores them`() = runTest {
        val base = """[{"id":"a","value":"1"},{"id":"b","value":"2"}]"""
        val api = FakePersistApi() // key absent, e.g. the server came back empty
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "1"), Thing("b", "2"), Thing("c", "added on phone"))

        val conflicts = sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertTrue(conflicts.isEmpty())
        assertEquals(setOf("a", "b", "c"), local.map { it.id }.toSet())
        val idsOnServer = Json.decodeFromString(ListSerializer(Thing.serializer()), api.store["key"]!!).map { it.id }.toSet()
        assertEquals(setOf("a", "b", "c"), idsOnServer)
    }

    @Test
    fun `push still removes a record deleted locally when the server has lost the key`() = runTest {
        val base = """[{"id":"a","value":"1"},{"id":"b","value":"2"}]"""
        val api = FakePersistApi()
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "1"))

        sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertEquals(listOf(Thing("a", "1")), local)
        assertEquals("""[{"id":"a","value":"1"}]""", api.store["key"])
    }

    @Test
    fun `an explicitly empty server array is still a real remote delete`() = runTest {
        val base = """[{"id":"a","value":"1"}]"""
        val api = FakePersistApi(mapOf("key" to """[]"""))
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "1"))

        sync.push("key", readLocal = { local }, writeLocal = { local = it })

        assertEquals(emptyList<Thing>(), local)
    }

    @Test
    fun `resolveConflict keepRemote does not delete the local record when the server has lost the key`() = runTest {
        val base = """[{"id":"a","value":"100"}]"""
        val api = FakePersistApi()
        val states = FakeSyncStateDao()
        states.states["key"] = com.stocktracker.core.database.SyncStateEntity("key", base, dirty = true)
        val sync = engine(api, states)
        var local = listOf(Thing("a", "110"))

        sync.resolveConflict("key", "a", keepLocal = false, readLocal = { local }, writeLocal = { local = it })

        assertEquals(listOf(Thing("a", "100")), local)
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

    @Test
    fun `an edit made while the pull was on the network survives it`() = runTest {
        val states = FakeSyncStateDao()
        val api = object : PersistApi by FakePersistApi(mapOf("key" to """[{"id":"a","value":"server"}]""")) {
            override suspend fun get(key: String): PersistResponse {
                states.markDirty(key) // the user saves a lot while this GET is in flight
                return PersistResponse("""[{"id":"a","value":"server"}]""")
            }
        }
        val sync = KeyedListSyncEngine(api, states, ListSerializer(Thing.serializer()), keyOf = { it: Thing -> it.id })
        var overwritten = false

        val pulled = sync.pull("key") { overwritten = true }

        assertFalse(pulled)
        assertFalse(overwritten)
        assertEquals(true, states.states["key"]?.dirty)
    }

    @Test
    fun `an edit made during a push is kept and stays dirty for the next one`() = runTest {
        val api = FakePersistApi(mapOf("key" to """[{"id":"a","value":"1"},{"id":"phone","value":"1"}]"""))
        val states = FakeSyncStateDao()
        states.upsert(SyncStateEntity("key", """[{"id":"a","value":"1"}]""", dirty = true))
        val sync = engine(api, states)
        val edited = listOf(Thing("a", "1"), Thing("mine", "1"), Thing("later", "1"))
        var reads = 0
        var local = listOf(Thing("a", "1"), Thing("mine", "1"))

        sync.push("key", readLocal = { if (reads++ == 0) local else edited.also { local = it } }, writeLocal = { local = it })

        assertEquals(edited, local)
        assertEquals(true, states.states["key"]?.dirty)
        // The base is still the old one, so the next push sees "phone" as a remote add, not a local delete.
        assertEquals("""[{"id":"a","value":"1"}]""", states.states["key"]?.lastSyncedJson)
    }

    @Test
    fun `a write landing after the GET refuses the push and changes nothing here`() = runTest {
        val states = FakeSyncStateDao()
        val fake = FakePersistApi(mapOf("key" to """[{"id":"a","value":"1"}]"""))
        val api = object : PersistApi by fake {
            override suspend fun get(key: String): PersistResponse {
                val seen = fake.get(key)
                fake.store[key] = """[{"id":"a","value":"1"},{"id":"web","value":"1"}]""" // the web saves right after
                return seen
            }
        }
        states.upsert(SyncStateEntity("key", """[{"id":"a","value":"1"}]""", dirty = true))
        var local = listOf(Thing("a", "1"), Thing("mine", "1"))
        var wrote = false
        val sync = KeyedListSyncEngine(api, states, ListSerializer(Thing.serializer()), keyOf = { it: Thing -> it.id })

        org.junit.Assert.assertThrows(VersionConflict::class.java) {
            kotlinx.coroutines.runBlocking { sync.push("key", readLocal = { local }, writeLocal = { wrote = true }) }
        }

        assertFalse(wrote)
        assertEquals(true, states.states["key"]?.dirty)
        assertTrue(fake.store["key"]!!.contains("web")) // what the web saved is still there
    }

    private fun listEngine(api: FakePersistApi, states: FakeSyncStateDao) = KeyedListSyncEngine(
        api, states, ListSerializer(Thing.serializer()), keyOf = { it: Thing -> it.id }, mergeWhenRemoteIsUnrelated = true,
    )

    @Test
    fun `a portfolio list sharing nothing with the last sync is united, not taken as deletions`() = runTest {
        val api = FakePersistApi(mapOf("key" to """[{"id":"new","value":"Main Portfolio"}]"""))
        val states = FakeSyncStateDao()
        states.upsert(SyncStateEntity("key", """[{"id":"old","value":"Real"}]""", dirty = false))
        val sync = listEngine(api, states)
        var local = listOf(Thing("old", "Real"))

        assertFalse(sync.pull("key") { local = it })
        assertEquals(listOf(Thing("old", "Real")), local)
        assertEquals(true, states.states["key"]?.dirty)

        assertTrue(sync.push("key", readLocal = { local }, writeLocal = { local = it }).isEmpty())
        assertEquals(setOf("old", "new"), local.map { it.id }.toSet())
        assertTrue(api.store["key"]!!.contains("old") && api.store["key"]!!.contains("new"))
    }

    @Test
    fun `a portfolio really deleted elsewhere still goes away`() = runTest {
        val api = FakePersistApi(mapOf("key" to """[{"id":"keep","value":"1"}]"""))
        val states = FakeSyncStateDao()
        states.upsert(SyncStateEntity("key", """[{"id":"keep","value":"1"},{"id":"gone","value":"1"}]""", dirty = false))
        var local = listOf(Thing("keep", "1"), Thing("gone", "1"))

        assertTrue(listEngine(api, states).pull("key") { local = it })

        assertEquals(listOf(Thing("keep", "1")), local)
    }
}
