package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import kotlin.math.abs

private const val EPS = 1e-6

private fun sameNumber(a: Double, b: Double): Boolean = abs(a - b) < EPS
private fun sameNullableNumber(a: Double?, b: Double?): Boolean =
    if (a == null || b == null) a == b else sameNumber(a, b)

/**
 * True when [candidate] looks like the same transaction as [existing] — same broker statement
 * re-uploaded a second time. Matches on ticker + buy date/qty/price + broker (the fields a
 * monthly statement can't change between exports), plus sell date/price when both carry one.
 */
private fun isDuplicate(candidate: Position, existing: Position): Boolean =
    candidate.ticker.equals(existing.ticker, ignoreCase = true) &&
        candidate.buyDate == existing.buyDate &&
        sameNumber(candidate.quantity, existing.quantity) &&
        sameNumber(candidate.buyPrice, existing.buyPrice) &&
        (candidate.broker ?: "").equals(existing.broker ?: "", ignoreCase = true) &&
        candidate.sellDate == existing.sellDate &&
        sameNullableNumber(candidate.sellPrice, existing.sellPrice)

/**
 * Drops any [candidates] entry that's already present in [existing] — re-uploading the same
 * monthly statement shouldn't double-count its transactions. Returns the positions actually
 * worth inserting, plus how many were dropped as duplicates.
 */
fun filterDuplicates(candidates: List<Position>, existing: List<Position>): Pair<List<Position>, Int> {
    if (existing.isEmpty()) return candidates to 0
    val toInsert = candidates.filterNot { candidate -> existing.any { isDuplicate(candidate, it) } }
    return toInsert to (candidates.size - toInsert.size)
}
