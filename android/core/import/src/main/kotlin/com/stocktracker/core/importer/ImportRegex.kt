package com.stocktracker.core.importer

/** Shared regex/number helpers ported verbatim from src/utils/pdfParser.ts. */

val CZ_NUM = Regex("""-?[\d][\d ]*,\d{2}""")
val DATE_RE = Regex("""(\d{1,2}\.\d{1,2}\.\d{4})\s+(\d{2}:\d{2})""")
val ISIN_RE = Regex("""[A-Z]{2}[A-Z0-9]{10}""")

/** Czech-formatted number: space thousands separator, comma decimal point. */
fun czn(s: String): Double = s.trim().replace(" ", "").replace(",", ".").toDouble()

/** English 3-letter month abbreviations, capitalized — used by the Revolut XAU parser's date format. */
val MONTHS: Map<String, String> = mapOf(
    "Jan" to "01", "Feb" to "02", "Mar" to "03", "Apr" to "04", "May" to "05", "Jun" to "06",
    "Jul" to "07", "Aug" to "08", "Sep" to "09", "Oct" to "10", "Nov" to "11", "Dec" to "12",
)

/** Full + 3-letter lowercase month names — used by the generic PDF heuristic's date parser. */
val MONTHS_EN: Map<String, String> = mapOf(
    "january" to "01", "february" to "02", "march" to "03", "april" to "04", "may" to "05", "june" to "06",
    "july" to "07", "august" to "08", "september" to "09", "october" to "10", "november" to "11", "december" to "12",
    "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04", "jun" to "06",
    "jul" to "07", "aug" to "08", "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12",
)

private val ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val DMY_DATE = Regex("""(\d{1,2})[./\-](\d{1,2})[./\-](\d{4})""")
private val MDY_DATE_PDF = Regex("""(\w+)\s+(\d{1,2}),?\s+(\d{4})""")

/**
 * Date parser used by the generic PDF heuristic. Mirrors `parseAnyDate` in
 * pdfParser.ts — deliberately a separate, slightly different implementation
 * from [parseAnyDateForMapping] (see that function's doc), not consolidated,
 * to avoid silently changing either parser's behavior.
 */
fun parseAnyDatePdf(s: String): String? {
    ISO_DATE.find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
    DMY_DATE.find(s)?.let {
        return "${it.groupValues[3]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[1].padStart(2, '0')}"
    }
    MDY_DATE_PDF.find(s)?.let {
        val mo = MONTHS_EN[it.groupValues[1].lowercase()] ?: return null
        return "${it.groupValues[3]}-$mo-${it.groupValues[2].padStart(2, '0')}"
    }
    return null
}
