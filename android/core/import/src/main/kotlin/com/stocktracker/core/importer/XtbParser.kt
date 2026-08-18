package com.stocktracker.core.importer

import com.stocktracker.core.calc.RawLot
import com.stocktracker.core.calc.applyFifo
import com.stocktracker.core.model.PositionType
import kotlin.math.abs

private val FILL_QTY = Regex("""(?:OPEN|CLOSE)\s+(?:BUY|SELL)\s+([\d.]+)(?:/[\d.]+)?\s*@""")
private val SELL_COMMENT = Regex("""CLOSE\s+SELL""", RegexOption.IGNORE_CASE)
private val ACCOUNT_CURRENCY_PREFIX = Regex("""^([A-Z]{3})_""")

private fun parseFillQty(comment: String): Double? = FILL_QTY.find(comment)?.groupValues?.get(1)?.toDoubleOrNull()

/** Filename prefix (`EUR_...`/`CZK_...`) is the only place the account currency is stated. */
fun accountCurrencyFromFileName(fileName: String): String? = ACCOUNT_CURRENCY_PREFIX.find(fileName)?.groupValues?.get(1)

/**
 * Pure row logic for XTB's "Cash Operations" sheet — takes already-extracted
 * string cells (date cell pre-normalized to a string, whether it started as
 * a real spreadsheet date or plain text) so it's testable without the XLSX
 * library. Mirrors the row loop in parseXtbXlsx (src/utils/xlsxParser.ts).
 *
 * @param rows data rows only — the header row itself must already be excluded.
 */
fun parseXtbRows(rows: List<List<String?>>, accountCurrency: String): Pair<List<RawLot>, Int> {
    val rawLots = mutableListOf<RawLot>()
    var skipped = 0

    for (row in rows) {
        val type = row.getOrNull(0) ?: ""
        if (type != "Stock purchase" && type != "Stock sale") continue

        val isSell = type == "Stock sale"
        val xtbTicker = (row.getOrNull(1) ?: "").trim()
        val name = (row.getOrNull(2)?.trim()?.ifEmpty { null }) ?: xtbTicker
        val time = row.getOrNull(3)
        val amount = row.getOrNull(4)?.toDoubleOrNull()
        val comment = row.getOrNull(6) ?: ""

        if (xtbTicker.isEmpty() || time.isNullOrEmpty() || amount == null) { skipped++; continue }
        if (!isSell && amount >= 0) { skipped++; continue }
        if (isSell && amount <= 0) { skipped++; continue }

        val qty = parseFillQty(comment)
        if (qty == null || qty <= 0) { skipped++; continue }

        if (isSell && !SELL_COMMENT.containsMatchIn(comment) && comment.contains("OPEN")) { skipped++; continue }

        // XTB uses .CZ for the Prague exchange; the app uses .PR.
        val ticker = if (xtbTicker.endsWith(".CZ")) xtbTicker.removeSuffix(".CZ") + ".PR" else xtbTicker
        val buyDate = time.split(Regex("[T ]"))[0]

        rawLots.add(RawLot(
            ticker = ticker, name = name, qty = qty, price = abs(amount) / qty, date = buyDate,
            currency = accountCurrency, broker = "XTB", type = PositionType.STOCK, isSell = isSell,
        ))
    }

    return rawLots to skipped
}

/**
 * Finishes the parse from already-extracted rows (post-FIFO + Yahoo type
 * enrichment). The actual sheet reading (fastexcel-reader) lives in
 * XlsxFileReader.kt, which hands this function its rows.
 */
suspend fun parseXtbFromRows(rows: List<List<String?>>, fileName: String, lookupTickers: TickerLookup): ParseResult? {
    val prefixCurrency = accountCurrencyFromFileName(fileName)
    val accountCurrency = prefixCurrency ?: "CZK"

    val (rawLots, skipped) = parseXtbRows(rows, accountCurrency)
    if (rawLots.isEmpty()) return null

    val positions = applyFifo(rawLots)
    if (positions.isEmpty()) return null

    val typeMap = lookupTickers(positions.map { it.ticker }.distinct())
    val enriched = positions.map { it.copy(type = typeMap[it.ticker]?.type ?: PositionType.STOCK) }

    return ParseResult(valid = enriched, skipped = skipped, currencyUncertain = prefixCurrency == null)
}
