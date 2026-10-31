package com.stocktracker.core.importer

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.InputStream

/**
 * Extracts per-page positioned text items from a PDF via PDFBox-Android,
 * then hands them to the pure [groupItemsIntoLines] algorithm. The one piece
 * of core:import that needs an Android runtime, so its test uses Robolectric.
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
                        // PDFBox reports y from the top of the page; PdfTextItem's y increases upward, as in pdfjs.
                        items.add(PdfTextItem(text = text, x = first.xDirAdj, y = first.pageHeight - first.yDirAdj))
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
