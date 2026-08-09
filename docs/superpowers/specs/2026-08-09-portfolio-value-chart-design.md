# Portfolio Value chart (Cost Basis + Current Value time series)

## Problem

`PortfolioPnLChart` (`src/components/PortfolioPnLChart.tsx`) currently shows only one
view: portfolio Total Return (price P&L + net dividends) over time. There is no way
to see how much capital is/was actually deployed (cost basis of open positions) or
how the mark-to-market value of open positions has moved over time.

## Goals

- Add a time series for **Cost Basis** of currently-open positions.
- Add a time series for **Current Value** (mark-to-market) of currently-open positions.
- Reuse the existing Total Return chart's infrastructure — no duplicate network
  fetching of Yahoo/FX histories.

## Non-goals

- No change to the existing Total Return view's math or visuals.
- Cost basis does **not** track cumulative lifetime capital invested (i.e. it is not
  monotonically increasing) — see "Cost basis definition" below.
- No new chart component/file — this is an extension of the existing one.

## Design

### Location: extend `PortfolioPnLChart.tsx` in place

The component already fetches per-ticker Yahoo histories, historical FX rates, and
builds `effectiveHistories` (synthetic histories for manual-priced funds, live-price
injection for today). All of that is unchanged and shared between both views.

Add a **view toggle** to the chart header: `'return' | 'value'`, defaulting to
`'return'`, persisted to `localStorage` key `chart_view_portfolio` (same pattern as
the existing `chart_range_portfolio` key for the range tabs). The existing range
tabs (1M/3M/6M/1Y/3Y/5Y/All) apply identically to both views.

Toggle UI reuses the existing `.pie-group-toggle` / `.pie-group-btn` CSS classes
(already shared by the Add Position modal's per-share/total price toggle — see
`.price-mode-toggle .pie-group-btn` in `App.css`). No new CSS classes needed for
the toggle itself.

### Cost basis definition

Per-lot, cost basis and current value are counted only while the lot is **open as
of the chart date**:

```text
included if: pos.buyDate <= date AND (!pos.sellDate || pos.sellDate > date)
```

This means cost basis is **not** cumulative-ever-invested — it rises on each buy
and **drops** when a lot is sold, tracking capital currently at risk in the
portfolio. This pairs directly with Current Value: the gap between the two lines
at any date is the unrealized gain/loss of currently-held positions at that
moment.

### Per-date computation (extends the existing `chartData` useMemo)

The existing per-date loop over `positions` already branches on `buyDate`/`sellDate`
to compute `pricePnl`. Two new accumulators are added alongside it, using the same
loop (no second pass over positions):

```text
for each position pos:
  if pos.buyDate > date: skip
  if pos.sellDate && pos.sellDate <= date: skip   // sold — contributes 0 to both

  costBasis    += convertAt(pos.buyPrice * pos.quantity, pos.currency, displayCurrency, pos.buyDate)
  currentValue += convertAt(price(date) * pos.quantity, hCurrency, displayCurrency, date)
```

- `costBasis` converts at the lot's **buy-date** FX rate — frozen, "what was
  actually paid" in display-currency terms. It only changes step-wise when a buy
  or sell happens, not with daily FX drift.
- `currentValue` converts at **that date's** FX rate, using `price(date)` from
  `effectiveHistories` via the existing `priceAt()` step-lookup — mirrors how the
  existing Total Return calculation treats the price side of unrealized P&L.
- `price(date)` requires a history lookup (`effectiveHistories`/`priceAt`), so if
  no history exists for a ticker at all (fetch failed, no manual price fallback),
  that lot is skipped for `currentValue` on dates where price is unavailable —
  same graceful-degradation behavior the Total Return view already has.
- `hCurrency` and `priceAt` are the existing `histCurrency()` and `priceAt()`
  helpers — unchanged, reused as-is.

`ChartPoint` gains two optional fields: `costBasis: number`, `currentValue: number`
(computed unconditionally alongside `pnl`; cheap enough not to gate behind the
active view).

### Rendering

**Total Return view** (existing, unchanged): single-line `AreaChart` on `pnl`,
green/red based on sign, `ReferenceLine` at 0.

**Portfolio Value view** (new): `LineChart` (via Recharts) with two `Line`s:

- Cost Basis — neutral slate (`#64748b`), matches no existing semantic color so it
  doesn't imply gain/loss on its own.
- Current Value — blue accent (`#3b82f6`).
- `<Legend/>` shown so the two lines are identifiable without hovering.
- Y-axis domain `[0, maxVal + pad]` — starts at 0 (unlike the Total Return chart,
  which centers around a 0 reference line) since these are absolute values and a
  non-zero baseline would visually exaggerate small dips.
- Tooltip shows both values, each formatted via the existing `fmtCurrency()`.
- Chart header `<h3>` text switches between "Portfolio Total Return" and
  "Portfolio Value" based on the active view; the subtitle line switches similarly
  (e.g. "capital in open positions vs. mark-to-market value").

### Edge cases

- No positions / no history data for the selected range → same existing "No data
  for this range" placeholder, shown for both views.
- `NO_FEED_TICKERS` / manual-priced funds → already produce a synthetic
  interpolated history via `effectiveHistories`; reused as-is, so Cost Basis /
  Current Value work for these tickers too.
- Weekend live-price injection → already handled by `effectiveHistories`; reused
  as-is (today's Current Value reflects the live quote on weekdays, yesterday's
  close on weekends).
- Fully-closed portfolio (all lots sold) → both lines are 0 for all dates after the
  last sell; this is correct per the "drops on sell" definition and matches how
  `isClosed` rows already show `currentValue: 0` in the table.

## Testing

There is no existing test coverage for chart components (`src/utils/money.test.ts`
covers `xirr`/`applyFifo`/`calcNetDividends` only — pure functions, not React
components). This feature will be verified manually: run `npm run dev`, open a
portfolio with a mix of open, closed, and manual-priced positions, toggle to
Portfolio Value, and confirm — Cost Basis steps down at each sell date, Current
Value tracks live/table values, and both charts share range-tab behavior
identically to the existing Total Return view.
