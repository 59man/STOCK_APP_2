package com.stocktracker.core.data

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.network.DividendSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DividendRepositoryTest {

    private fun events() = listOf(DividendEvent("2025-01-01", 1.0, "CZK"))

    /**
     * PortfolioListViewModel drives fetchTickers() from a collectLatest, so a
     * new positions emission cancels the previous fetch while it is still in
     * flight. Any ticker whose fetch job was cancelled before its body ran must
     * be fetchable again on the next cycle — otherwise it stays marked
     * in-flight for the life of the process and its dividends never appear.
     * Same failure QuoteRepository was fixed for in be70bc9.
     */
    @Test
    fun `ticker cancelled before its fetch job starts is retried on the next cycle`() = runTest {
        var cancelDuringFirstFetch = true
        val job = Job()
        val scope = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val source = DividendSource { _ ->
            // Cancels the enclosing scope while the *first* ticker is being
            // fetched, so the second ticker's launch is cancelled before its
            // body ever runs.
            if (cancelDuringFirstFetch) job.cancel()
            events()
        }
        val repo = DividendRepository(source)

        scope.launch { repo.fetchTickers(listOf("AAA", "BBB")) }
        advanceUntilIdle()
        cancelDuringFirstFetch = false

        repo.fetchTickers(listOf("AAA", "BBB"))
        advanceUntilIdle()

        assertEquals(setOf("AAA", "BBB"), repo.dividends.value.keys)
    }

    @Test
    fun `a ticker already fetched is not fetched twice`() = runTest {
        val calls = mutableListOf<String>()
        val repo = DividendRepository(DividendSource { ticker -> calls += ticker; events() })

        repo.fetchTickers(listOf("AAA", "aaa", "BBB"))
        advanceUntilIdle()
        repo.fetchTickers(listOf("AAA", "BBB"))
        advanceUntilIdle()

        assertEquals(listOf("AAA", "BBB"), calls.sorted())
    }

    @Test
    fun `a failed fetch is not cached and retries on the next cycle`() = runTest {
        var fail = true
        val calls = mutableListOf<String>()
        val repo = DividendRepository(
            DividendSource { ticker ->
                calls += ticker
                if (fail) throw RuntimeException("boom") else events()
            },
        )

        repo.fetchTickers(listOf("AAA"))
        advanceUntilIdle()
        assertEquals(emptySet<String>(), repo.dividends.value.keys)

        fail = false
        repo.fetchTickers(listOf("AAA"))
        advanceUntilIdle()

        assertEquals(listOf("AAA", "AAA"), calls)
        assertEquals(setOf("AAA"), repo.dividends.value.keys)
    }
}
