package com.stocktracker.core.network

import com.stocktracker.core.model.FundProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class FundQuoteClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: FundQuoteClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val base = server.url("/").toString().trimEnd('/')
        client = FundQuoteClient(onemarketsBase = base, fioBase = "$base/fio")
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `parses onemarkets rows, taking the last two`() {
        assertEquals(FundPoint(102.5, 101.0, "2026-10-30"), parseOnemarketsCsv("29.10.2026;101.0;; 30.10.2026;102.5;;\n"))
    }

    @Test
    fun `a single row is its own previous close`() {
        assertEquals(FundPoint(5.0, 5.0, "2026-10-30"), parseOnemarketsCsv("30.10.2026;5.0;;"))
    }

    @Test
    fun `parses the Fio chart`() {
        assertEquals(FundPoint(2.0, 1.0, "2026-10-30"), parseFioFundJson("""[{"x":"2026-10-29","value":1.0},{"x":"2026-10-30","value":2.0}]"""))
    }

    @Test
    fun `an onemarkets fund becomes a CZK quote, asked for by ISIN`() = runTest {
        server.enqueue(MockResponse().setBody("29.10.2026;100.0;; 30.10.2026;110.0;;"))

        val quote = client.fetchQuote("LU2606422355", FundProvider.Onemarkets, LocalDate.of(2026, 10, 31))

        assertEquals(110.0, quote.price, 0.0)
        assertEquals(10.0, quote.change, 1e-9)
        assertEquals(10.0, quote.changePercent, 1e-9)
        assertEquals("CZK", quote.currency)
        val path = server.takeRequest().path.orEmpty()
        assertTrue(path, path.contains("isin=LU2606422355") && path.contains("start=20261017") && path.contains("end=20261031"))
    }

    @Test
    fun `a Fio fund sends the session cookie it got from a fund page, with the signal headers`() = runTest {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "PHPSESSID=abc; path=/; HttpOnly").setBody("<html>"))
        server.enqueue(MockResponse().setBody("""[{"x":"2026-10-30","value":2.5}]"""))

        val quote = client.fetchQuote("FIOG.PR", FundProvider.FioFund("some-fund"))

        assertEquals(2.5, quote.price, 0.0)
        server.takeRequest()
        val chart = server.takeRequest()
        assertEquals("/fio/some-fund?do=getFundChartData", chart.path)
        assertEquals("PHPSESSID=abc", chart.getHeader("Cookie"))
        assertEquals("XMLHttpRequest", chart.getHeader("X-Requested-With"))
    }
}
