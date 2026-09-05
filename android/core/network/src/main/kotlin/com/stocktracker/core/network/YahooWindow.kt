package com.stocktracker.core.network

/**
 * Query-string builders for Yahoo Finance's v8 chart API. Mirrors
 * src/utils/yahooWindow.ts.
 *
 * `range=max` must never be sent: Yahoo silently ignores the requested
 * `interval` and answers with 3mo bars for equities / 1mo bars for FX. For
 * `events=div` that is not merely a resolution loss — the dividend map comes
 * back covering 1962-2003 plus only the single most recent event, so every
 * distribution paid in the last ~20 years except the newest disappears,
 * under-reporting dividend income, total return and IRR. An explicit
 * period1/period2 window is what makes Yahoo honour the interval.
 *
 * `period1=0` is the Unix epoch — history before 1970 is unreachable this way,
 * no lot can predate that, and it is the earliest value Yahoo accepts.
 */
private fun epochWindow(nowMs: Long) = "period1=0&period2=${nowMs / 1000}"

/** Interval + window for one ticker's price history at a range-selector value. */
fun yahooChartQuery(range: String, nowMs: Long = System.currentTimeMillis()): String {
    if (range != "max") return "interval=1d&range=$range"
    // An unbounded daily request is correct but huge — 14,291 bars / 1.4 MB for a
    // ticker listed since 1962, per ticker, and the portfolio chart fetches one per
    // holding. Weekly bars over the same window are ~5x smaller and still far finer
    // than the 3mo bars `range=max` used to yield.
    return "interval=1wk&${epochWindow(nowMs)}"
}

/**
 * Interval + window for a ticker's dividend event history.
 *
 * Weekly by default: Yahoo emits at most one dividend per bar, so the interval caps how
 * many events survive — 3mo collapses 227 JNJ payouts to 168 and 1mo drops one — while
 * weekly returned event-for-event identical sets to daily for JNJ, VIG.PR, 8306.T and
 * DTE.DE at ~1/5 the payload. Weekly-distribution ETFs (QDTE, XDTE, ...) *would* saturate
 * a weekly bar, so callers detect that (one event per bar) and retry with "1d", which no
 * real distribution schedule can saturate.
 */
fun yahooDividendQuery(interval: String = "1wk", nowMs: Long = System.currentTimeMillis()): String =
    "interval=$interval&${epochWindow(nowMs)}&events=div"

/** Interval + window for a CUR->CZK rate history. */
fun yahooFxHistoryQuery(nowMs: Long = System.currentTimeMillis()): String =
    // Daily, unlike the charts above: convertAt() resolves every chart point at that
    // date's own rate, so weekly bars would stale each conversion by up to 7 days.
    "interval=1d&${epochWindow(nowMs)}"
