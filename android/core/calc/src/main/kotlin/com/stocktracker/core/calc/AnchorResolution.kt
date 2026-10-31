package com.stocktracker.core.calc

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Finding the price a position was worth at midnight in the user's own zone.
 *
 * Mirrors src/utils/anchorResolution.ts. Split from the fetching so the date arithmetic — the
 * part that is easy to get subtly wrong and impossible to eyeball — is tested without network.
 */

/** One bar: [epoch] is seconds, and Yahoo stamps a daily bar at its session open. */
data class AnchorBar(val epoch: Long, val close: Double)

/** Start of today in [zone], epoch seconds. */
fun localMidnightEpoch(zone: ZoneId, now: Instant = Instant.now()): Long =
    LocalDate.ofInstant(now, zone).atStartOfDay(zone).toEpochSecond()

/**
 * Whether daily bars can answer the question, or whether a session was in progress at local
 * midnight and only intraday bars will do.
 *
 * True for anything with no session window, and for any session that straddles this viewer's
 * midnight. Yahoo's window is only the *latest* session, not the one running at midnight: BTC's
 * rolls over at 00:00 UTC, so in Prague from 01:00 on it starts after local midnight. Without
 * [dailyBars], which carry each session's own open, that read as "no session at midnight" and the
 * anchor became an older daily close, for nearly the whole day.
 */
fun needsIntraday(sessionStart: Long?, sessionEnd: Long?, midnight: Long, dailyBars: List<AnchorBar> = emptyList()): Boolean {
    if (sessionStart == null || sessionEnd == null) return true
    if (sessionStart < midnight && sessionEnd > midnight) return true
    val length = sessionEnd - sessionStart
    return dailyBars.any { it.epoch < midnight && it.epoch + length > midnight }
}

/**
 * The close of the last session that *ended* at or before local midnight. A daily bar is
 * stamped at its session open, so [sessionLengthSeconds] is what turns that into a session end.
 */
fun resolveAnchorFromDaily(bars: List<AnchorBar>, midnight: Long, sessionLengthSeconds: Long): Double? {
    var anchor: Double? = null
    for (bar in bars) {
        if (bar.epoch + sessionLengthSeconds <= midnight) anchor = bar.close else break
    }
    return anchor
}

/** The last intraday bar at or before local midnight. */
fun resolveAnchorFromIntraday(bars: List<AnchorBar>, midnight: Long): Double? {
    var anchor: Double? = null
    for (bar in bars) {
        if (bar.epoch <= midnight) anchor = bar.close else break
    }
    return anchor
}
