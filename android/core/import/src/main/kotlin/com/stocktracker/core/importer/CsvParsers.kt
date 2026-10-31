package com.stocktracker.core.importer

import com.stocktracker.core.calc.RawLot
import com.stocktracker.core.calc.applyFifo
import com.stocktracker.core.calc.isIsoDate
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private fun colIdx(header: List<String>, name: String): Int =
    header.indexOfFirst { it.trim().equals(name, ignoreCase = true) }

private val THOUSANDS_ONLY = Regex("""^[+-]?\d{1,3}(,\d{3})+$""")
private val NUMBER_SHAPE = Regex("""^[+-]?[\d.,]*\d[\d.,]*$""")

/**
 * A number as brokers write it: `1,234.56`, `1.234,56`, `150,25`, `1 234`. With both separators the
 * last one is the decimal point. A lone comma is thousands only in the exact `1,000` shape, unless
 * [commaDecimal] says this file writes decimals with a comma: see [usesCommaDecimals]. Anything else,
 * like `1.2.3`, is NaN. Mirrors parseBrokerNumber in csvParser.ts.
 */
fun parseBrokerNumber(raw: String?, commaDecimal: Boolean = false): Double {
    val t = (raw ?: "").filterNot { it.isWhitespace() || it == '\u00a0' }
    if (!NUMBER_SHAPE.matches(t)) return Double.NaN
    val comma = t.lastIndexOf(',')
    val dot = t.lastIndexOf('.')
    val normalized = when {
        comma >= 0 && dot >= 0 ->
            if (comma > dot) t.replace(".", "").replace(',', '.') else t.replace(",", "")
        comma >= 0 -> if (!commaDecimal && THOUSANDS_ONLY.matches(t)) t.replace(",", "") else t.replace(',', '.')
        commaDecimal && DOT_THOUSANDS.matches(t) -> t.replace(".", "")
        else -> t
    }
    return normalized.toDoubleOrNull() ?: Double.NaN
}

private val DOT_THOUSANDS = Regex("""^[+-]?\d{1,3}(\.\d{3})+$""")
private val COMMA_DECIMAL_PROOF = Regex("""^[+-]?\d+,(\d{1,2}|\d{4,})$|^[+-]?\d{1,3}(\.\d{3})+,\d+$""")

/**
 * `1,234` alone could be either number. Another cell in the same file settles it: `150,25` or
 * `1.234,5` cannot be anything but comma decimals. Mirrors usesCommaDecimals in csvParser.ts.
 */
fun usesCommaDecimals(cells: List<String>): Boolean =
    cells.any { c -> COMMA_DECIMAL_PROOF.matches(c.filterNot { it.isWhitespace() || it == '\u00a0' }) }

/**
 * Trading 212 CSV/XLSX export. Columns: Action | Time | ISIN | Ticker | Name |
 * No. of shares | Price / share | Currency (Price/share) | ... Mirrors
 * parseT212 in src/utils/csvParser.ts. Returns null if the required columns
 * aren't present or no buy rows are found — the dispatcher falls through to
 * the column-mapping wizard in that case.
 */
suspend fun parseT212(rows: List<List<String>>, lookupTickers: TickerLookup): ParseResult? {
    if (rows.isEmpty()) return null
    val header = rows[0]
    val iAction = colIdx(header, "Action")
    val iTime = colIdx(header, "Time")
    val iISIN = colIdx(header, "ISIN")
    val iTicker = colIdx(header, "Ticker")
    val iName = colIdx(header, "Name")
    val iQty = header.indexOfFirst { it.lowercase().contains("no. of shares") }
    val iPrice = header.indexOfFirst { it.lowercase().contains("price / share") }
    val iCurrency = header.indexOfFirst { it.lowercase().startsWith("currency (price") }

    if (iAction == -1 || iTime == -1 || iQty == -1 || iPrice == -1) return null

    fun cell(row: List<String>, i: Int): String? = if (i in row.indices) row[i] else null

    val rawLots = mutableListOf<RawLot>()
    var skipped = 0

    for (i in 1 until rows.size) {
        val row = rows[i]
        val action = (cell(row, iAction) ?: "").lowercase()
        val isSell = action.contains("sell")
        if (!isSell && !action.contains("buy")) { skipped++; continue }

        val ticker = (cell(row, iTicker) ?: "").trim()
        val name = (cell(row, iName) ?: ticker).trim()
        val isin = (cell(row, iISIN) ?: "").trim()
        val date = (cell(row, iTime) ?: "").split('T', ' ')[0]
        val qty = parseBrokerNumber(cell(row, iQty))
        val price = parseBrokerNumber(cell(row, iPrice))
        val currency = (cell(row, iCurrency) ?: "USD").trim()

        if (ticker.isEmpty() || !isIsoDate(date) || !(qty > 0) || !(price > 0)) { skipped++; continue }

        rawLots.add(RawLot(
            ticker = ticker, name = name, qty = qty, price = price, date = date, currency = currency,
            broker = "Trading 212", isin = isin.ifEmpty { null }, type = PositionType.STOCK, isSell = isSell,
        ))
    }

    val valid = applyFifo(rawLots) { _, _ -> skipped++ }
    if (valid.isEmpty()) return null

    val typeMap = lookupTickers(valid.map { it.ticker }.distinct())
    val enriched = valid.map { it.copy(type = typeMap[it.ticker]?.type ?: PositionType.STOCK) }

    return ParseResult(valid = enriched, skipped = skipped)
}

private val DEGIRO_TRADE = Regex("""^(Buy|Sell)\s+([\d.,]+)[^@]*@\s*([\d.,]+)\s*([A-Z]{3})""", RegexOption.IGNORE_CASE)
private val DEGIRO_DATE = Regex("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")

/**
 * Degiro CSV export. Columns vary by region; key ones are Date, Product,
 * ISIN, Description — the Description field encodes "Buy N PRODUCT @ PRICE
 * CURRENCY", or "Sell ...", for trade rows. Mirrors parseDegiro in src/utils/csvParser.ts.
 */
suspend fun parseDegiro(rows: List<List<String>>, lookupIsins: IsinLookup): ParseResult? {
    if (rows.isEmpty()) return null
    val header = rows[0].map { it.trim().lowercase() }
    val iDate = header.indexOf("date")
    val iProduct = header.indexOf("product")
    val iISIN = header.indexOf("isin")
    val iDesc = header.indexOf("description")

    if (iDate == -1 || iISIN == -1 || iDesc == -1) return null

    fun cell(row: List<String>, i: Int): String? = if (i in row.indices) row[i] else null

    val rawLots = mutableListOf<RawLot>()
    var skipped = 0

    for (i in 1 until rows.size) {
        val row = rows[i]
        val isin = (cell(row, iISIN) ?: "").trim()
        if (isin.isEmpty()) { skipped++; continue }

        val trade = DEGIRO_TRADE.find(cell(row, iDesc) ?: "")
        if (trade == null) { skipped++; continue }

        val qty = parseBrokerNumber(trade.groupValues[2])
        val price = parseBrokerNumber(trade.groupValues[3])
        val currency = trade.groupValues[4]
        if (!(qty > 0) || !(price > 0)) { skipped++; continue }

        val rawDate = cell(row, iDate) ?: ""
        val dm = DEGIRO_DATE.find(rawDate)
        if (dm == null) { skipped++; continue }
        val date = "${dm.groupValues[3]}-${dm.groupValues[2].padStart(2, '0')}-${dm.groupValues[1].padStart(2, '0')}"

        val name = if (iProduct != -1) (cell(row, iProduct) ?: isin).trim() else isin

        rawLots.add(RawLot(
            ticker = isin, name = name, qty = qty, price = price, date = date, currency = currency,
            broker = "Degiro", isin = isin, type = PositionType.STOCK,
            isSell = trade.groupValues[1].equals("sell", ignoreCase = true),
        ))
    }

    val valid = applyFifo(rawLots) { _, _ -> skipped++ }
    if (valid.isEmpty()) return null

    val isinMap = lookupIsins(valid.mapNotNull { it.isin }.distinct())
    val enriched = valid.map { p ->
        val info = p.isin?.let { isinMap[it] }
        if (info != null) p.copy(ticker = info.ticker, type = info.type) else p
    }

    return ParseResult(valid = enriched, skipped = skipped)
}

/** Mirrors detectCsvFormat in src/utils/csvParser.ts. */
fun detectCsvFormat(header: List<String>): String? {
    val h = header.joinToString(",").lowercase()
    if (h.contains("no. of shares") && h.contains("price / share")) return "t212"
    if (h.contains("order id") && h.contains("isin") && h.contains("description")) return "degiro"
    return null
}
