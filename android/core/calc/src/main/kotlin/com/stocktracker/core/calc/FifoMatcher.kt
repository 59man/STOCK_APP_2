package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import java.util.UUID

/** Intermediate struct fed by the import parsers, before FIFO matching. Mirrors RawLot in src/utils/fifoMatcher.ts. */
data class RawLot(
    val ticker: String,
    val name: String,
    val qty: Double,
    val price: Double,
    val date: String,
    val currency: String,
    val broker: String?,
    val isin: String? = null,
    val type: PositionType,
    val isSell: Boolean,
)

private const val EPSILON = 1e-6

private class BuyEntry(val lot: RawLot, var remaining: Double)

/**
 * FIFO lot matcher. Mirrors src/utils/fifoMatcher.ts exactly, including the
 * 1e-6 epsilon that governs partial-lot-consumed boundary behavior, and the
 * silent-drop of any sell quantity left over once the buy queue is exhausted.
 */
fun applyFifo(lots: List<RawLot>): List<Position> {
    val result = mutableListOf<Position>()

    for ((_, group) in lots.groupBy { it.ticker }) {
        val buys = group.filter { !it.isSell }.sortedBy { it.date }
        val sells = group.filter { it.isSell }.sortedBy { it.date }

        if (sells.isEmpty()) {
            buys.forEach { result.add(it.toOpenPosition()) }
            continue
        }

        val queue = buys.map { BuyEntry(it, it.qty) }
        var qi = 0

        for (sell in sells) {
            var toSell = sell.qty
            while (toSell > EPSILON && qi < queue.size) {
                val entry = queue[qi]
                val closedQty = minOf(entry.remaining, toSell)
                result.add(entry.lot.toClosedPosition(closedQty, sell))
                entry.remaining -= closedQty
                toSell -= closedQty
                if (entry.remaining < EPSILON) qi++
            }
            // toSell left over here (buy queue exhausted) is silently dropped, matching the source.
        }

        if (qi < queue.size) {
            val first = queue[qi]
            if (first.remaining > EPSILON) result.add(first.lot.toOpenPosition(quantity = first.remaining))
            for (i in (qi + 1) until queue.size) {
                result.add(queue[i].lot.toOpenPosition())
            }
        }
    }

    return result
}

private fun RawLot.toOpenPosition(quantity: Double = qty): Position = Position(
    id = UUID.randomUUID().toString(),
    ticker = ticker,
    name = name,
    type = type,
    quantity = quantity,
    buyPrice = price,
    buyDate = date,
    currency = currency,
    broker = broker,
    isin = isin,
)

private fun RawLot.toClosedPosition(quantity: Double, sell: RawLot): Position = Position(
    id = UUID.randomUUID().toString(),
    ticker = ticker,
    name = name,
    type = type,
    quantity = quantity,
    buyPrice = price,
    buyDate = date,
    currency = currency,
    broker = broker,
    isin = isin,
    sellPrice = sell.price,
    sellDate = sell.date,
)
