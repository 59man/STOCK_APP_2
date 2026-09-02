package com.stocktracker.feature.portfolio

import com.stocktracker.core.model.DividendEvent
import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.PositionType

fun fakePosition(
    id: String = "lot-1",
    ticker: String = "AAPL",
    name: String = "Apple Inc.",
    type: PositionType = PositionType.STOCK,
    quantity: Double = 10.0,
    buyPrice: Double = 150.0,
    buyDate: String = "2024-01-15",
    currency: String = "USD",
    broker: String? = null,
    isin: String? = null,
    sellPrice: Double? = null,
    sellDate: String? = null,
) = Position(
    id = id, ticker = ticker, name = name, type = type, quantity = quantity,
    buyPrice = buyPrice, buyDate = buyDate, currency = currency, broker = broker,
    isin = isin, sellPrice = sellPrice, sellDate = sellDate,
)

fun fakeRow(
    ticker: String = "AAPL",
    name: String = "Apple Inc.",
    type: PositionType = PositionType.STOCK,
    currency: String = "USD",
    positions: List<Position> = listOf(fakePosition(ticker = ticker, name = name)),
    currentValue: Double = 1500.0,
    costBasis: Double = 1400.0,
    pnl: Double = 100.0,
    pnlPercent: Double = 7.1,
    dividendIncome: Double = 0.0,
    totalReturn: Double = 100.0,
    priceIsManual: Boolean = false,
    isClosed: Boolean = false,
    dailyChange: Double = 12.0,
    irr: Double? = 0.09,
) = PortfolioRow(
    ids = positions.map { it.id }, ticker = ticker, name = name, type = type,
    currency = currency, nativeCurrency = currency, lots = positions.size, positions = positions,
    totalQuantity = positions.sumOf { it.quantity }, avgBuyPrice = positions.map { it.buyPrice }.average(),
    firstBuyDate = positions.minOf { it.buyDate }, currentPrice = 155.0, currentValue = currentValue,
    costBasis = costBasis, pnl = pnl, pnlPercent = pnlPercent, dividendIncome = dividendIncome,
    totalReturn = totalReturn, loading = false, priceIsManual = priceIsManual, irr = irr,
    isClosed = isClosed, dailyChange = dailyChange,
)

fun fakeDividendEvent(
    date: String = "2024-06-01",
    amount: Double = 0.5,
    currency: String = "USD",
) = DividendEvent(date = date, amount = amount, currency = currency)
