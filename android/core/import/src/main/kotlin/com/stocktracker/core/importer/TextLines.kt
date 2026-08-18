package com.stocktracker.core.importer

import kotlin.math.abs

/** One text run from a PDF page, position in PDF user-space units (Y increases upward). */
data class PdfTextItem(val text: String, val x: Float, val y: Float)

/**
 * Groups one page's text items into lines by Y position (±3 unit threshold),
 * ordering items within a line by X. Mirrors the per-page body of `getLines`
 * in pdfParser.ts. Pure and library-free — the actual PDFBox-Android call
 * that produces [PdfTextItem]s lives in PdfBoxTextExtractor.kt, which needs
 * an Android runtime and isn't unit-testable the way this is.
 */
fun groupItemsIntoLines(items: List<PdfTextItem>): List<String> {
    val ti = items.filter { it.text.isNotBlank() }
    if (ti.isEmpty()) return emptyList()

    val sorted = ti.sortedWith { a, b ->
        val dy = b.y - a.y
        if (abs(dy) > 3f) dy.compareTo(0f) else (a.x - b.x).compareTo(0f)
    }

    val out = mutableListOf<String>()
    var curY = sorted.first().y
    var parts = mutableListOf<String>()
    for (item in sorted) {
        if (abs(item.y - curY) > 3f) {
            if (parts.isNotEmpty()) out.add(parts.joinToString(" ").trim())
            curY = item.y
            parts = mutableListOf(item.text)
        } else {
            parts.add(item.text)
        }
    }
    if (parts.isNotEmpty()) out.add(parts.joinToString(" ").trim())
    return out.filter { it.isNotBlank() }
}

/** Concatenates grouped lines across every page, matching getLines' page loop in pdfParser.ts. */
fun getLines(pages: List<List<PdfTextItem>>): List<String> = pages.flatMap(::groupItemsIntoLines)
