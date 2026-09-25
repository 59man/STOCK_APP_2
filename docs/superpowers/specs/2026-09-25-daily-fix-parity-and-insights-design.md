# Daily-change fix, detail parity, chart type filter, and insight features

Date: 2026-09-25
Scope: web + Android. Delivered in phases; each phase is independently shippable.

## Phase 1 — Android bugs

### 1a. Summary "Today's change" disagrees with web

Observed at 00:39 Europe/Prague, same moment: web `+CZK 368.99 (+0.10%)`, Android `-3,113.49 (-0.9%)`.

Root cause: `SummaryHeader` (`feature/portfolio/.../PortfolioListScreen.kt:532`) sums the legacy
`PortfolioRow.dailyChange` (quote.change × qty, previous-close based, row currency). The web
summary (`PortfolioTable.tsx:500`) sums `dailyChangeDisplay` (midnight-anchored, already in the
display currency). Rows on both platforms already show the anchored figure; only the Android
aggregate uses the old one.

Fix: `totalDailyChange = rows.sumOf { it.dailyChangeDisplay }` — no `dc()` conversion, the value is
already in the display currency. Percent stays `total / (totalValue − total)` as on web.
Extract the aggregation into a pure `core:calc` function (`portfolioDailyChange(rows)`) so it is
unit-tested rather than living in a composable.

### 1b. Sort by "Today" looks random

Same root cause: `RowSorting.kt:30` compares `money(it.dailyChange, it.currency)` while the row
displays `dailyChangeDisplay`. Fix: compare `it.dailyChangeDisplay` directly (display currency
already). Add a `RowSortingTest` case where the legacy and anchored figures order rows oppositely.

Decision: keep the legacy `dailyChange` field (still the fallback input), but after this change no
UI or sort path reads it on either platform. Grep-verify.

## Phase 2 — Android detail-screen parity with web columns

Web table columns not on the Android position detail screen, added to its `DetailMetricGrid`
(or a second compact "Position" grid under it, to keep the money grid readable):

| Field | Source | Notes |
|---|---|---|
| Quantity held | `totalQuantity` (open) | `formatQty` |
| Bought / sold qty | Σ all lots / Σ closed lots | only when any lot is sold |
| Avg buy price | `avgBuyPrice` | native lot currency, like the web cell |
| First buy | `firstBuyDate` | `formatDisplayDate` |
| Lots | `lots` | |
| Broker(s) | distinct `positions.broker` | comma-joined, omitted if none |
| Type | `type` | badge |
| ISIN | first non-null `isin` | monospace, omitted if none |
| Return % | `totalReturn / costBasis` | already computed for total-return sub; also shown standalone |

Layout covered by `LayoutMatrixTest` (320/360/411 dp × 1.0/1.3/2.0) and a refreshed golden.

## Phase 3 — Chart type filter (web + Android)

Multi-select chips above the Total Return / Portfolio Value chart: `All` + one chip per asset type
the portfolio actually holds (stock, etf, fund, commodity, crypto). Toggling chips selects a set;
`All` clears the set (= no filter); deselecting the last chip returns to `All`.

- Filter applies **to the chart only**; summary cards stay whole-portfolio.
- Implementation filters the input positions/rows *before* chart math, so `buildPortfolioChartData`
  / `PortfolioPnLChart` math is untouched.
  - Web: `PortfolioContent` passes filtered `rows` to `PortfolioPnLChart`; state in
    `localStorage['chart_types_portfolio']` (JSON array; invalid → All).
  - Android: `PortfolioChartViewModel` gets a `typeFilter: MutableStateFlow<Set<AssetType>>`,
    positions filtered before history fetch/series build; persisted in the settings DataStore.
- A stored filter naming a type no longer held is ignored (treated as not selected).
- Chart subtitle names the active filter ("Stock + ETF") so a filtered chart is never mistaken for
  the whole portfolio.

## Phase 4 — Czech 3-year time test + yearly realized gains (web + Android)

Czech income tax exempts gains on securities held > 3 years (časový test). Pure calc in
`src/utils/taxTest.ts` / `core/calc/TaxTest.kt`, shared test table:

- `timeTestDate(buyDate) = buyDate + 3 years + 1 day`
- Per open lot: badge `tax-free` (date passed) or `tax-free in N d` / date. Shown in the lot
  mini-table (web) and `LotListSection` (Android).
- Per closed lot: `exempt` if `sellDate >= timeTestDate`, else `taxable`.
- **Yearly realized summary** (new collapsible panel on web below pie charts; new Insights card on
  Android): per calendar year of `sellDate` → gross sale proceeds, realized gain taxable, realized
  gain exempt. All in CZK, converted at the buy-date and sell-date FX rates (existing FX-history
  infrastructure: `convertAt` web / `ChartMath` Android). Also flag years whose total gross
  proceeds from *taxable* sales are ≤ 100 000 CZK (the annual small-proceeds exemption).
- Labelled "Estimate — not tax advice". Dividends are out of scope for this panel.

## Phase 5 — Benchmark overlay on the Total Return chart (web + Android)

Toggle `Benchmark: none | MSCI World | S&P 500` in the chart header. Tickers: `URTH` (iShares
MSCI World, USD) and `^GSPC`. Persisted like the range selection.

Method — **cash-flow-matched**, not a normalised index line (a plain % line is meaningless against
a portfolio built up over time):
- Every buy of `X` display-currency on date `d` buys `X / benchmark(d)` benchmark units
  (benchmark price converted to display currency at date `d`).
- Every sell withdraws the same display-currency amount of benchmark units at the sell date.
- Benchmark return line = benchmark holdings value − net invested, per chart date.
- Dividends: benchmark uses a price series (no reinvested dividends); portfolio dividends stay in
  the portfolio line. Documented in a tooltip ("benchmark excludes dividends").
- Respects the type filter (benchmark mirrors only the filtered cash flows).
- Pure function in `chartMath` on both platforms, tested with a fixture where the portfolio *is*
  the benchmark (lines must coincide).
- History fetched via the existing `yahooChartQuery` builders — never `range=max`.

## Phase 6 — Dividend forecast (web + Android)

- **Next 12 months net income**: per open position, trailing-12-month dividend events per share ×
  current open qty × (1 − withholding rate, honouring `taxOverrides` pattern via
  `getDividendTaxRate`), converted to display currency. Tickers with fewer than one event in the
  trailing year contribute 0.
- **Upcoming calendar**: last year's ex-dates shifted +1 year, only those in the future, sorted;
  each entry shows ticker, estimated date, estimated net amount. Labelled "estimated".
- Web: card in the summary area ("Est. dividends 12 m") + a small calendar panel. Android: a card
  on the Insights tab.
- Pure calc `dividendForecast.ts` / `DividendForecast.kt`, shared test table.

## Phase 7 — Android widget + price alerts (Android only)

### Home-screen widget
Jetpack Glance widget: portfolio name, total value, today's change (anchored, same
`portfolioDailyChange` as Phase 1), last-updated time. Reads Room only (no network in the widget);
refreshed by the existing sync/refresh cadence plus a periodic WorkManager job (≥ 15 min, OS
minimum). Tap opens the app. Shows the active portfolio in the settings display currency (no separate
configuration screen — YAGNI; add one only if asked).

### Price alerts
- New Room table `price_alerts` (id, ticker, direction above/below, threshold, currency,
  enabled, lastTriggeredAt) — **database version 4**, `MIGRATION_3_4`, migration test.
- Device-local, not synced (the server has no alert concept; keeps the persist schema unchanged).
- UI: "Alerts" section on the position detail screen: add/remove, list.
- `PriceAlertWorker` (periodic, 15 min, network-constrained) fetches quotes via `QuoteClient`
  and fires a notification on crossing; one trigger per crossing (re-arms after price returns
  across the threshold) so it does not spam every 15 minutes.
- Requests `POST_NOTIFICATIONS` at the moment the first alert is created (Android 13+).

## Phase 0 — General bug hunt (runs first, findings re-run after Phase 7)

The Phase 1 bug was a web/Android divergence nobody noticed until two screenshots were compared.
This phase hunts for more of that class systematically, plus ordinary errors.

1. **Web ↔ Android numeric parity harness.** A script (`scripts/parity-check`) feeds the same
   positions, quotes, FX rates, dividends and anchors (a frozen fixture captured from the demo
   dataset) through the web calc (`vitest`) and Android `core:calc` (JVM test), then diffs every
   per-row and portfolio figure: value, cost basis, P&L, dividends, total return, IRR, today's
   change, sort orders. Any mismatch > 0.01 is a bug to fix or a documented intentional
   difference. Kept as a permanent test so future ports can't drift.
2. **Static checks.** `npm run build` (tsc), `./gradlew lint` for Android; fix errors and real
   warnings (not style nits).
3. **Runtime logs.** Web: load every portfolio in the MCP browser, collect console errors/warnings
   and failed network requests. Android: emulator run through every screen, `adb logcat` filtered
   to the app for exceptions, ANRs, StrictMode hits.
4. **Silent-failure review.** Review catch blocks and fallbacks in fetch/sync code (`useQuotes`,
   `useDividends`, `storage.ts`, `SyncCoordinator`, `QuoteClient`, `DividendClient`) for errors
   swallowed without a user-visible or logged signal.
5. **Edge-case data.** Demo dataset extended with: fully closed ticker, partial sell, GBp quote,
   JPY quote with EUR lots, manual-priced fund, crypto, zero-quantity lot; both apps must render
   without crash or NaN.

Every finding: reproduce → failing test → fix (systematic-debugging). A findings list with
status goes in the final report.

## Testing summary

- Web: vitest for every new pure util (`taxTest`, `dividendForecast`, benchmark math, chart type
  filter parse). Playwright layout suite still passes at all widths.
- Android: `core:calc` unit tests mirroring web tables; `RowSortingTest`; Room migration test;
  LayoutMatrix + Roborazzi goldens for changed screens; emulator smoke run of widget + alert.
- Release: Android version bump + APK release; web deploy via the deploy skill.

## Out of scope

Dividend reinvestment in benchmark, tax on dividends, server-synced alerts, exchange holiday
calendars for the dividend calendar.
