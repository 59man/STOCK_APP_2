package com.stocktracker.core.calc

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Same shared case as src/utils/dividendForecast.test.ts. */
class DividendForecastTest {
    @Test fun `repeats the last 12 months on the shares held now`() {
        val json = Json { ignoreUnknownKeys = true }
        val c = json.parseToJsonElement(
            File(System.getProperty("user.dir")).resolve("../../../test-fixtures/forecast/cases.json").readText(),
        ).jsonObject
        val positions = json.decodeFromJsonElement(ListSerializer(Position.serializer()), c.getValue("positions"))
        val dividends = json.decodeFromJsonElement(
            MapSerializer(String.serializer(), ListSerializer(DividendEvent.serializer())), c.getValue("dividends"),
        )
        val got = dividendForecast(positions, dividends, c.getValue("today").jsonPrimitive.content)
        val expected = c.getValue("expected").jsonArray.map { it.jsonObject }
        assertEquals(expected.map { it.getValue("date").jsonPrimitive.content }, got.map { it.date })
        expected.forEachIndexed { i, e ->
            assertEquals(e.getValue("ticker").jsonPrimitive.content, got[i].ticker)
            assertEquals(e.getValue("net").jsonPrimitive.double, got[i].net, 1e-9)
        }
    }
}
