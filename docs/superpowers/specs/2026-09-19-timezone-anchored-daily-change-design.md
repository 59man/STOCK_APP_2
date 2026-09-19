# Time-Zone-Anchored Today's Change (Web + Android)

Date: 2026-09-19
Status: Design approved, ready for implementation planning
Related: `2026-09-19-android-instrument-info-sort-logos-design.md` (Part 2 of that
spec adds the Android time zone setting this feature reads)

## Problem

"Today's change" currently means *change since the exchange's previous session
close*, not *change since midnight where the user lives*.

Today, in both apps:

```
quote.change   = price − previousClose        // exchange session boundary
row.dailyChange = toRow(quote.change) × openQty
percent         = dailyChange / (currentValue − dailyChange) × 100
```

Consequences the user sees:

- On a Saturday, an Apple position still reports Friday's session move as
  "today's".
- On a Tuesday morning before the US open, a US holding reports Monday's move.
- Crypto is anchored to **UTC** midnight (`BTC-USD`'s `exchangeTimezoneName` is
  `UTC`), which is one to two hours off Central European midnight — so between
  CET midnight and UTC midnight the number covers the wrong day outright.
- Currency movement is excluded entirely: a USD holding displayed in CZK shows
  the stock's move but not the koruna's, so the figure does not describe the
  change in what the position is actually worth to the user.

## Definition

For every position, as of the user's configured time zone:

```
localMidnight = start of the current day in the user's zone
anchorPrice   = last traded price at or before localMidnight (native currency)
anchorFx      = native→display FX rate at or before localMidnight
todaysChange  = openQty × (price_now × fx_now − anchorPrice × anchorFx)
todaysPercent = todaysChange / (openQty × anchorPrice × anchorFx) × 100
```

If the instrument has not traded since `localMidnight`, the anchor is the
current price and the change is exactly zero.

**This is deliberate and is the point of the feature.** On a Saturday, a US
holding reports `0.00`, not Friday's session move. Before the market opens on a
weekday morning it likewise reports `0.00`. The number answers "what has this
position done since midnight, my time", and when nothing has traded, the honest
answer is nothing. Falling back to the last session's move in these cases was
considered and rejected.

Because the anchor is a property of the day, it changes only at local midnight —
so it is fetched at most once per ticker per local day and cached, not
recomputed on every quote refresh.

### Two figures, not one

Including FX movement makes the headline number differ from what broker apps
(XTB, Revolut, IBKR) show, because they all quote price-only change. Both are
therefore surfaced:

- **Headline** — the full figure above, including currency movement. This is the
  real change in what the position is worth in the display currency.
- **Sub-line** — the price-only component, `openQty × (price_now − anchorPrice) ×
  fx_now`, labelled so it is recognisable as the broker-style number.

When the display currency equals the position's currency the two are identical
and the sub-line is omitted.

## Anchor resolution

A hybrid, chosen so the common case costs almost nothing and the hard case is
still exact.

### Step 1 — has it traded today at all?

`meta.regularMarketTime <= localMidnight` means no trade since local midnight.
Anchor is the current price, change is zero, and no further fetch happens. This
covers every weekend and every pre-open morning, which is most of the time.

### Step 2 — daily bars, when no session straddles local midnight

Fetch `range=5d&interval=1d`. Each daily bar's timestamp is the session's open;
its close lands at the session end, derivable from the same
`meta.currentTradingPeriod.regular` window the instrument-info feature already
parses. The anchor is the close of the last session that **ended** at or before
`localMidnight`.

For a Central European user this resolves nearly everything: European, US and
Asian sessions all begin and end within one CET calendar day.

### Step 3 — intraday bars, when a session straddles local midnight

If a session was in progress at `localMidnight` — always the case for crypto,
which trades continuously, and possible for any exchange depending on the user's
zone — daily bars cannot answer the question. Fetch
`range=2d&interval=5m` and take the last bar at or before `localMidnight`.

Measured payloads: 157 bars for AAPL, 156 for 8306.T, 409 for BTC-USD. Fetched
once per ticker per day, this is negligible.

### Step 4 — fallback

If no anchor can be resolved (fetch failure, history gap, a manual-priced
instrument with no feed), fall back to the **current** behaviour —
`price − previousClose` — rather than reporting zero. A silent zero is
indistinguishable from a flat day and would quietly under-report the portfolio
total. The row records which method produced its number so the UI can mark a
fallback.

### FX anchor

The same resolution applies to each `<native>→<display>` rate, fetched from the
corresponding Yahoo FX pair. FX pairs trade Sunday evening to Friday evening, so
on a weekend the FX anchor equals the current rate and the currency component is
zero — consistent with the price component being zero for the same reason.

Rates are cached per currency pair per local day. `CZK→CZK` is 1 by definition
and never fetched.

## Presentation

Row and summary numbers become midnight-anchored everywhere they appear:
position rows, position detail, and the portfolio summary's today total (the sum
of the anchored row values, so header and rows agree).

When the anchor shows no trading has happened today, the zero is annotated with
a short muted hint naming the last session — for example `closed · last traded
Fri`. Without it, a column of zeros on a weekend is indistinguishable from a
failed price fetch, which is the main risk this feature introduces.

A row whose number came from the Step 4 fallback is marked distinctly (a muted
`prev. close` hint), so a stale or unavailable anchor is visible rather than
silently changing what the number means.

The P&L chart is unchanged.

## Web implementation

- **Time zone setting** — new web-only setting, stored per browser, defaulting to
  `Intl.DateTimeFormat().resolvedOptions().timeZone`. A picker in the header
  next to the currency switcher, following the existing `.currency-tabs`
  pattern. Not synced to the Android setting; the two are independent by
  design.
- **New `src/hooks/useDailyAnchors.ts`** — owns anchor resolution and its
  per-local-day cache, keyed `TICKER::YYYY-MM-DD`, mirroring the module-level
  cache pattern already used by `useQuotes` and `useDividends`. Exposes
  `anchorFor(ticker)` and `fxAnchorFor(from, to)`.
- **New `src/utils/dailyChange.ts`** — the pure calculation, unit-tested
  alongside `money.test.ts`: it takes quantity, current price, anchor price,
  current and anchor FX, and returns the full and price-only figures plus which
  method produced them. No fetching, no React.
- **`PortfolioContent.tsx`** — `dailyChange` is computed through the new utility.
  `PortfolioRow` gains `anchorPrice`, `anchorMethod`, and `lastTradedAt`;
  `dailyChange` keeps its meaning as the headline figure so existing consumers
  do not change shape.
- **`PortfolioTable.tsx`** — the today cell renders the sub-line and the
  closed/fallback hints; the summary card sums the anchored values.

## Android implementation

- **Time zone** — reads `SettingsRepository.effectiveZoneId`, added by Part 2 of
  the instrument-info spec. This feature therefore lands after that part.
- **`core/calc/DailyChange.kt`** — a direct port of `dailyChange.ts`, with the
  same test cases, so the two apps cannot drift. This follows the existing
  `Xirr.kt` / `FifoMatcher.kt` / `ChartMath.kt` precedent.
- **`core/network/AnchorClient.kt`** — the three-step resolution against Yahoo,
  direct from the phone like every other market-data call.
- **Caching** — anchors live in a Room table keyed by ticker and local date,
  pruned to the current and previous day. This rides the same `Migration(1,2)`
  as the instrument profile table if both ship together; otherwise it takes the
  next version number. Room rather than memory so a cold start in the morning
  does not refetch every anchor.
- **`RowDerivation.kt`** — takes anchors as a parameter and produces the anchored
  `dailyChange`, plus the new fields, exactly as the web does.
- **UI** — `PositionCard`, `PositionDetailRoute` and the Insights summary render
  the sub-line and hints.

## Testing

Shared cases, implemented in both `src/utils/dailyChange.test.ts` and
`core/calc`'s `DailyChangeTest.kt` with identical inputs and expected values:

- No trade since local midnight → zero, method `noTradeToday`.
- Normal intraday move, display currency equal to native → headline equals
  price-only.
- Foreign-currency holding where the price is flat but FX moved → headline
  non-zero, price-only zero.
- Foreign-currency holding where price and FX move in opposite directions →
  headline smaller than price-only, signs as expected.
- Missing anchor → fallback to `previousClose`, method `prevCloseFallback`.
- Zero and negative anchor prices → no division by zero in the percentage.
- Closed position → zero, as today.

Anchor resolution is tested separately against recorded fixtures: a daily-bar
case, an intraday straddling case (crypto), a weekend case, and a fetch failure.
Android uses MockWebServer with wire-byte assertions, per the `DeviceApiTest`
precedent.

One end-to-end check per app with the device clock set either side of local
midnight, confirming the figure resets.

## Risks

| Risk | Mitigation |
|---|---|
| Zeros on weekends read as a broken feed | Explicit muted "closed · last traded <day>" hint next to the zero. |
| Headline disagrees with broker apps because it includes FX | Price-only figure shown as a sub-line, labelled, so both numbers are visible. |
| Extra Yahoo calls trigger rate limiting | One fetch per ticker per local day, cached; Step 1 avoids the fetch entirely whenever nothing traded today. |
| The two apps' definitions drift | The calculation is a pure function ported line-for-line with a shared test table, following the existing `core:calc` precedent. |
| An anchor fetch fails and the number silently changes meaning | Explicit fallback to the current previous-close behaviour, marked in the UI. |

## Out of scope

- Changing the P&L chart's handling of today's bar.
- Syncing the web time zone setting to Android.
- Intraday history anywhere other than anchor resolution.
