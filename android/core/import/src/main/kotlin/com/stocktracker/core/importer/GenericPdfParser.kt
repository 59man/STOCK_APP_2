package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private val BUY_KW = Regex("""\b(buy|nákup|kauf|achat|purchase|acqui|entrada|compra)\b""", RegexOption.IGNORE_CASE)
private val ANY_DATE = listOf(
    Regex("""(\d{4}-\d{2}-\d{2})"""),
    Regex("""(\d{1,2}[./\-]\d{1,2}[./\-]\d{4})"""),
    Regex("""(\w{3,9}\s+\d{1,2},?\s+\d{4})"""),
)
private val ANY_NUM = Regex("""\b\d+(?:[., ]\d+)*\b""")

private data class Hit(val isin: String, val date: String, val price: Double, val qty: Double, val name: String)

/**
 * Best-effort fallback for any broker PDF the specific parsers don't
 * recognize — finds ISIN + nearby buy keyword + date + numbers. Results are
 * tagged `broker = "Unknown (verify)"`. Mirrors parseGeneric in pdfParser.ts.
 */
suspend fun parseGeneric(lines: List<String>, lookupIsins: IsinLookup): ParseResult {
    val hits = mutableListOf<Hit>()
    var skipped = 0

    for (i in lines.indices) {
        val line = lines[i]
        val isinMatch = ISIN_RE.find(line) ?: continue

        val window = lines.subList(maxOf(0, i - 2), minOf(lines.size, i + 4)).joinToString(" ")
        if (!BUY_KW.containsMatchIn(window)) { skipped++; continue }

        var date: String? = null
        outer@ for (j in (i - 2)..(i + 2)) {
            if (j < 0 || j >= lines.size) continue
            for (pat in ANY_DATE) {
                val m = pat.find(lines[j])
                if (m != null) { date = parseAnyDatePdf(m.value); break@outer }
            }
        }
        if (date == null) { skipped++; continue }

        val nums = ANY_NUM.findAll(window)
            .map { it.value.replace(Regex("""[\s,]"""), "").toDoubleOrNull() }
            .filterNotNull()
            .filter { it.isFinite() && it > 0 }
            .sorted()
            .toList()

        if (nums.size < 2) { skipped++; continue }
        val qty = if (nums[0] <= 1000) nums[0] else 1.0
        val price = nums.last()

        val isin = isinMatch.value
        val isinIdx = line.indexOf(isin)
        val nameRaw = (if (isinIdx > 0) line.substring(0, isinIdx) else "").replace(DATE_RE, "").trim()

        hits.add(Hit(isin = isin, date = date, price = price, qty = qty, name = nameRaw.ifEmpty { isin }))
    }

    if (hits.isEmpty()) return ParseResult(valid = emptyList(), skipped = skipped)

    val isinMap = lookupIsins(hits.map { it.isin }.distinct())

    val valid = hits.map { h ->
        val info = isinMap[h.isin]
        Position(
            id = newId(),
            ticker = info?.ticker ?: h.isin,
            name = h.name.ifEmpty { h.isin },
            type = info?.type ?: PositionType.STOCK,
            quantity = h.qty,
            buyPrice = h.price,
            buyDate = h.date,
            currency = "USD", // unknown — user must verify
            broker = "Unknown (verify)",
            isin = h.isin,
        )
    }

    return ParseResult(valid = valid, skipped = skipped)
}
