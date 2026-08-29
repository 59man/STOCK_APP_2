package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private fun getStr(row: List<String>, col: Int?): String {
    if (col == null || col < 0 || col >= row.size) return ""
    return row[col].trim()
}

private fun getNum(row: List<String>, col: Int?): Double =
    getStr(row, col).replace(Regex("""[,\s]"""), "").toDoubleOrNull() ?: 0.0

private val MAPPING_MONTHS: Map<String, String> = mapOf(
    "jan" to "01", "feb" to "02", "mar" to "03", "apr" to "04", "may" to "05", "jun" to "06",
    "jul" to "07", "aug" to "08", "sep" to "09", "oct" to "10", "nov" to "11", "dec" to "12",
)
private val MAPPING_ISO_DATE = Regex("""(\d{4})-(\d{2})-(\d{2})""")
private val MAPPING_DMY_DATE = Regex("""(\d{1,2})[./\-](\d{1,2})[./\-](\d{4})""")
private val MAPPING_MDY_DATE = Regex("""(\w{3,})\s+(\d{1,2}),?\s+(\d{4})""", RegexOption.IGNORE_CASE)

/**
 * Date parser used by the column-mapping wizard. Mirrors `parseAnyDate` in
 * importParser.ts — a deliberately separate implementation from
 * [parseAnyDatePdf] (3-letter month keys only, sliced from the matched
 * word), not consolidated with the PDF generic-heuristic version.
 */
fun parseAnyDateForMapping(s: String): String? {
    MAPPING_ISO_DATE.find(s)?.let { return "${it.groupValues[1]}-${it.groupValues[2]}-${it.groupValues[3]}" }
    MAPPING_DMY_DATE.find(s)?.let {
        return "${it.groupValues[3]}-${it.groupValues[2].padStart(2, '0')}-${it.groupValues[1].padStart(2, '0')}"
    }
    MAPPING_MDY_DATE.find(s)?.let {
        val mo = MAPPING_MONTHS[it.groupValues[1].take(3).lowercase()] ?: return null
        return "${it.groupValues[3]}-$mo-${it.groupValues[2].padStart(2, '0')}"
    }
    return null
}

/**
 * Finishes a [NeedsMapping] result once the user has picked columns in the
 * mapping wizard. Mirrors parseWithMapping in src/utils/importParser.ts.
 */
suspend fun parseWithMapping(
    rows: List<List<String>>,
    mapping: ColumnMapping,
    defaults: MappingDefaults,
    lookupTickers: TickerLookup,
): ParseResult? {
    val dataRows = rows.drop(defaults.skipRows)
    val valid = mutableListOf<Position>()
    var skipped = 0

    for (row in dataRows) {
        val ticker = getStr(row, mapping.ticker).uppercase()
        val dateRaw = getStr(row, mapping.date)
        val qty = getNum(row, mapping.quantity)
        val price = getNum(row, mapping.buyPrice)

        if (ticker.isEmpty() || dateRaw.isEmpty() || !(qty > 0) || !(price > 0)) { skipped++; continue }
        val buyDate = parseAnyDateForMapping(dateRaw)
        if (buyDate == null) { skipped++; continue }

        val sellDateRaw = getStr(row, mapping.sellDate)
        val sellPrice = getNum(row, mapping.sellPrice)
        val sellDate = if (sellDateRaw.isNotEmpty()) parseAnyDateForMapping(sellDateRaw) else null

        valid.add(Position(
            id = newId(),
            ticker = ticker,
            name = getStr(row, mapping.name).ifEmpty { ticker },
            type = PositionType.STOCK,
            quantity = qty,
            buyPrice = price,
            buyDate = buyDate,
            currency = getStr(row, mapping.currency).ifEmpty { defaults.currency },
            broker = getStr(row, mapping.broker).ifEmpty { defaults.broker }.ifEmpty { null },
            isin = getStr(row, mapping.isin).ifEmpty { null },
            sellDate = if (sellDate != null && sellPrice > 0) sellDate else null,
            sellPrice = if (sellDate != null && sellPrice > 0) sellPrice else null,
        ))
    }

    if (valid.isEmpty()) return null

    val typeMap = lookupTickers(valid.map { it.ticker }.distinct())
    val enriched = valid.map { it.copy(type = typeMap[it.ticker]?.type ?: PositionType.STOCK) }

    return ParseResult(valid = enriched, skipped = skipped)
}

/** Multilingual keyword-matches a header row to pre-fill the mapping wizard. Mirrors autoDetectMapping. */
fun autoDetectMapping(header: List<String>): ColumnMapping {
    val h = header.map { it.lowercase() }
    fun find(vararg keywords: String): Int? {
        val idx = h.indexOfFirst { cell -> keywords.any { cell.contains(it) } }
        return idx.takeIf { it >= 0 }
    }
    return ColumnMapping(
        ticker = find("ticker", "symbol", "kód", "instrument"),
        date = find("date", "datum", "time", "čas"),
        quantity = find("qty", "quantity", "množství", "shares", "počet", "volume"),
        buyPrice = find("price", "cena", "buy price", "open price", "jednotková"),
        name = find("name", "název", "product", "instrument"),
        isin = find("isin"),
        currency = find("currency", "měna", "fx", "ccy"),
        broker = find("broker", "platform", "source"),
        sellDate = find("sell date", "close time", "close date", "prodej datum"),
        sellPrice = find("sell price", "close price", "prodej cena"),
    )
}
