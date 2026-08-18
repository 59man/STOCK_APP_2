package com.stocktracker.core.importer

import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportJsonTest {

    private fun position(ticker: String) = Position(
        id = "id-$ticker",
        ticker = ticker,
        name = ticker,
        type = PositionType.STOCK,
        quantity = 10.0,
        buyPrice = 150.0,
        buyDate = "2024-05-01",
        currency = "USD",
    )

    @Test
    fun `exported json always includes version so parsePositionsFromJson recognizes it`() {
        // Regression test: encodeDefaults=false would silently drop the
        // default-valued "version" field, and parsePositionsFromJson only
        // recognizes {version, positions} when version == 1 is present —
        // without it, a self-exported backup could not be re-imported.
        val json = buildExportJson(listOf(position("AAPL")))
        assertTrue(json.contains("\"version\": 1"))
    }

    @Test
    fun `exported json round-trips through parsePositionsFromJson`() {
        val json = buildExportJson(
            positions = listOf(position("AAPL"), position("MSFT")),
            manualPrices = mapOf("LU2606422355" to ManualPriceEntry(105.5, "2025-01-01")),
            dividendTaxOverrides = mapOf("AAPL::2024-08-01" to 0.15),
        )

        val result = parsePositionsFromJson(Json.parseToJsonElement(json))!!

        assertEquals(2, result.valid.size)
        assertEquals(setOf("AAPL", "MSFT"), result.valid.map { it.ticker }.toSet())
        assertEquals(0, result.skipped)
        assertEquals(0.15, result.dividendTaxOverrides?.get("AAPL::2024-08-01")!!, 1e-9)
        assertEquals(105.5, result.manualPrices?.get("LU2606422355")?.price!!, 1e-9)
    }

    @Test
    fun `empty overrides and manual prices round-trip as absent, not empty objects`() {
        // encodeDefaults=true (required for "version") means these null fields are
        // still present as literal JSON nulls — parsePositionsFromJson must treat
        // that the same as the field being absent entirely.
        val json = buildExportJson(listOf(position("AAPL")))
        val result = parsePositionsFromJson(Json.parseToJsonElement(json))!!
        assertEquals(null, result.dividendTaxOverrides)
        assertEquals(null, result.manualPrices)
    }
}
