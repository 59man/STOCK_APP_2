package com.stocktracker.core.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Verifies real wire bytes, not just types — PersistJson doesn't set
 * encodeDefaults, so a body field left at its Kotlin default value is silently
 * dropped from the JSON, which the server's manual validation then 400s on.
 * This bit [DeviceHeartbeatBody.platform] once already (it had a `= "android"`
 * default); this test is what would have caught it.
 */
class DeviceApiTest {

    private lateinit var server: MockWebServer
    private lateinit var api: DeviceApi
    private val fakeConfig = object : PersistApiConfig {
        override suspend fun serverUrl(): String = server.url("/").toString()
        override suspend fun apiKey(): String = "test-key-123"
    }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        api = createDeviceApi(fakeConfig)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `heartbeat sends id, label, and platform on the wire`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true,"device":{"id":"d1","label":"Android · Pixel","platform":"android","firstSeen":"x","lastSeen":"x"}}"""))

        api.heartbeat(DeviceHeartbeatBody(id = "d1", label = "Android · Pixel", platform = "android"))

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/api/devices/heartbeat", recorded.path)
        assertEquals("test-key-123", recorded.getHeader("X-API-Key"))
        val body = recorded.body.readUtf8()
        assertTrue("body must include platform, got: $body", body.contains(""""platform":"android""""))
        assertEquals("""{"id":"d1","label":"Android · Pixel","platform":"android"}""", body)
    }

    @Test
    fun `delete hits the id-scoped route`() = runTest {
        server.enqueue(MockResponse().setBody("""{"ok":true}"""))

        api.delete("d1")

        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertEquals("/api/devices/d1", recorded.path)
    }
}
