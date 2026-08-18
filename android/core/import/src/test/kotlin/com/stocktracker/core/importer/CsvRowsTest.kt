package com.stocktracker.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvRowsTest {

    @Test
    fun `plain comma separated rows split correctly`() {
        val rows = parseCsvRows("a,b,c\n1,2,3\n")
        assertEquals(listOf(listOf("a", "b", "c"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `quoted field with an embedded comma stays one field`() {
        val rows = parseCsvRows("name,note\n\"Doe, John\",hello\n")
        assertEquals(listOf(listOf("name", "note"), listOf("Doe, John", "hello")), rows)
    }

    @Test
    fun `doubled quotes inside a quoted field decode to one literal quote`() {
        val rows = parseCsvRows("a\n\"say \"\"hi\"\"\"\n")
        assertEquals(listOf(listOf("a"), listOf("say \"hi\"")), rows)
    }

    @Test
    fun `blank lines are dropped`() {
        val rows = parseCsvRows("a,b\n\n1,2\n")
        assertEquals(listOf(listOf("a", "b"), listOf("1", "2")), rows)
    }
}
