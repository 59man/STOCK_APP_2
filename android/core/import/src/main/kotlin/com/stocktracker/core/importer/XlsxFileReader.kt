package com.stocktracker.core.importer

import org.dhatim.fastexcel.reader.ReadableWorkbook
import java.io.InputStream

/**
 * Reads XLSX rows via fastexcel-reader (a lightweight streaming reader —
 * deliberately not full Apache POI, which costs 10+ MB and desugaring for a
 * feature that only needs cell text). Like PdfBoxTextExtractor, this is
 * library glue only compile-checked in this environment, not runtime-tested.
 */
object XlsxFileReader {

    /** All rows of the given sheet (by name), as string cells; blank/missing cells become "". */
    fun readSheet(stream: InputStream, sheetName: String): List<List<String?>>? {
        ReadableWorkbook(stream).use { wb ->
            val sheet = wb.findSheet(sheetName).orElse(null) ?: return null
            return sheet.openStream().use { rows ->
                rows.map { row ->
                    (0 until row.cellCount).map { i -> row.getCellText(i) }
                }.toList()
            }
        }
    }

    /** The first sheet's rows — used when the caller doesn't know the sheet name up front (T212/Degiro/mapping wizard). */
    fun readFirstSheet(stream: InputStream): List<List<String?>> {
        ReadableWorkbook(stream).use { wb ->
            val sheet = wb.firstSheet
            return sheet.openStream().use { rows ->
                rows.map { row ->
                    (0 until row.cellCount).map { i -> row.getCellText(i) }
                }.toList()
            }
        }
    }

    /** True if the workbook has a sheet with this exact name (used to detect an XTB export before full parsing). */
    fun hasSheet(stream: InputStream, sheetName: String): Boolean {
        ReadableWorkbook(stream).use { wb ->
            return wb.findSheet(sheetName).isPresent
        }
    }
}
