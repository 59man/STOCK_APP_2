package com.stocktracker.core.network

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@Serializable
private data class Widget(val id: String, val qty: Double)

/**
 * Verifies the double-JSON-encoding contract against real wire bytes, not
 * just types — this is the bug the Mobile Sync Blueprint calls out as most
 * likely: forgetting the second decode/encode step.
 */
class PersistApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: PersistApi
    private val fakeConfig = object : PersistApiConfig {
        override suspend fun serverUrl(): String = server.url("/").toString()
        override suspend fun apiKey(): String = "test-key-123"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = createPersistApi(fakeConfig)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `setEncoded double-encodes the payload on the wire`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.setEncoded("stock_tracker_positions_p1", listOf(Widget("a", 3.0)))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/persist/stock_tracker_positions_p1", recorded.path)
        assertEquals("test-key-123", recorded.getHeader("X-API-Key"))
        // The outer JSON's "value" field must be a STRING containing escaped inner JSON —
        // not a nested object. This is exactly what a missing double-encode would get wrong.
        assertEquals("""{"value":"[{\"id\":\"a\",\"qty\":3.0}]"}""", recorded.body.readUtf8())
    }

    @Test
    fun `getDecoded decodes the double-encoded response back into real objects`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":"[{\"id\":\"a\",\"qty\":3.0}]"}"""))

        val result = api.getDecoded<List<Widget>>("stock_tracker_positions_p1")

        assertEquals(listOf(Widget("a", 3.0)), result)
    }

    @Test
    fun `getDecoded returns null when the server has no value for the key`() = runTest {
        server.enqueue(MockResponse().setBody("""{"value":null}"""))

        val result = api.getDecoded<List<Widget>>("stock_tracker_positions_missing")

        assertEquals(null, result)
    }
}
