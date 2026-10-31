package com.stocktracker.core.data

import com.stocktracker.core.database.ManualPriceEntity
import com.stocktracker.core.database.PortfolioEntity
import com.stocktracker.core.database.PositionEntity
import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Portfolio
import com.stocktracker.core.model.Position

fun PositionEntity.toDomain(): Position = Position(
    id = id, ticker = ticker, name = name, type = type, quantity = quantity,
    buyPrice = buyPrice, buyDate = buyDate, currency = currency, broker = broker,
    isin = isin, sellPrice = sellPrice,
    // A blank sell date means unsold on the web too; every calc here only checks for null.
    sellDate = sellDate?.takeIf { it.isNotBlank() },
)

fun Position.toEntity(portfolioId: String): PositionEntity = PositionEntity(
    id = id, portfolioId = portfolioId, ticker = ticker, name = name, type = type,
    quantity = quantity, buyPrice = buyPrice, buyDate = buyDate, currency = currency,
    broker = broker, isin = isin, sellPrice = sellPrice, sellDate = sellDate,
)

fun PortfolioEntity.toDomain(): Portfolio = Portfolio(id = id, name = name)
fun Portfolio.toEntity(): PortfolioEntity = PortfolioEntity(id = id, name = name)

fun ManualPriceEntity.toDomain(): ManualPriceEntry = ManualPriceEntry(price = price, updatedAt = updatedAt)
