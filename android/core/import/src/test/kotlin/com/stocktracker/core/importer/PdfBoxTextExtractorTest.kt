package com.stocktracker.core.importer

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class PdfBoxTextExtractorTest {
    @Test
    fun `lines come out top to bottom, as they read on the page`() {
        PdfBoxTextExtractor.init(ApplicationProvider.getApplicationContext())
        val bytes = ByteArrayOutputStream().also { out ->
            PDDocument().use { doc ->
                val page = PDPage()
                doc.addPage(page)
                PDPageContentStream(doc, page).use { c ->
                    c.setFont(PDType1Font.HELVETICA, 12f)
                    c.beginText(); c.newLineAtOffset(50f, 700f); c.showText("TOP"); c.endText()
                    c.beginText(); c.newLineAtOffset(50f, 400f); c.showText("BOTTOM"); c.endText()
                }
                doc.save(out)
            }
        }.toByteArray()

        assertEquals(listOf("TOP", "BOTTOM"), PdfBoxTextExtractor.extractLines(ByteArrayInputStream(bytes)))
    }
}
