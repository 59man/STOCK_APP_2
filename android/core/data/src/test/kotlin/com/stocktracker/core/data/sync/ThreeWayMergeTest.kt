package com.stocktracker.core.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private data class Item(val id: String, val value: String, val updatedAt: String = "")

class ThreeWayMergeTest {

    @Test
    fun `two independent additions from two devices both survive`() {
        // The exact scenario walked through with the user: web adds A while
        // the phone is offline, phone adds B, phone reconnects and pushes.
        val base = listOf(Item("x", "existing"))
        val local = listOf(Item("x", "existing"), Item("b", "added on phone"))
        val remote = listOf(Item("x", "existing"), Item("a", "added on web"))

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertTrue(result.conflicts.isEmpty())
        assertEquals(setOf("x", "a", "b"), result.merged.map { it.id }.toSet())
    }

    @Test
    fun `edit on only one side wins cleanly, no conflict`() {
        val base = listOf(Item("x", "100"))
        val local = listOf(Item("x", "105")) // edited locally
        val remote = listOf(Item("x", "100")) // unchanged remotely

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertTrue(result.conflicts.isEmpty())
        assertEquals("105", result.merged.single { it.id == "x" }.value)
    }

    @Test
    fun `same record edited differently on both sides is a reported conflict`() {
        // The closed-position sell-price scenario: web edits to 105, phone
        // (independently, while offline) edits to 110.
        val base = listOf(Item("lot1", "100"))
        val local = listOf(Item("lot1", "110"))
        val remote = listOf(Item("lot1", "105"))

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertEquals(1, result.conflicts.size)
        val conflict = result.conflicts[0]
        assertEquals("lot1", conflict.key)
        assertEquals("110", conflict.local?.value)
        assertEquals("105", conflict.remote?.value)
        // Kept provisionally so a push isn't silently empty while the conflict is pending.
        assertEquals("110", result.merged.single { it.id == "lot1" }.value)
    }

    @Test
    fun `identical edit on both sides is not a conflict`() {
        val base = listOf(Item("x", "100"))
        val local = listOf(Item("x", "105"))
        val remote = listOf(Item("x", "105"))

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertTrue(result.conflicts.isEmpty())
        assertEquals("105", result.merged.single { it.id == "x" }.value)
    }

    @Test
    fun `a key with a real timestamp auto-resolves by recency instead of conflicting`() {
        // Manual prices: ManualPriceEntity carries updatedAt, so this is a
        // genuine last-write-wins, not an accident of push order.
        val base = listOf(Item("AAPL", "100", updatedAt = "2024-01-01"))
        val local = listOf(Item("AAPL", "110", updatedAt = "2024-06-01"))
        val remote = listOf(Item("AAPL", "105", updatedAt = "2024-03-01"))

        val result = threeWayMerge(
            base, local, remote, keyOf = { it.id },
            resolveConflict = { l, r -> if (l.updatedAt >= r.updatedAt) l else r },
        )

        assertTrue(result.conflicts.isEmpty())
        assertEquals("110", result.merged.single { it.id == "AAPL" }.value) // local is more recent
    }

    @Test
    fun `deletion on one side only is respected`() {
        val base = listOf(Item("x", "100"), Item("y", "200"))
        val local = listOf(Item("y", "200")) // x deleted locally
        val remote = listOf(Item("x", "100"), Item("y", "200")) // unchanged remotely

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertTrue(result.conflicts.isEmpty())
        assertEquals(setOf("y"), result.merged.map { it.id }.toSet())
    }

    @Test
    fun `edit on one side plus delete on the other is a conflict, edit kept provisionally`() {
        val base = listOf(Item("x", "100"))
        val local = listOf(Item("x", "150")) // edited locally
        val remote = emptyList<Item>() // deleted remotely

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertEquals(1, result.conflicts.size)
        assertEquals("150", result.conflicts[0].local?.value)
        assertEquals(null, result.conflicts[0].remote)
        assertEquals("150", result.merged.single { it.id == "x" }.value)
    }

    @Test
    fun `deletion on both sides leaves nothing behind`() {
        val base = listOf(Item("x", "100"))
        val result = threeWayMerge(base, emptyList(), emptyList(), keyOf = { it.id })
        assertTrue(result.conflicts.isEmpty())
        assertTrue(result.merged.isEmpty())
    }

    @Test
    fun `two records sharing a name but different ids never collapse into one`() {
        // The "two portfolios both called Retirement" case — merge is
        // strictly id-keyed, so distinct ids always survive independently
        // no matter what their (non-key) fields contain.
        val base = emptyList<Item>()
        val local = listOf(Item("phone-id", "Retirement"))
        val remote = listOf(Item("web-id", "Retirement"))

        val result = threeWayMerge(base, local, remote, keyOf = { it.id })

        assertTrue(result.conflicts.isEmpty())
        assertEquals(2, result.merged.size)
        assertEquals(setOf("phone-id", "web-id"), result.merged.map { it.id }.toSet())
    }
}
