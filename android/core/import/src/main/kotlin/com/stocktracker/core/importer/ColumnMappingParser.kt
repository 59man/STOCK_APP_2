package com.stocktracker.core.importer

import com.stocktracker.core.calc.RawLot
import com.stocktracker.core.calc.applyFifo
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private fun getStr(row: List<String>, col: Int?): String {
    if (col == null || col < 0 || col >= row.size) return ""
    return row[col].trim()
}

private fun getNum(row: List<String>, col: Int?, commaDecimal: Boolean): Double =
    parseBrokerNumber(getStr(row, col), commaDecimal).takeUnless { it.isNaN() } ?: 0.0

private val EXCEL_SERIAL = Regex("""^\d{5}(\.\d+)?$""")

/**
 * A date cell in a spreadsheet read as its raw value, a day count from Excel's epoch. The XLSX reader
 * has no number-format information, so this recognises the five-digit shape, which covers 1927-2173.
 */
fun excelSerialToIso(value: String): String? =
    if (EXCEL_SERIAL.matches(value)) java.time.LocalDate.of(1899, 12, 30).plusDays(value.toDouble().toLong()).toString() else null

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
    excelSerialToIso(s)?.let { return it }
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
    val numberCols = listOf(mapping.quantity, mapping.buyPrice, mapping.sellPrice)
    val commaDecimal = usesCommaDecimals(dataRows.flatMap { r -> numberCols.map { getStr(r, it) } })
    val valid = mutableListOf<Position>()
    val sells = mutableListOf<RawLot>()
    var skipped = 0

    for (row in dataRows) {
        val ticker = getStr(row, mapping.ticker).uppercase()
        val dateRaw = getStr(row, mapping.date)
        val qty = getNum(row, mapping.quantity, commaDecimal)
        val price = getNum(row, mapping.buyPrice, commaDecimal)

        if (ticker.isEmpty() || dateRaw.isEmpty() || qty == 0.0 || !(price > 0)) { skipped++; continue }
        val buyDate = parseAnyDateForMapping(dateRaw)
        if (buyDate == null) { skipped++; continue }

        // The wizard has no direction column, but statements commonly write a sale as negative quantity.
        if (qty < 0) {
            sells.add(RawLot(
                ticker = ticker, name = ticker, qty = -qty, price = price, date = buyDate,
                currency = getStr(row, mapping.currency).ifEmpty { defaults.currency }, broker = null,
                type = PositionType.STOCK, isSell = true,
            ))
            continue
        }

        val sellDateRaw = getStr(row, mapping.sellDate)
        val sellPrice = getNum(row, mapping.sellPrice, commaDecimal)
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

    val matched = if (sells.isEmpty()) valid else {
        val (closed, open) = valid.partition { it.sellDate != null }
        val buys = open.map {
            RawLot(it.ticker, it.name, it.quantity, it.buyPrice, it.buyDate, it.currency, it.broker, it.isin, it.type, isSell = false)
        }
        closed + applyFifo(buys + sells) { _, _ -> skipped++ }
    }
    if (matched.isEmpty()) return null

    val typeMap = lookupTickers(matched.map { it.ticker }.distinct())
    val enriched = matched.map { it.copy(type = typeMap[it.ticker]?.type ?: PositionType.STOCK) }

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
