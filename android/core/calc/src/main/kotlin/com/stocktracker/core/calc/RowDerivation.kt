package com.stocktracker.core.calc

import com.stocktracker.core.model.DivTaxOverrides
import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.FX_CONVERTED_TICKERS
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.Quote

/** CZK-base FX conversion, injected so this module stays pure and testable. */
typealias Convert = (Double, String, String) -> Double

private fun isOpenLot(lot: Position): Boolean {
    val sellPrice = lot.sellPrice
    val sellDate = lot.sellDate
    return sellPrice == null || sellPrice == 0.0 || sellDate == null || sellDate.isEmpty()
}

private fun isClosedLot(lot: Position): Boolean =
    lot.sellPrice != null && !lot.sellDate.isNullOrEmpty()

/**
 * One aggregated ticker row. Mirrors the `rows` useMemo in
 * src/components/PortfolioContent.tsx line-for-line — see that file before
 * changing anything here, this is a faithful port, not a redesign.
 *
 * `quote`/`loading`/`error`/`manual` are the *raw* per-ticker values (before
 * the source's isClosed-nulling) — this function applies that nulling
 * internally, exactly like the source does by shadowing `quote` etc. once
 * at the top of its row-building closure.
 */
fun deriveRow(
    lots: List<Position>,
    quoteRaw: Quote?,
    loadingRaw: Boolean,
    errorRaw: String?,
    manualRaw: ManualPriceEntry?,
    dividends: List<DividendEvent>,
    taxOverrides: DivTaxOverrides = emptyMap(),
    today: String,
    convert: Convert,
): PortfolioRow {
    require(lots.isNotEmpty()) { "lots must not be empty" }
    val ticker = lots.first().ticker

    val openLots = lots.filter(::isOpenLot)
    val closedLots = lots.filter(::isClosedLot)
    val isClosed = openLots.isEmpty()

    val rowCurrency = lots.first().currency
    fun toRow(amount: Double, lotCurrency: String): Double = convert(amount, lotCurrency, rowCurrency)

    val totalQty = lots.sumOf { it.quantity }
    val openQty = openLots.sumOf { it.quantity }
    val totalCost = lots.sumOf { toRow(it.buyPrice * it.quantity, it.currency) }
    val openCost = openLots.sumOf { toRow(it.buyPrice * it.quantity, it.currency) }
    val avgBuyPrice = totalCost / totalQty
    val firstBuyDate = lots.minOf { it.buyDate }

    // Source nulls these out once isClosed, then uses that nulled value everywhere below.
    val quote = if (isClosed) null else quoteRaw
    val isLoading = !isClosed && loadingRaw
    val error = if (isClosed) null else errorRaw
    val manual = if (isClosed) null else manualRaw
    val priceIsManual = !isClosed && quote == null && manual != null

    val fxEntry = FX_CONVERTED_TICKERS[ticker.uppercase()]
    val nativeCurrency = fxEntry?.fxTicker?.take(3) ?: quote?.currency ?: rowCurrency

    val closedQty = closedLots.sumOf { it.quantity }
    val avgSellPrice = if (closedLots.isNotEmpty()) {
        closedLots.sumOf { toRow((it.sellPrice ?: 0.0) * it.quantity, it.currency) } / closedQty
    } else 0.0
    val openAvgBuy = if (openQty > 0) openCost / openQty else 0.0
    val quotePrice = quote?.let { toRow(it.price, it.currency) }
    val currentPrice = if (isClosed) avgSellPrice else (quotePrice ?: manual?.price ?: avgBuyPrice)
    val currentValue = if (isClosed) 0.0 else currentPrice * openQty

    val divCurrency = dividends.firstOrNull()?.currency ?: rowCurrency
    val dividendIncome = toRow(calcNetDividends(lots, dividends, ticker, taxOverrides), divCurrency)

    val realizedPnl = closedLots.sumOf { toRow(((it.sellPrice ?: 0.0) - it.buyPrice) * it.quantity, it.currency) }
    val unrealizedPnl = if (isClosed) 0.0 else (currentPrice - openAvgBuy) * openQty
    val pricePnl = realizedPnl + unrealizedPnl
    val totalReturn = pricePnl + dividendIncome
    val pnlPercent = if (totalCost > 0) pricePnl / totalCost * 100 else 0.0

    val hasUsablePrice = isClosed || (!isLoading && (quote != null || manual != null))
    val irr: Double? = if (!hasUsablePrice) null else {
        val flows = mutableListOf<CashFlow>()
        lots.forEach { flows.add(CashFlow(it.buyDate, -toRow(it.buyPrice * it.quantity, it.currency))) }
        closedLots.forEach { flows.add(CashFlow(it.sellDate!!, toRow(it.sellPrice!! * it.quantity, it.currency))) }
        dividends.forEach { div ->
            val shares = lots.filter { lot ->
                val sd = lot.sellDate
                lot.buyDate <= div.date && (sd == null || sd > div.date)
            }.sumOf { it.quantity }
            if (shares != 0.0) {
                val rate = taxOverrides["${ticker.uppercase()}::${div.date}"] ?: getDividendTaxRate(ticker)
                flows.add(CashFlow(div.date, toRow(shares * div.amount * (1 - rate), div.currency)))
            }
        }
        if (!isClosed) flows.add(CashFlow(today, currentValue))
        xirr(flows)
    }

    val dailyChange = if (isClosed || quote == null) 0.0 else toRow(quote.change, quote.currency) * openQty

    return PortfolioRow(
        ids = lots.map { it.id },
        ticker = ticker,
        name = lots.first().name,
        type = lots.first().type,
        currency = rowCurrency,
        nativeCurrency = nativeCurrency,
        lots = lots.size,
        positions = lots.sortedBy { it.buyDate },
        totalQuantity = totalQty,
        avgBuyPrice = avgBuyPrice,
        firstBuyDate = firstBuyDate,
        currentPrice = currentPrice,
        currentValue = currentValue,
        costBasis = totalCost,
        pnl = pricePnl,
        pnlPercent = pnlPercent,
        dividendIncome = dividendIncome,
        totalReturn = totalReturn,
        loading = isLoading,
        error = error,
        priceIsManual = priceIsManual,
        manualPriceDate = manual?.updatedAt,
        irr = irr,
        isClosed = isClosed,
        dailyChange = dailyChange,
    )
}

/**
 * Portfolio-wide IRR — a separate aggregation over every individual position,
 * not an average of per-row IRRs (XIRR isn't additive). Mirrors the
 * `portfolioIrr` useMemo in src/components/PortfolioContent.tsx.
 */
fun computePortfolioIrr(
    positions: List<Position>,
    rows: List<PortfolioRow>,
    dividendsByTicker: Map<String, List<DividendEvent>>,
    taxOverrides: DivTaxOverrides = emptyMap(),
    displayCurrency: String,
    today: String,
    convert: Convert,
): Double? {
    if (positions.isEmpty()) return null

    val anyLoading = rows.any { it.loading }
    val anyMissingPrice = rows.any { it.error == null && it.irr == null && !it.loading }
    if (anyLoading || anyMissingPrice) return null

    fun toDc(amount: Double, currency: String) = convert(amount, currency, displayCurrency)

    val totalCurrentValue = rows.sumOf { toDc(it.currentValue, it.currency) }

    val flows = mutableListOf<CashFlow>()
    positions.forEach { pos -> flows.add(CashFlow(pos.buyDate, -toDc(pos.buyPrice * pos.quantity, pos.currency))) }
    positions.filter(::isClosedLot).forEach { pos ->
        flows.add(CashFlow(pos.sellDate!!, toDc(pos.sellPrice!! * pos.quantity, pos.currency)))
    }
    positions.forEach { pos ->
        val divs = dividendsByTicker[pos.ticker.uppercase()] ?: emptyList()
        divs.forEach { div ->
            val sellDate = pos.sellDate
            if (pos.buyDate <= div.date && (sellDate == null || sellDate > div.date)) {
                val rate = taxOverrides["${pos.ticker.uppercase()}::${div.date}"] ?: getDividendTaxRate(pos.ticker)
                flows.add(CashFlow(div.date, toDc(pos.quantity * div.amount * (1 - rate), div.currency)))
            }
        }
    }
    flows.add(CashFlow(today, totalCurrentValue))

    return xirr(flows)
}
