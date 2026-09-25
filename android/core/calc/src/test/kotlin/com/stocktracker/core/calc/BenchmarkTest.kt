package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Same shared case as src/utils/benchmark.test.ts, plus the identity property. */
class BenchmarkTest {
    private val same = { a: Double, _: String, _: String, _: String -> a }

    @Test fun `matches the shared hand-computed case`() {
        val json = Json { ignoreUnknownKeys = true }
        val c = json.parseToJsonElement(
            File(System.getProperty("user.dir")).resolve("../../../test-fixtures/benchmark/cases.json").readText(),
        ).jsonObject
        val bench = c.getValue("bench").jsonArray.map { it.jsonArray[0].jsonPrimitive.content to it.jsonArray[1].jsonPrimitive.double }
        val dates = c.getValue("dates").jsonArray.map { it.jsonPrimitive.content }
        val positions = json.decodeFromJsonElement(ListSerializer(Position.serializer()), c.getValue("positions"))
        val expected = c.getValue("expected").jsonArray.map { it.jsonPrimitive.double }
        val got = benchmarkSeries(positions, dates, bench, "CZK", "CZK", same)
        expected.forEachIndexed { i, e -> assertEquals(e, got[i], 1e-6) }
    }

    @Test fun `equals the holding's own price P&L when the holding is the benchmark`() {
        val bench = listOf("2024-01-01" to 100.0, "2024-03-01" to 90.0, "2024-09-01" to 140.0)
        val lot = Position("x", "X", "X", PositionType.ETF, 3.0, 100.0, "2024-01-01", "CZK")
        val got = benchmarkSeries(listOf(lot), bench.map { it.first }, bench, "CZK", "CZK", same)
        assertEquals(bench.map { 3 * (it.second - 100) }, got)
    }
}
