package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private fun colIdx(header: List<String>, name: String): Int =
    header.indexOfFirst { it.trim().equals(name, ignoreCase = true) }

private fun parseDecimal(s: String?): Double = (s ?: "").replace(",", "").toDoubleOrNull() ?: Double.NaN

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

    val valid = mutableListOf<Position>()
    var skipped = 0

    for (i in 1 until rows.size) {
        val row = rows[i]
        val action = (cell(row, iAction) ?: "").lowercase()
        if (!action.contains("buy")) { skipped++; continue }

        val ticker = (cell(row, iTicker) ?: "").trim()
        val name = (cell(row, iName) ?: ticker).trim()
        val isin = (cell(row, iISIN) ?: "").trim()
        val timeStr = cell(row, iTime) ?: ""
        val qty = parseDecimal(cell(row, iQty))
        val price = parseDecimal(cell(row, iPrice))
        val currency = (cell(row, iCurrency) ?: "USD").trim()

        if (ticker.isEmpty() || timeStr.isEmpty() || !(qty > 0) || !(price > 0)) { skipped++; continue }

        valid.add(Position(
            id = newId(), ticker = ticker, name = name, type = PositionType.STOCK,
            quantity = qty, buyPrice = price, buyDate = timeStr.substringBefore('T'),
            currency = currency, broker = "Trading 212", isin = isin.ifEmpty { null },
        ))
    }

    if (valid.isEmpty()) return null

    val typeMap = lookupTickers(valid.map { it.ticker }.distinct())
    val enriched = valid.map { it.copy(type = typeMap[it.ticker]?.type ?: PositionType.STOCK) }

    return ParseResult(valid = enriched, skipped = skipped)
}

private val DEGIRO_BUY = Regex("""^Buy\s+([\d.]+)[^@]*@\s*([\d.]+)\s*([A-Z]{3})""", RegexOption.IGNORE_CASE)
private val DEGIRO_DATE = Regex("""(\d{1,2})[-/](\d{1,2})[-/](\d{4})""")

/**
 * Degiro CSV export. Columns vary by region; key ones are Date, Product,
 * ISIN, Description — the Description field encodes "Buy N PRODUCT @ PRICE
 * CURRENCY" for buy rows. Mirrors parseDegiro in src/utils/csvParser.ts.
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

    val valid = mutableListOf<Position>()
    var skipped = 0

    for (i in 1 until rows.size) {
        val row = rows[i]
        val isin = (cell(row, iISIN) ?: "").trim()
        if (isin.isEmpty()) { skipped++; continue }

        val desc = cell(row, iDesc) ?: ""
        val buyM = DEGIRO_BUY.find(desc)
        if (buyM == null) { skipped++; continue }

        val qty = buyM.groupValues[1].toDouble()
        val price = buyM.groupValues[2].toDouble()
        val currency = buyM.groupValues[3]

        val rawDate = cell(row, iDate) ?: ""
        val dm = DEGIRO_DATE.find(rawDate)
        if (dm == null) { skipped++; continue }
        val buyDate = "${dm.groupValues[3]}-${dm.groupValues[2].padStart(2, '0')}-${dm.groupValues[1].padStart(2, '0')}"

        val name = if (iProduct != -1) (cell(row, iProduct) ?: isin).trim() else isin

        valid.add(Position(
            id = newId(), ticker = isin, name = name, type = PositionType.STOCK,
            quantity = qty, buyPrice = price, buyDate = buyDate, currency = currency,
            broker = "Degiro", isin = isin,
        ))
    }

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
