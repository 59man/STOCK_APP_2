package com.stocktracker.core.importer

import com.stocktracker.core.model.Position

private const val EPS = 1e-6

private fun Position.isClosed() = sellPrice != null && !sellDate.isNullOrEmpty()
private fun Position.buyKey() = listOf(ticker.uppercase(), buyDate, "%.6f".format(java.util.Locale.ROOT, buyPrice), (broker ?: "").lowercase())

data class ImportPlan(
    /** The whole portfolio after the import. */
    val positions: List<Position>,
    val added: Int,
    val duplicates: Int,
)

private class Entry(var lot: Position, var unclaimed: Double)

/**
 * Merges a parsed statement into a portfolio, so re-importing one, or one overlapping an earlier
 * one, counts nothing twice. Matches shares of the same buy rather than whole lots, because FIFO
 * splits a buy differently once a later statement contains its sale. A closed incoming lot closes
 * the matching open shares rather than being dropped, so a newer statement can record a sale.
 * Mirrors planImport in dedupe.ts.
 */
fun planImport(existing: List<Position>, incoming: List<Position>): ImportPlan {
    val entries = existing.map { Entry(it, it.quantity) }
    val fresh = mutableListOf<Position>()
    var duplicates = 0

    // Closed lots first, so they claim open shares before an open lot from the same statement does.
    for (lot in incoming.filter { it.isClosed() } + incoming.filterNot { it.isClosed() }) {
        fun claim(wanted: Double, shrink: Boolean, matches: (Position) -> Boolean): Double {
            var need = wanted
            for (e in entries) {
                if (need <= EPS) break
                if (e.lot.buyKey() != lot.buyKey() || !matches(e.lot) || e.unclaimed <= EPS) continue
                val qty = minOf(e.unclaimed, need)
                e.unclaimed -= qty
                if (shrink) e.lot = e.lot.copy(quantity = e.lot.quantity - qty)
                need -= qty
            }
            return need
        }

        val unmatched = if (lot.isClosed()) {
            claim(lot.quantity, shrink = false) { it.isClosed() && it.sellDate == lot.sellDate && it.sellPrice == lot.sellPrice }
        } else {
            claim(lot.quantity, shrink = false) { true }
        }
        if (unmatched <= EPS) {
            duplicates++
            continue
        }
        if (lot.isClosed()) claim(unmatched, shrink = true) { !it.isClosed() }
        fresh.add(lot.copy(quantity = unmatched))
    }

    return ImportPlan(
        positions = entries.map { it.lot }.filter { it.quantity > EPS } + fresh,
        added = fresh.size,
        duplicates = duplicates,
    )
}
