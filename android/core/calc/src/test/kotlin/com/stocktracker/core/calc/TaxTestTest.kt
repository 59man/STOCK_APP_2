package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** Runs the same case table as src/utils/taxTest.test.ts. */
class TaxTestTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cases = json.parseToJsonElement(
        File(System.getProperty("user.dir")).resolve("../../../test-fixtures/tax/cases.json").readText(),
    ).jsonObject

    @Test fun freeFrom() = cases.getValue("freeFrom").jsonArray.forEach {
        val c = it.jsonObject
        assertEquals(c.getValue("expected").jsonPrimitive.content, timeTestDate(c.getValue("buyDate").jsonPrimitive.content))
    }

    @Test fun status() = cases.getValue("status").jsonArray.forEach {
        val c = it.jsonObject
        val lot = c.getValue("lot").jsonObject
        val s = lotTaxStatus(
            buyDate = lot.getValue("buyDate").jsonPrimitive.content,
            sellDate = lot["sellDate"]?.jsonPrimitive?.content,
            sellPrice = lot["sellPrice"]?.jsonPrimitive?.double,
            today = c.getValue("today").jsonPrimitive.content,
        )
        assertEquals(c.toString(), c.getValue("kind").jsonPrimitive.content.uppercase(), s.kind.name)
        c["daysLeft"]?.let { d -> assertEquals(c.toString(), d.jsonPrimitive.long, s.daysLeft) }
    }

    @Test fun years() {
        val y = cases.getValue("years").jsonObject
        val fx = y.getValue("fx").jsonObject.mapValues { (_, v) ->
            v.jsonArray.map { p -> p.jsonArray[0].jsonPrimitive.content to p.jsonArray[1].jsonPrimitive.double }
        }
        val czkAt = { amount: Double, cur: String, date: String ->
            if (cur == "CZK") amount else amount * fx.getValue(cur).last { it.first <= date }.second
        }
        val positions = json.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(Position.serializer()), y.getValue("positions"))
        val got = realizedByYear(positions, czkAt)
        val expected = y.getValue("expected").jsonArray.map { e ->
            val o = e.jsonObject
            YearSummary(
                o.getValue("year").jsonPrimitive.int, o.getValue("proceedsTaxable").jsonPrimitive.double,
                o.getValue("gainTaxable").jsonPrimitive.double, o.getValue("gainExempt").jsonPrimitive.double,
                o.getValue("underSmallProceedsLimit").jsonPrimitive.boolean,
            )
        }
        assertEquals(expected, got)
    }
}
