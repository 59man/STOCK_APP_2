package com.stocktracker.core.importer

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonReimportParserTest {

    @Test
    fun `bare position array is accepted`() {
        val json = Json.parseToJsonElement(
            """[{"ticker":"AAPL","quantity":10,"buyPrice":150.0,"buyDate":"2024-05-01","currency":"USD"}]"""
        )
        val result = parsePositionsFromJson(json)!!
        assertEquals(1, result.valid.size)
        assertEquals("AAPL", result.valid[0].ticker)
        assertEquals(0, result.skipped)
    }

    @Test
    fun `versioned export shape with overrides is accepted`() {
        val json = Json.parseToJsonElement(
            """{"version":1,"positions":[{"ticker":"AAPL","quantity":10,"buyPrice":150.0,"buyDate":"2024-05-01"}],
               "dividendTaxOverrides":{"AAPL::2024-05-01":0.1}}"""
        )
        val result = parsePositionsFromJson(json)!!
        assertEquals(1, result.valid.size)
        assertEquals(0.1, result.dividendTaxOverrides?.get("AAPL::2024-05-01")!!, 1e-9)
    }

    @Test
    fun `legacy single-key shape is parsed and re-decoded`() {
        val json = Json.parseToJsonElement(
            """{"stock_tracker_positions":"[{\"ticker\":\"AAPL\",\"quantity\":10,\"buyPrice\":150.0,\"buyDate\":\"2024-05-01\"}]"}"""
        )
        val result = parsePositionsFromJson(json)!!
        assertEquals(1, result.valid.size)
    }

    @Test
    fun `multi-portfolio legacy keys are merged`() {
        val json = Json.parseToJsonElement(
            """{"stock_tracker_positions_a":"[{\"ticker\":\"AAPL\",\"quantity\":10,\"buyPrice\":150.0,\"buyDate\":\"2024-05-01\"}]",
               "stock_tracker_positions_b":"[{\"ticker\":\"MSFT\",\"quantity\":5,\"buyPrice\":300.0,\"buyDate\":\"2024-06-01\"}]"}"""
        )
        val result = parsePositionsFromJson(json)!!
        assertEquals(2, result.valid.size)
    }

    @Test
    fun `invalid entries are counted as skipped, not dropped silently`() {
        val json = Json.parseToJsonElement(
            """[{"ticker":"AAPL","quantity":10,"buyPrice":150.0,"buyDate":"2024-05-01"},{"ticker":"","quantity":-1,"buyPrice":0,"buyDate":""}]"""
        )
        val result = parsePositionsFromJson(json)!!
        assertEquals(1, result.valid.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `unrecognized shape returns null`() {
        val json = Json.parseToJsonElement("""{"foo":"bar"}""")
        assertNull(parsePositionsFromJson(json))
    }
}
