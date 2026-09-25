package com.stocktracker.core.calc

import com.stocktracker.core.model.PositionType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the same case table as src/utils/typeFilter.test.ts. */
class TypeFilterTest {
    private val cases = Json.parseToJsonElement(
        File(System.getProperty("user.dir")).resolve("../../../test-fixtures/type-filter/cases.json").readText(),
    ).jsonObject

    private fun types(o: JsonObject, key: String) =
        o.getValue(key).jsonArray.map { PositionType.valueOf(it.jsonPrimitive.content.uppercase()) }

    @Test fun effective() = cases.getValue("effective").jsonArray.forEach {
        val c = it.jsonObject
        assertEquals(c.toString(), types(c, "expected"), effectiveTypeFilter(types(c, "selected").toSet(), types(c, "held").toSet()).toList())
    }

    @Test fun toggle() = cases.getValue("toggle").jsonArray.forEach {
        val c = it.jsonObject
        val type = PositionType.valueOf(c.getValue("type").jsonPrimitive.content.uppercase())
        assertEquals(c.toString(), types(c, "expected"), toggleType(types(c, "selected").toSet(), type).toList())
    }

    @Test fun label() = cases.getValue("label").jsonArray.forEach {
        val c = it.jsonObject
        assertEquals(c.toString(), c.getValue("expected").jsonPrimitive.content, typeFilterLabel(types(c, "filter").toSet()))
    }
}
