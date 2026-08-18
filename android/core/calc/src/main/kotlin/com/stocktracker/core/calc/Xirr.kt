package com.stocktracker.core.calc

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

data class CashFlow(val date: String, val amount: Double)

private const val DAYS_PER_YEAR = 365.25
private const val MAX_ITERATIONS = 200

/**
 * Newton-Raphson with bisection fallback. Mirrors src/utils/xirr.ts exactly —
 * same tolerances, same iteration caps, same bracket [-0.999, 10].
 */
fun xirr(cashFlows: List<CashFlow>): Double? {
    if (cashFlows.size < 2) return null

    val t0 = LocalDate.parse(cashFlows[0].date)
    val years = cashFlows.map { ChronoUnit.DAYS.between(t0, LocalDate.parse(it.date)) / DAYS_PER_YEAR }
    val amounts = cashFlows.map { it.amount }

    fun f(r: Double) = amounts.indices.sumOf { i -> amounts[i] / Math.pow(1 + r, years[i]) }
    fun df(r: Double) = amounts.indices.sumOf { i -> -(years[i] * amounts[i]) / Math.pow(1 + r, years[i] + 1) }

    var r = 0.1
    for (i in 0 until MAX_ITERATIONS) {
        val fr = f(r)
        if (abs(fr) < 1e-8) return r
        val dfr = df(r)
        if (dfr == 0.0) break
        val next = r - fr / dfr
        if (!next.isFinite() || next <= -1.0) break
        if (abs(next - r) < 1e-10) return next
        r = next
    }

    val lo0 = -0.999
    val hi0 = 10.0
    val signLo = sign(f(lo0))
    if (signLo == sign(f(hi0))) return null

    var lo = lo0
    var hi = hi0
    for (i in 0 until MAX_ITERATIONS) {
        val mid = (lo + hi) / 2
        if (abs(hi - lo) < 1e-8) return mid
        if (sign(f(mid)) == signLo) lo = mid else hi = mid
    }
    return (lo + hi) / 2
}

private fun sign(x: Double): Int = when {
    x > 0 -> 1
    x < 0 -> -1
    else -> 0
}
