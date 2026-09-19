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
  const parts = new Intl.DateTimeFormat('en-CA', {
    timeZone: zone,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
    hour12: false,
  }).formatToParts(at)

  const get = (type: string) => Number(parts.find((p) => p.type === type)?.value ?? 0)
  const asUtc = Date.UTC(get('year'), get('month') - 1, get('day'), get('hour'), get('minute'), get('second'))
  // How far the zone's wall clock is ahead of UTC at this instant.
  const offsetMs = asUtc - Math.floor(at.getTime() / 1000) * 1000
  const midnightUtc = Date.UTC(get('year'), get('month') - 1, get('day'))
  return Math.round((midnightUtc - offsetMs) / 1000)
}

/**
 * Whether daily bars can answer the question, or whether a session was in progress at local
 * midnight and only intraday bars will do.
 *
 * True for anything trading continuously — crypto reports no session windows at all — and for
 * any exchange whose session happens to straddle this particular viewer's midnight.
 */
export function needsIntraday(periods: SessionPeriods | null | undefined, midnight: number): boolean {
  const regular = periods?.regular
  if (!regular) return true
  return regular.start < midnight && regular.end > midnight
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
