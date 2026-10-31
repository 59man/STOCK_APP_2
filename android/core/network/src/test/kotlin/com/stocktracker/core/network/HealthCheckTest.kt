package com.stocktracker.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class HealthCheckTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `sends the API key on an authenticated route`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":null}"""))

        val result = HealthCheck.check(server.url("/").toString(), "secret")

        assertTrue(result.isSuccess)
        val request = server.takeRequest()
        assertEquals("secret", request.getHeader("X-API-Key"))
        assertEquals("/api/persist/${PersistKeys.PORTFOLIOS}", request.path)
    }

    @Test
    fun `a wrong key is a failure that says so`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = HealthCheck.check(server.url("/").toString(), "wrong")

        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("API key"))
    }
}
