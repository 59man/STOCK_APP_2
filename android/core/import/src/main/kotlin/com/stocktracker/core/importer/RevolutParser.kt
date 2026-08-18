package com.stocktracker.core.importer

import com.stocktracker.core.calc.RawLot
import com.stocktracker.core.calc.applyFifo
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType

private val REV_XAU_DATE = Regex("""(\w{3})\s+(\d{1,2}),\s+(\d{4})""")
private val REV_XAU_QTY = Regex("""([\d.]+)\s+XAU""")
private val REV_XAU_CZK = Regex("""([\d,]+\.\d{2})\s+CZK""")

/** "Exchanged to XAU" gold-buy events. Mirrors parseRevolut in src/utils/pdfParser.ts. Synchronous — XAU is a fixed ticker, no lookup needed. */
fun parseRevolutXau(lines: List<String>): ParseResult {
    val valid = mutableListOf<Position>()

    for (i in lines.indices) {
        val line = lines[i]
        if (!line.contains("Exchanged to XAU")) continue

        val dm = REV_XAU_DATE.find(line) ?: continue
        val month = MONTHS[dm.groupValues[1]] ?: continue
        val buyDate = "${dm.groupValues[3]}-$month-${dm.groupValues[2].padStart(2, '0')}"

        val qty = REV_XAU_QTY.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
        if (qty <= 0) continue

        var czk = 0.0
        for (j in (i + 1)..minOf(i + 6, lines.size - 1)) {
            val cm = REV_XAU_CZK.find(lines[j]) ?: continue
            czk = cm.groupValues[1].replace(",", "").toDouble()
            break
        }
        if (czk <= 0) continue

        valid.add(Position(
            id = newId(), ticker = "XAU", name = "Gold (XAU)", type = PositionType.COMMODITY,
            quantity = qty, buyPrice = czk / qty, buyDate = buyDate, currency = "CZK", broker = "Revolut",
        ))
    }

    return ParseResult(valid = valid, skipped = 0)
}

data class RevolutTx(
    val date: String,
    val symbol: String,
    val qty: Double,
    val price: Double,
    val currency: String,
    val isSell: Boolean,
)

data class SymbolInfo(val isin: String, val name: String)

data class RevolutTradingLines(
    val txs: List<RevolutTx>,
    val bySymbol: Map<String, SymbolInfo>,
    val skipped: Int,
)

private val REV_SECTION = Regex("""^([A-Z]{3}) Transactions$""")
private val REV_TRADE = Regex(
    """^(\d{1,2}) ([A-Za-z]{3}) (\d{4}) \d{2}:\d{2}:\d{2} \S+ (\S+) Trade - \S+ ([\d,]*\.?\d+) [^\d\s]*([\d,]+(?:\.\d+)?) (Buy|Sell)\b"""
)

/**
 * Pure row parser for Revolut trading-account statements — exported
 * separately (matching parseRevolutTradingLines in pdfParser.ts) so it's
 * unit-testable without a network lookup.
 */
fun parseRevolutTradingLines(lines: List<String>): RevolutTradingLines {
    val txs = mutableListOf<RevolutTx>()
    val bySymbol = mutableMapOf<String, SymbolInfo>()
    var currency = "USD"
    var skipped = 0

    for (line in lines) {
        val sec = REV_SECTION.find(line)
        if (sec != null) { currency = sec.groupValues[1]; continue }

        val isinM = ISIN_RE.find(line)
        if (isinM != null && !line.contains("GMT")) {
            val symbol = line.substringBefore(' ')
            val isinStart = line.indexOf(isinM.value)
            val name = if (isinStart > symbol.length) line.substring(symbol.length, isinStart).trim() else ""
            if (symbol.isNotEmpty() && name.isNotEmpty()) bySymbol[symbol] = SymbolInfo(isinM.value, name)
            continue
        }

        if (!line.contains("Trade - ")) continue
        val m = REV_TRADE.find(line)
        if (m == null) { skipped++; continue }

        val month = MONTHS[m.groupValues[2]]
        val qty = m.groupValues[5].replace(",", "").toDoubleOrNull()
        val price = m.groupValues[6].replace(",", "").toDoubleOrNull()
        if (month == null || qty == null || qty <= 0 || price == null || price <= 0) { skipped++; continue }

        txs.add(RevolutTx(
            date = "${m.groupValues[3]}-$month-${m.groupValues[1].padStart(2, '0')}",
            symbol = m.groupValues[4],
            qty = qty,
            price = price,
            currency = currency,
            isSell = m.groupValues[7] == "Sell",
        ))
    }

    return RevolutTradingLines(txs, bySymbol, skipped)
}

/** Mirrors parseRevolutTrading in src/utils/pdfParser.ts. */
suspend fun parseRevolutTrading(lines: List<String>, lookupIsins: IsinLookup, lookupTickers: TickerLookup): ParseResult {
    val (txs, bySymbol, skipped) = parseRevolutTradingLines(lines)
    if (txs.isEmpty()) return ParseResult(valid = emptyList(), skipped = skipped)

    val isinMap = lookupIsins(bySymbol.values.map { it.isin }.distinct())
    val orphans = txs.map { it.symbol }.filter { it !in bySymbol }.distinct()
    val tickerMap = if (orphans.isNotEmpty()) lookupTickers(orphans) else emptyMap()

    val rawLots = txs.map { tx ->
        val info = bySymbol[tx.symbol]
        val resolved = if (info != null) isinMap[info.isin] else tickerMap[tx.symbol]
        RawLot(
            ticker = resolved?.ticker ?: tx.symbol,
            name = info?.name ?: tx.symbol,
            qty = tx.qty,
            price = tx.price,
            date = tx.date,
            currency = tx.currency,
            broker = "Revolut",
            isin = info?.isin,
            type = resolved?.type ?: PositionType.STOCK,
            isSell = tx.isSell,
        )
    }

    return ParseResult(valid = applyFifo(rawLots), skipped = skipped)
}
