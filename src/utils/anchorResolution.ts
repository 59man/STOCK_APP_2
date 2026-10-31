/**
 * Finding the price a position was worth at midnight in the user's own zone.
 *
 * Split out of the fetching so the date arithmetic — the part that is easy to get subtly wrong
 * and impossible to eyeball — can be tested without network.
 */

export interface Bar {
  /** Bar timestamp, epoch seconds. Yahoo's daily bars are stamped at the session open. */
  epoch: number
  close: number
}

export interface SessionWindow {
  start: number
  end: number
}

export interface SessionPeriods {
  regular?: SessionWindow
}

/**
 * Start of [at]'s day in [zone], as epoch seconds.
 *
 * Done by formatting rather than arithmetic because the offset is not a constant: it changes
 * at a DST boundary, and the day whose midnight is being computed may sit on the other side of
 * one from today.
 */
export function localMidnightEpoch(at: Date, zone: string): number {
  const wallClock = (instant: Date) => {
    const parts = new Intl.DateTimeFormat('en-CA', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    }).formatToParts(instant)
    const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0)
    return { y: get('year'), m: get('month'), d: get('day'), h: get('hour') % 24, min: get('minute'), s: get('second') }
  }
  // How far the zone's wall clock is ahead of UTC at an instant.
  const offsetAt = (instant: Date) => {
    const w = wallClock(instant)
    return Date.UTC(w.y, w.m - 1, w.d, w.h, w.min, w.s) - Math.floor(instant.getTime() / 1000) * 1000
  }
  const today = wallClock(at)
  const midnightUtc = Date.UTC(today.y, today.m - 1, today.d)
  // The offset that counts is the one in force at midnight, which on a DST day differs from now's.
  // Now's gets close enough to land on the right side of the change.
  const guess = midnightUtc - offsetAt(at)
  return Math.round((midnightUtc - offsetAt(new Date(guess))) / 1000)
}

/**
 * Whether daily bars can answer the question, or whether a session was in progress at local
 * midnight and only intraday bars will do.
 *
 * True for anything with no session window, and for any session that straddles this viewer's
 * midnight. Yahoo's window is only the *latest* session, not the one running at midnight: BTC's
 * rolls over at 00:00 UTC, so in Prague from 01:00 on it starts after local midnight. Without
 * `dailyBars`, which carry each session's own open, that read as "no session at midnight" and the
 * anchor became an older daily close, for nearly the whole day.
 */
export function needsIntraday(
  periods: SessionPeriods | null | undefined,
  midnight: number,
  dailyBars: Bar[] = [],
): boolean {
  const regular = periods?.regular
  if (!regular) return true
  if (regular.start < midnight && regular.end > midnight) return true
  const length = regular.end - regular.start
  return dailyBars.some((bar) => bar.epoch < midnight && bar.epoch + length > midnight)
}

/**
 * The close of the last session that *ended* at or before local midnight.
 *
 * Yahoo stamps a daily bar at its session open, so the session's end is approximated by adding
 * the session's length — which is why [sessionLengthSeconds] is passed in rather than assumed.
 */
export function resolveAnchorFromDaily(
  bars: Bar[],
  midnight: number,
  sessionLengthSeconds: number,
): number | null {
  let anchor: number | null = null
  for (const bar of bars) {
    if (bar.epoch + sessionLengthSeconds <= midnight) anchor = bar.close
    else break
  }
  return anchor
}

/** The last intraday bar at or before local midnight. */
export function resolveAnchorFromIntraday(bars: Bar[], midnight: number): number | null {
  let anchor: number | null = null
  for (const bar of bars) {
    if (bar.epoch <= midnight) anchor = bar.close
    else break
  }
  return anchor
}
