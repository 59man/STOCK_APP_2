package com.stocktracker.core.importer

import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream

/** The three network-lookup callbacks every parser needs, bundled for convenience at the call site. */
class ImportLookups(val lookupIsins: IsinLookup, val lookupTickers: TickerLookup)

/**
 * Top-level entry point — dispatches by file extension exactly like
 * `parseFile` in src/utils/importParser.ts. Everything here runs on-device;
 * no network call happens except the injected ISIN/ticker lookups.
 */
suspend fun parseFile(bytes: ByteArray, fileName: String, lookups: ImportLookups): ParseFileResult? {
    val ext = fileName.substringAfterLast('.', "").lowercase()

    if (ext == "pdf") {
        val result = parsePdfBytes(bytes, lookups) ?: return null
        return ParseResultFile(result)
    }

    if (ext == "xlsx" || ext == "csv") {
        val rows: List<List<String?>> = if (ext == "csv") {
            parseCsvRows(String(bytes, Charsets.UTF_8))
        } else {
            if (XlsxFileReader.hasSheet(ByteArrayInputStream(bytes), "Cash Operations")) {
                val xtbRows = XlsxFileReader.readSheet(ByteArrayInputStream(bytes), "Cash Operations") ?: emptyList()
                val headerIdx = xtbRows.indexOfFirst { it.getOrNull(0) == "Type" }
                val dataRows = if (headerIdx >= 0) xtbRows.drop(headerIdx + 1) else emptyList()
                val result = parseXtbFromRows(dataRows, fileName, lookups.lookupTickers) ?: return null
                return ParseResultFile(result)
            }
            XlsxFileReader.readFirstSheet(ByteArrayInputStream(bytes))
        }

        if (rows.isEmpty()) return null
        val header = rows[0].map { it ?: "" }
        val stringRows = rows.map { r -> r.map { it ?: "" } }

        when (detectCsvFormat(header)) {
            "t212" -> parseT212(stringRows, lookups.lookupTickers)?.let { return ParseResultFile(it) }
            "degiro" -> parseDegiro(stringRows, lookups.lookupIsins)?.let { return ParseResultFile(it) }
        }

        return NeedsMappingFile(NeedsMapping(stringRows))
    }

    // Fall back to JSON re-import of a previously exported app file.
    return try {
        val json = Json.parseToJsonElement(String(bytes, Charsets.UTF_8))
        parsePositionsFromJson(json)?.let { ParseResultFile(it) }
    } catch (_: Exception) {
        null
    }
}

/** Mirrors parsePdf in src/utils/pdfParser.ts — broker detection by content, generic heuristic as the fallback. */
private suspend fun parsePdfBytes(bytes: ByteArray, lookups: ImportLookups): ParseResult? {
    val lines = PdfBoxTextExtractor.extractLines(ByteArrayInputStream(bytes))
    val flat = lines.joinToString(" ")

    return when {
        flat.contains("Fio banka") || flat.contains("FIOBCZPP") -> parseFio(lines, lookups.lookupIsins)
        flat.contains("Revolut") && flat.contains("Trade - ") ->
            parseRevolutTrading(lines, lookups.lookupIsins, lookups.lookupTickers)
        flat.contains("Revolut") && flat.contains("XAU") -> parseRevolutXau(lines)
        else -> {
            val generic = parseGeneric(lines, lookups.lookupIsins)
            generic.valid.ifEmpty { null }?.let { generic }
        }
    }
}
