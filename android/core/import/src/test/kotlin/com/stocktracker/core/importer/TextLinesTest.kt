package com.stocktracker.core.importer

import org.junit.Assert.assertEquals
import org.junit.Test

class TextLinesTest {

    @Test
    fun `items on the same y group into one line ordered by x`() {
        val items = listOf(
            PdfTextItem("World", x = 50f, y = 700f),
            PdfTextItem("Hello", x = 0f, y = 700f),
        )
        assertEquals(listOf("Hello World"), groupItemsIntoLines(items))
    }

    @Test
    fun `items more than 3 units apart in y become separate lines`() {
        val items = listOf(
            PdfTextItem("Line one", x = 0f, y = 700f),
            PdfTextItem("Line two", x = 0f, y = 690f),
        )
        assertEquals(listOf("Line one", "Line two"), groupItemsIntoLines(items))
    }

    @Test
    fun `items within 3 units of y stay on the same line`() {
        val items = listOf(
            PdfTextItem("A", x = 0f, y = 700f),
            PdfTextItem("B", x = 10f, y = 698f), // within threshold of the first item's y
        )
        assertEquals(listOf("A B"), groupItemsIntoLines(items))
    }

    @Test
    fun `blank items are dropped and empty pages produce no lines`() {
        val items = listOf(PdfTextItem("  ", x = 0f, y = 700f), PdfTextItem("", x = 5f, y = 700f))
        assertEquals(emptyList<String>(), groupItemsIntoLines(items))
    }

    @Test
    fun `multiple pages concatenate in order`() {
        val page1 = listOf(PdfTextItem("Page one", x = 0f, y = 700f))
        val page2 = listOf(PdfTextItem("Page two", x = 0f, y = 700f))
        assertEquals(listOf("Page one", "Page two"), getLines(listOf(page1, page2)))
    }
}
