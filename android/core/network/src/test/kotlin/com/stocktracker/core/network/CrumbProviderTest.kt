package com.stocktracker.core.network

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class CrumbProviderTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun provider() = CrumbProvider(
        client = OkHttpClient(),
        cookieUrl = server.url("/cookie").toString(),
        crumbUrl = server.url("/getcrumb").toString(),
    )

    /** The real cookie endpoint answers 404 while still setting the cookie, so status is ignored. */
    private fun enqueueHandshake(crumb: String = "abc/123") {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A1=token; Path=/"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(crumb))
    }

    @Test fun `returns the crumb and the cookie it was issued against`() = runTest {
        enqueueHandshake()
        val p = provider()
        assertEquals("abc/123", p.crumb())
        assertTrue(p.cookieHeader()!!.contains("A1=token"))
    }

    @Test fun `a cached crumb is reused rather than refetched`() = runTest {
        enqueueHandshake()
        val p = provider()
        assertEquals("abc/123", p.crumb())
        assertEquals("abc/123", p.crumb())
        assertEquals(2, server.requestCount)
    }

    @Test fun `concurrent callers share one handshake`() = runTest {
        enqueueHandshake()
        val p = provider()
        val results = (1..5).map { async { p.crumb() } }.awaitAll()
        assertTrue(results.all { it == "abc/123" })
        assertEquals(2, server.requestCount)
    }

    @Test fun `invalidate forces a fresh handshake`() = runTest {
        enqueueHandshake("first")
        enqueueHandshake("second")
        val p = provider()
        assertEquals("first", p.crumb())
        p.invalidate()
        assertEquals("second", p.crumb())
    }

    @Test fun `a failed crumb call yields null instead of throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A1=token"))
        server.enqueue(MockResponse().setResponseCode(500))
        assertNull(provider().crumb())
    }

    @Test fun `an empty crumb body is treated as failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).addHeader("Set-Cookie", "A1=token"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("  "))
        assertNull(provider().crumb())
    }
}
