package com.stocktracker.core.calc

import com.stocktracker.core.model.Position
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Czech 3-year time test (časový test): a gain on securities held more than three years is
 * exempt. Estimate only. Mirrors src/utils/taxTest.ts against test-fixtures/tax/cases.json.
 */

/** First date a sale is exempt: buy date + 3 years (29 Feb clamps to 28 Feb) + 1 day. */
fun timeTestDate(buyDate: String): String = LocalDate.parse(buyDate).plusYears(3).plusDays(1).toString()

enum class TaxKind { EXEMPT, TAXABLE, PENDING }

data class LotTaxStatus(val kind: TaxKind, val freeFrom: String, val daysLeft: Long)

fun lotTaxStatus(buyDate: String, sellDate: String?, sellPrice: Double?, today: String): LotTaxStatus {
    val freeFrom = timeTestDate(buyDate)
    if (sellPrice != null && !sellDate.isNullOrEmpty()) {
        return LotTaxStatus(if (sellDate >= freeFrom) TaxKind.EXEMPT else TaxKind.TAXABLE, freeFrom, 0)
    }
    val daysLeft = maxOf(0L, ChronoUnit.DAYS.between(LocalDate.parse(today), LocalDate.parse(freeFrom)))
    return LotTaxStatus(if (daysLeft == 0L) TaxKind.EXEMPT else TaxKind.PENDING, freeFrom, daysLeft)
}

fun lotTaxStatus(lot: Position, today: String): LotTaxStatus = lotTaxStatus(lot.buyDate, lot.sellDate, lot.sellPrice, today)

data class YearSummary(
    val year: Int,
    val proceedsTaxable: Double,
    val gainTaxable: Double,
    val gainExempt: Double,
    /** Gross proceeds of taxable sales stay within the 100 000 CZK annual small-sales limit. */
    val underSmallProceedsLimit: Boolean,
)

const val SMALL_PROCEEDS_LIMIT_CZK = 100_000.0

/** Realized gains per sell year, in CZK at each trade's own date. Newest year first. */
fun realizedByYear(positions: List<Position>, czkAt: (Double, String, String) -> Double): List<YearSummary> =
    positions.filter(::isClosedLot)
        .groupBy { it.sellDate!!.take(4).toInt() }
        .map { (year, lots) ->
            var proceedsTaxable = 0.0
            var gainTaxable = 0.0
            var gainExempt = 0.0
            lots.forEach { lot ->
                val proceeds = czkAt(lot.sellPrice!! * lot.quantity, lot.currency, lot.sellDate!!)
                val gain = proceeds - czkAt(lot.buyPrice * lot.quantity, lot.currency, lot.buyDate)
                if (lotTaxStatus(lot, lot.sellDate!!).kind == TaxKind.EXEMPT) {
                    gainExempt += gain
                } else {
                    gainTaxable += gain
                    proceedsTaxable += proceeds
                }
            }
            YearSummary(year, proceedsTaxable, gainTaxable, gainExempt, proceedsTaxable <= SMALL_PROCEEDS_LIMIT_CZK)
        }
        .sortedByDescending { it.year }
