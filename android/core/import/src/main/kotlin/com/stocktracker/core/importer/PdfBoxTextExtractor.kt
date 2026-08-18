package com.stocktracker.core.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream

/**
 * Extracts per-page positioned text items from a PDF via PDFBox-Android,
 * then hands them to the pure [groupItemsIntoLines] algorithm. This is the
 * one piece of core:import that genuinely needs an Android runtime — it
 * can't be unit-tested the way the rest of this module is (no emulator in
 * this environment); only compile-checked. [groupItemsIntoLines] itself is
 * fully unit-tested against synthetic items.
 */
object PdfBoxTextExtractor {

    private var initialized = false

    /** Must be called once (e.g. from Application.onCreate) before [extractLines]. */
    fun init(context: Context) {
        if (!initialized) {
            PDFBoxResourceLoader.init(context.applicationContext)
            initialized = true
        }
    }

    fun extractLines(stream: InputStream): List<String> {
        check(initialized) { "PdfBoxTextExtractor.init(context) must run before use" }
        PDDocument.load(stream).use { document ->
            val pages = mutableListOf<List<PdfTextItem>>()
            for (pageIndex in 0 until document.numberOfPages) {
                val items = mutableListOf<PdfTextItem>()
                val stripper = object : PDFTextStripper() {
                    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
                        val first = textPositions.firstOrNull() ?: return
                        if (text.isBlank()) return
                        items.add(PdfTextItem(text = text, x = first.xDirAdj, y = first.yDirAdj))
                    }
                }
                stripper.startPage = pageIndex + 1
                stripper.endPage = pageIndex + 1
                stripper.sortByPosition = false
                stripper.getText(document)
                pages.add(items)
            }
            return getLines(pages)
        }
    }
}
