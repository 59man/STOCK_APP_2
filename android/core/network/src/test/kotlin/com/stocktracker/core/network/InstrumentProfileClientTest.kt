package com.stocktracker.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstrumentProfileClientTest {

    /** Trimmed from a real EXUS.DE response recorded 2026-09-19. */
    private val fullMeta = """
    {"chart":{"result":[{"meta":{
      "currency":"EUR","symbol":"EXUS.DE","exchangeName":"GER","fullExchangeName":"XETRA",
      "instrumentType":"ETF","firstTradeDate":1710144000,"gmtoffset":7200,"timezone":"CEST",
      "exchangeTimezoneName":"Europe/Berlin","regularMarketPrice":39.825,
      "fiftyTwoWeekHigh":41.04,"fiftyTwoWeekLow":32.84,
      "regularMarketDayHigh":40.145,"regularMarketDayLow":39.75,"regularMarketVolume":90952,
      "longName":"Xtrackers MSCI World ex USA UCITS ETF 1C USD",
      "currentTradingPeriod":{
        "pre":{"start":1789707600,"end":1789714800},
        "regular":{"start":1789714800,"end":1789745400},
        "post":{"start":1789745400,"end":1789756200}}
    }}]}}
    """.trimIndent()

    @Test
    fun `chart meta fills every always-present field`() {
        val p = parseProfileMeta("EXUS.DE", fullMeta)!!
        assertEquals("XETRA", p.exchange)
        assertEquals("ETF", p.instrumentType)
        assertEquals("EUR", p.currency)
        assertEquals("Europe/Berlin", p.exchangeTimeZone)
        assertEquals("Xtrackers MSCI World ex USA UCITS ETF 1C USD", p.longName)
        assertEquals(41.04, p.fiftyTwoWeekHigh!!, 1e-9)
        assertEquals(32.84, p.fiftyTwoWeekLow!!, 1e-9)
        assertEquals(40.145, p.dayHigh!!, 1e-9)
        assertEquals(39.75, p.dayLow!!, 1e-9)
        assertEquals(90952L, p.volume)
        assertEquals(1710144000L, p.firstTradeDate)
        assertEquals(1789714800L, p.tradingPeriods!!.regular!!.startEpoch)
        assertEquals(1789745400L, p.tradingPeriods!!.regular!!.endEpoch)
        assertEquals(1789707600L, p.tradingPeriods!!.pre!!.startEpoch)
        assertEquals(1789756200L, p.tradingPeriods!!.post!!.endEpoch)
    }

    @Test
    fun `a meta without trading periods parses rather than throwing`() {
        val body = """{"chart":{"result":[{"meta":{"currency":"CZK","fullExchangeName":"Prague"}}]}}"""
        val p = parseProfileMeta("KOMB.PR", body)!!
        assertEquals("Prague", p.exchange)
        assertNull(p.tradingPeriods)
    }

    @Test
    fun `an empty result list yields null`() {
        assertNull(parseProfileMeta("ZZZ", """{"chart":{"result":[]}}"""))
    }

    @Test
    fun `quoteSummary unwraps raw values and ignores the formatted ones`() {
        val body = """
        {"quoteSummary":{"result":[{
          "assetProfile":{"longBusinessSummary":"A bank.","sector":"Financial Services",
                          "industry":"Banks","website":"https://www.kb.cz","country":"Czechia"},
          "fundProfile":{"family":"DWS Investment S.A. (ETF)","legalType":"Exchange Traded Fund",
                         "categoryName":"Global Equity",
                         "feesExpensesInvestment":{"annualReportExpenseRatio":{"raw":0.0015,"fmt":"0.15%"},
                                                   "totalNetAssets":{"raw":76148.9,"fmt":"76,148.90"}}}
        }]}}
        """.trimIndent()
        val detail = parseQuoteSummary(body)!!
        assertEquals("A bank.", detail.description)
        assertEquals("Financial Services", detail.sector)
        assertEquals("Banks", detail.industry)
        assertEquals("https://www.kb.cz", detail.website)
        assertEquals("Czechia", detail.country)
        assertEquals("DWS Investment S.A. (ETF)", detail.fundFamily)
        assertEquals("Exchange Traded Fund", detail.legalType)
        assertEquals("Global Equity", detail.category)
        assertEquals(0.0015, detail.expenseRatio!!, 1e-9)
        assertEquals(76148.9, detail.totalNetAssets!!, 1e-9)
    }

    @Test
    fun `a response missing whole modules parses to nulls`() {
        val detail = parseQuoteSummary("""{"quoteSummary":{"result":[{}]}}""")!!
        assertNull(detail.description)
        assertNull(detail.expenseRatio)
    }

    @Test
    fun `the 401 invalid-crumb body is not mistaken for data`() {
        val body = """{"finance":{"result":null,"error":{"code":"Unauthorized","description":"Invalid Crumb"}}}"""
        assertNull(parseQuoteSummary(body))
    }

    @Test
    fun `detail merges onto meta without disturbing the meta fields`() {
        val meta = parseProfileMeta("EXUS.DE", fullMeta)!!
        val merged = meta.withDetail(parseQuoteSummary("""{"quoteSummary":{"result":[{"assetProfile":{"sector":"Tech"}}]}}"""))
        assertEquals("XETRA", merged.exchange)
        assertEquals("Tech", merged.sector)
    }

    @Test
    fun `merging a null detail leaves the profile untouched`() {
        val meta = parseProfileMeta("EXUS.DE", fullMeta)!!
        assertEquals(meta, meta.withDetail(null))
        assertTrue(!meta.hasProfileDetail)
    }
}
