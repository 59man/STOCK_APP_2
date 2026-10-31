package com.stocktracker.core.data

import com.stocktracker.core.database.DailyAnchorDao
import com.stocktracker.core.database.DailyAnchorEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class DailyAnchorRepositoryTest {

    private class FakeDao(entities: List<DailyAnchorEntity>) : DailyAnchorDao {
        private val rows = entities.associateBy { it.ticker to it.localDate }.toMutableMap()
        override suspend fun get(ticker: String, localDate: String) = rows[ticker to localDate]
        override suspend fun upsert(entity: DailyAnchorEntity) { rows[entity.ticker to entity.localDate] = entity }
        override suspend fun pruneBefore(keepFrom: String) { rows.keys.removeAll { it.second < keepFrom } }
    }

    private val prague = ZoneId.of("Europe/Prague")

    /**
     * The repository is a process-wide singleton and Android keeps the process alive for days.
     * Its in-memory map once skipped any ticker it already held, so after midnight every row
     * kept measuring against the previous day's anchor — TABAK.PR showed Monday's move on
     * Tuesday and the portfolio total was off by thousands against the web.
     */
    @Test
    fun `anchors held in memory are replaced once the local date changes`() = runTest {
        val dao = FakeDao(
            listOf(
                DailyAnchorEntity("TABAK.PR", "2026-10-05@Europe/Prague", 17980.0, 1L),
                DailyAnchorEntity("TABAK.PR", "2026-10-06@Europe/Prague", 18900.0, 2L),
                DailyAnchorEntity("EUR->CZK", "2026-10-05@Europe/Prague", 24.41, null),
                DailyAnchorEntity("EUR->CZK", "2026-10-06@Europe/Prague", 24.43, null),
            ),
        )
        val repo = DailyAnchorRepository(dao)

        repo.refresh(listOf("TABAK.PR"), listOf("EUR"), "CZK", prague, Instant.parse("2026-10-05T08:00:00Z"))
        assertEquals(17980.0, repo.anchors.value["TABAK.PR"]?.price)
        assertEquals(24.41, repo.fxAnchors.value["EUR->CZK"])

        repo.refresh(listOf("TABAK.PR"), listOf("EUR"), "CZK", prague, Instant.parse("2026-10-06T08:00:00Z"))
        assertEquals(18900.0, repo.anchors.value["TABAK.PR"]?.price)
        assertEquals(24.43, repo.fxAnchors.value["EUR->CZK"])
    }

    @Test
    fun `local date follows the chosen zone, not UTC`() = runTest {
        val dao = FakeDao(
            listOf(
                DailyAnchorEntity("TABAK.PR", "2026-10-05@Europe/Prague", 17980.0, 1L),
                DailyAnchorEntity("TABAK.PR", "2026-10-06@Europe/Prague", 18900.0, 2L),
            ),
        )
        val repo = DailyAnchorRepository(dao)

        // 22:30 UTC on the 5th is already 00:30 on the 6th in Prague.
        repo.refresh(listOf("TABAK.PR"), emptyList(), "CZK", prague, Instant.parse("2026-10-05T22:30:00Z"))
        assertEquals(18900.0, repo.anchors.value["TABAK.PR"]?.price)
    }

    @Test
    fun `an anchor stored under one zone is not reused for another`() = runTest {
        val dao = FakeDao(listOf(DailyAnchorEntity("TABAK.PR", "2026-10-05@Europe/Prague", 17980.0, 1L)))
        val repo = DailyAnchorRepository(dao)

        assertEquals(null, dao.get("TABAK.PR", "2026-10-05@Asia/Tokyo"))
        repo.refresh(listOf("TABAK.PR"), emptyList(), "CZK", ZoneId.of("Europe/Prague"), Instant.parse("2026-10-05T08:00:00Z"))
        assertEquals(17980.0, repo.anchors.value["TABAK.PR"]?.price)
    }
}
