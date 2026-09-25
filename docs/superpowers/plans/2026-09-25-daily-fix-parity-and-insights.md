# Daily-change fix, parity, chart filter & insight features — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix Android's today's-change aggregate and sort, add a permanent web↔Android parity test, bring Android detail to web-column parity, add a chart type filter, the Czech 3-year tax view, a benchmark overlay, a dividend forecast, and an Android widget + price alerts.

**Architecture:** Every number is a pure function written twice (TS in `src/utils/`, Kotlin in `android/core/calc/`) and pinned by one shared JSON fixture under `test-fixtures/` that both test suites read. UI layers only call those functions. Android-only features (widget, alerts) live in new small files in `app` / `core:data` / `core:database`.

**Tech Stack:** React 18 + TS + Vite + vitest + recharts; Kotlin + Compose + Room 2.6.1 + WorkManager 2.9.0 + Hilt + Jetpack Glance; JUnit4.

**Spec:** `docs/superpowers/specs/2026-09-25-daily-fix-parity-and-insights-design.md`

## Global Constraints

- Never send `range=max` to Yahoo; use `yahooChartQuery` / `yahooDividendQuery` / `yahooFxHistoryQuery` (web) and `YahooWindow.kt` (Android).
- Web and Android calc must agree to 0.01 on every figure in `test-fixtures/parity/`; any intentional difference is written in that fixture's `README.md`.
- `FX_CONVERTED_TICKERS` on Android stays unencoded.
- Room: database version goes 3 → 4 with `MIGRATION_3_4`; never destructive migration.
- kotlinx.serialization request bodies: no defaulted fields that must reach the wire.
- Tax view is labelled "Estimate — not tax advice".
- Forecast and calendar are labelled "estimated".
- Alerts are device-local, not synced.
- Commit after every task; message style `fix(android): …` / `feat(web): …`, ending with the session's Co-Authored-By + Claude-Session lines.
- Web tests: `npm test`; type check `npm run build`. Android: `cd android && ./gradlew :core:calc:test` / `./gradlew test`.

---

## Phase 0 — Bug hunt

### Task 0.1: Extract web row derivation into a pure function

**Files:**
- Create: `src/utils/rowDerivation.ts`
- Modify: `src/components/PortfolioContent.tsx:97-228` (rows useMemo) and `:230-259` (portfolioIrr)
- Test: `src/utils/rowDerivation.test.ts`

**Interfaces — Produces:**
```ts
export interface DeriveRowInput {
  lots: Position[]
  quote?: Quote; loading: boolean; error: string | null
  manual?: { price: number; updatedAt?: string }
  dividends: DividendEvent[]
  taxOverrides: Record<string, number>
  today: string                       // YYYY-MM-DD, injected (not new Date()) for determinism
  convert: (a: number, from: string, to: string) => number
  anchor?: { price: number; lastTradedAt: number | null }
  anchorFx?: number | null
  displayCurrency: string
}
export function deriveRow(i: DeriveRowInput): PortfolioRow
export function computePortfolioIrr(positions: Position[], rows: PortfolioRow[], dividends: Map<string, DividendEvent[]>,
  taxOverrides: Record<string, number>, displayCurrency: string, today: string,
  convert: (a: number, from: string, to: string) => number): number | null
```
Mirrors Android `deriveRow` / `computePortfolioIrr` (`core/calc/RowDerivation.kt`) parameter-for-parameter.

- [ ] Move the body of the map callback verbatim into `deriveRow`; the only behavioural change is `today` injected (`new Date(today)` in the xirr terminal flow). `PortfolioContent` calls `deriveRow({... today: new Date().toISOString().slice(0,10) })`.
- [ ] Test: one open USD lot with quote → `currentValue`, `pnl`, `dailyChangeDisplay` equal hand-computed values; one closed lot → `isClosed`, `currentValue === 0`.
- [ ] `npm test && npm run build` pass. Commit `refactor(web): extract deriveRow into a pure util`.

### Task 0.2: Shared parity fixture + web golden

**Files:**
- Create: `test-fixtures/parity/input.json`, `test-fixtures/parity/expected.json`, `test-fixtures/parity/README.md`
- Create: `src/utils/parity.test.ts`

`input.json` shape (one frozen snapshot, edge cases from spec Phase 0.5):
```json
{
  "today": "2026-09-25",
  "displayCurrencies": ["CZK", "USD", "EUR"],
  "rates": { "CZK": 1, "USD": 23.1, "EUR": 24.3, "GBP": 28.9, "JPY": 0.155, "CHF": 26.1, "CAD": 16.9, "AUD": 15.2 },
  "positions": [ /* AAPL USD 2 lots; VIG.PR CZK partial sell; 8306.T EUR lots/JPY quote;
                    BP.L GBP quote; LU2606422355 manual fund; BTC-USD crypto; CLOSED.X fully sold;
                    ZERO.X quantity 0 lot; SELL0.X sellPrice 0 with sellDate */ ],
  "quotes": { "AAPL": {...}, "8306.T": {"currency": "JPY", ...}, ... },
  "anchors": { "AAPL": {"price": 250.1, "lastTradedAt": 1790000000} },
  "fxAnchors": { "USD": 23.0, "JPY": 0.154 },
  "manualPrices": { "LU2606422355": { "price": 112.4, "updatedAt": "2026-09-20" } },
  "dividends": { "AAPL": [{ "date": "2026-08-11", "amount": 0.26, "currency": "USD" }] },
  "taxOverrides": {},
  "sortOrders": ["VALUE", "TODAY", "TOTAL_RETURN", "NAME", "TYPE"]
}
```
`expected.json`: for each display currency → `rows[]` (ticker, currentValue, costBasis, pnl, dividendIncome, totalReturn, irr, dailyChangeDisplay, dailyChangePercent, dailyChangeMethod — row currency), `summary` (totalValue, totalDaily, totalDailyPct, totalReturn, portfolioIrr — display currency), `sorted` (ticker order per sort field, descending).

- [ ] `parity.test.ts` computes all of the above from `input.json` using `deriveRow`, `computePortfolioIrr`, a web `convert` built from `rates` (same CZK-base formula as `useFxRates.convert`), and a pure `sortRows(rows, field, asc, displayCurrency, convert)` extracted from `PortfolioTable.tsx:460-480` into `src/utils/rowSorting.ts`.
- [ ] Generate `expected.json` once with `UPDATE_PARITY=1 npm test -- parity`; the test writes the file when that env var is set, else asserts `toBeCloseTo(x, 2)` per figure.
- [ ] Inspect the generated file by hand for NaN/Infinity and wrong-looking values; each edge case either renders sanely or becomes a Phase 0 finding (fix + test first, regenerate).
- [ ] Commit `test: shared web/android parity fixture`.

### Task 0.3: Android side of the parity test

**Files:**
- Create: `android/core/calc/src/test/kotlin/com/stocktracker/core/calc/ParityTest.kt`
- Modify: `android/core/calc/build.gradle.kts` (add `testImplementation(libs.kotlinx.serialization.json)` + serialization plugin if missing)

- [ ] Read `../../test-fixtures/parity/input.json` relative to the android root (`File(System.getProperty("user.dir")).resolve("../../test-fixtures/parity")` — Gradle's test working dir is the module dir `android/core/calc`).
- [ ] Compute rows via `deriveRow`, IRR via `computePortfolioIrr`, summary via `portfolioDailyChange` (Task 1.1), sorts via `sortedForDisplay`; assert every figure within 0.01 of `expected.json`.
- [ ] Run `./gradlew :core:calc:test --tests '*ParityTest*'`. Expected first run: FAIL on summary/sort (the Phase 1 bugs) and possibly others. Record each mismatch as a finding in `test-fixtures/parity/README.md`; fix in Phase 1 or in a dedicated fix commit (test stays red until fixed — do not loosen tolerances).
- [ ] Commit `test(android): parity test against shared fixture`.

### Task 0.4: Static checks, runtime logs, silent-failure review

- [ ] `npm run build` → fix every tsc error. `cd android && ./gradlew lint` → fix Errors and correctness Warnings (skip pure style).
- [ ] `npm run dev`; MCP browser: load each portfolio, collect `console` errors/warnings + failed network requests. Each real error → systematic-debugging → failing test → fix.
- [ ] Emulator (`stocktracker_test` AVD): install debug APK, visit Insights / Portfolio / each detail / Settings / Import; `adb logcat --pid=$(adb shell pidof com.stocktracker.app)` → collect exceptions / ANRs.
- [ ] Review catch blocks in `src/hooks/useQuotes.ts`, `src/hooks/useDividends.ts`, `src/utils/storage.ts`, `android/core/data/.../sync/SyncCoordinator.kt`, `core/network/QuoteClient.kt`, `DividendClient.kt`: every swallowed error must at least log (`console.warn` / `Log.w`) or surface in UI state. Fix gaps.
- [ ] Extend `server/demo-data.json` with the same edge-case lots as `test-fixtures/parity/input.json` (closed, partial sell, GBp, JPY-quote/EUR-lot, manual fund, crypto, zero-qty, sellPrice 0); run `DATA_FILE=server/demo-data.json npm run dev` and confirm no NaN/crash in the table, charts and pies.
- [ ] Write findings table to `test-fixtures/parity/README.md` §Findings (id, symptom, cause, fix commit, status). Commit per fix.

---

## Phase 1 — Android today's change

### Task 1.1: `portfolioDailyChange` + summary fix

**Files:**
- Create: `android/core/calc/src/main/kotlin/com/stocktracker/core/calc/PortfolioSummary.kt`
- Modify: `android/feature/portfolio/.../PortfolioListScreen.kt:532-534`
- Test: `android/core/calc/src/test/kotlin/com/stocktracker/core/calc/PortfolioSummaryTest.kt`

**Produces:** `data class DailyTotal(val change: Double, val percent: Double)`; `fun portfolioDailyChange(rows: List<PortfolioRow>, totalValueDisplay: Double): DailyTotal`

```kotlin
/** Sums the anchored per-row figures, which are already in the display currency — mirrors PortfolioTable.tsx:500-502. */
fun portfolioDailyChange(rows: List<PortfolioRow>, totalValueDisplay: Double): DailyTotal {
    val change = rows.sumOf { it.dailyChangeDisplay }
    val prev = totalValueDisplay - change
    return DailyTotal(change, if (prev > 0) change / prev * 100 else 0.0)
}
```
- [ ] Test first: two rows with `dailyChange = -500.0` (legacy) and `dailyChangeDisplay = 10.0` each, total value 1020 → `DailyTotal(20.0, 2.0)`. Run → FAIL (unresolved). Implement → PASS.
- [ ] `SummaryHeader`: `val daily = portfolioDailyChange(rows, totalValue)`; use `daily.change` / `daily.percent`.
- [ ] Commit `fix(android): summary today's change uses the anchored figure`.

### Task 1.2: Sort by Today uses the anchored figure

**Files:** Modify `android/core/calc/.../RowSorting.kt:30`; Test `RowSortingTest.kt`

- [ ] Replace `today_alsoConvertsBeforeComparing` with:
```kotlin
@Test
fun today_sortsOnAnchoredDisplayFigure_notLegacyPrevClose() {
    val a = row(ticker = "AAA", dailyChange = 999.0).copy(dailyChangeDisplay = -5.0)
    val b = row(ticker = "BBB", dailyChange = -999.0).copy(dailyChangeDisplay = 5.0)
    val sorted = listOf(a, b).sortedForDisplay(SortOrder(SortField.TODAY, ascending = false), "CZK", rates)
    assertEquals(listOf("BBB", "AAA"), sorted.map { it.ticker })
}
```
- [ ] FAIL → change to `SortField.TODAY -> compareBy { it.dailyChangeDisplay }` (already display currency; no `money()`) → PASS.
- [ ] `grep -rn "\.dailyChange\b" android --include='*.kt' | grep -v /build/` shows only `RowDerivation.kt` (producer) and tests. Same grep on `src/` shows only `rowDerivation.ts` + types.
- [ ] Parity test (0.3) summary + sort cases now pass. Commit `fix(android): sort by Today uses the anchored figure`.

---

## Phase 2 — Android detail parity

### Task 2.1: Position facts grid

**Files:**
- Create: `android/core/calc/.../PositionFacts.kt`, test `PositionFactsTest.kt`
- Create: `android/feature/portfolio/.../PositionFactsSection.kt`
- Modify: `PortfolioListScreen.kt` detail screen, after the `DetailMetricGrid` block (~line 824)
- Test: `LayoutMatrixTest.kt` (add detail case), goldens re-recorded

**Produces:**
```kotlin
data class PositionFacts(
    val quantityHeld: Double, val quantityBought: Double, val quantitySold: Double,
    val avgBuyPrice: Double, val avgBuyCurrency: String, val firstBuyDate: String,
    val lots: Int, val brokers: List<String>, val isin: String?, val returnPercent: Double?,
)
fun positionFacts(row: PortfolioRow): PositionFacts
```
Rules: `quantityHeld` = Σ open lots (same `isOpenLot` rule as RowDerivation — make that function `internal` and reuse); `quantityBought` = Σ all lots; `quantitySold` = Σ closed lots; brokers = distinct non-blank in buy-date order; isin = first non-blank; `returnPercent = totalReturn / costBasis * 100` or null when costBasis ≤ 0.
- [ ] Tests: partial sell (3 bought, 1 sold → held 2, sold 1); two brokers deduped; no ISIN → null; zero cost → null returnPercent.
- [ ] `PositionFactsSection`: second `DetailMetricGrid` titled "Position" with cells Quantity (sub "bought X · sold Y" only when sold > 0), Avg buy (`formatMoney` + currency), First buy (`formatDisplayDate`), Lots, Type (badge text), Return % (`signedPercent`, pnl color); then full-width rows for Broker(s) and ISIN (monospace) when present.
- [ ] LayoutMatrixTest: detail screen at 320/360/411 × 1.0/1.3/2.0 has no clipped text. `recordRoborazziDebug` for the detail golden; eyeball the PNG.
- [ ] Commit `feat(android): position facts on detail screen`.

---

## Phase 3 — Chart type filter

### Task 3.1: Filter logic (both platforms)

**Files:** Create `src/utils/typeFilter.ts` + test; `android/core/calc/.../TypeFilter.kt` + test.

```ts
export type AssetType = Position['type']
/** Empty set = All. Drops types not present, so a stale stored filter can't hide everything. */
export function effectiveTypeFilter(selected: AssetType[], held: AssetType[]): Set<AssetType>
export function filterPositionsByType(positions: Position[], filter: Set<AssetType>): Position[]  // empty filter → positions
export function toggleType(selected: AssetType[], t: AssetType): AssetType[]  // add/remove; result empty = All
export function parseStoredTypes(raw: string | null): AssetType[]  // invalid JSON / unknown values → []
export function typeFilterLabel(filter: Set<AssetType>): string  // "" for All, else "Stock + ETF" in enum order
```
Kotlin mirrors: `effectiveTypeFilter(selected: Set<PositionType>, held: Set<PositionType>)`, `filterPositionsByType`, `toggleType`, `typeFilterLabel`. Same test table both sides: stale `["crypto"]` with held `{stock}` → All; toggle last off → All; label order stock, etf, fund, commodity, crypto.
- [ ] TDD both. Commit `feat: chart type filter logic`.

### Task 3.2: Web chart chips

**Files:** Modify `src/components/PortfolioPnLChart.tsx`, `src/App.css`
- [ ] State `types` from `parseStoredTypes(localStorage.getItem('chart_types_portfolio'))` (try/catch).
- [ ] `held = unique positions.map(p => p.type)`; `filter = effectiveTypeFilter(types, held)`; `chartPositions = filterPositionsByType(positions, filter)`.
- [ ] History fetch effect keeps using **all** `positions`/`tickers` (switching chips must not refetch). `effectiveHistories` and `chartData` use `chartPositions`.
- [ ] Chips row under the header: `All` + one per held type, classes `.chart-type-chips`, `.chart-type-chip`, `.active`; only render when `held.length > 1`. Subtitle appends `· {typeFilterLabel(filter)}` when non-empty.
- [ ] Playwright layout suite passes (`npm run test:ui`). Commit `feat(web): filter portfolio chart by asset type`.

### Task 3.3: Android chart chips

**Files:** Modify `PortfolioChartViewModel.kt`, `PortfolioChartUiState.kt`, `PortfolioPnlChartCard.kt`, `SettingsRepository.kt`
- [ ] `AppSettings.chartTypes: Set<PositionType>` stored as comma-joined names under key `chart_types`; unparseable names dropped. `setChartTypes(set)`.
- [ ] VM: history fetch still keyed on all positions. In `seriesState`, compute `held`, `filter = effectiveTypeFilter(settings.chartTypes, held)`, filtered positions passed to `buildEffectiveHistories` and `buildPortfolioChartData`. UiState gains `heldTypes: List<PositionType>`, `typeFilter: Set<PositionType>`. Action `ToggleType(type)` / `ClearTypes` → `settingsRepository.setChartTypes(toggleType(...))`.
- [ ] Card: `FilterChip` row (horizontally scrollable) when `heldTypes.size > 1`; subtitle suffix via `typeFilterLabel`.
- [ ] Unit test the VM filter wiring by testing the pure functions; layout test at 320 dp for chip row. Commit `feat(android): filter portfolio chart by asset type`.

---

## Phase 4 — Czech 3-year time test

### Task 4.1: Tax calc (both platforms)

**Files:** `src/utils/taxTest.ts` + test; `android/core/calc/.../TaxTest.kt` + test; shared table `test-fixtures/tax/cases.json`.

```ts
export function timeTestDate(buyDate: string): string           // buyDate + 3y + 1d; Feb 29 → Mar 1 + 1d rule: add years via Date.UTC, clamp
export function lotTaxStatus(lot: Position, today: string):
  { kind: 'exempt' | 'taxable' | 'pending'; freeFrom: string; daysLeft: number }
  // closed: exempt if sellDate >= freeFrom else taxable; open: pending w/ daysLeft (0 → 'exempt')
export interface YearSummary { year: number; proceedsTaxable: number; gainTaxable: number; gainExempt: number; underSmallProceedsLimit: boolean }
export function realizedByYear(positions: Position[], czkAt: (amount: number, currency: string, date: string) => number): YearSummary[]
  // per closed lot: proceeds = czkAt(sellPrice*qty, cur, sellDate); cost = czkAt(buyPrice*qty, cur, buyDate)
  // underSmallProceedsLimit = proceedsTaxable <= 100_000; sorted by year desc
```
Cases (shared JSON, both suites iterate it): buy 2023-01-15 → free 2026-01-16; buy 2024-02-29 → free 2027-03-01; sell on free date → exempt; sell day before → taxable; a year with 99 999 taxable proceeds → flagged; FX: USD lot bought at 20, sold at 25 CZK/USD with same USD price → gain = qty·price·5 CZK.
- [ ] TDD both. Commit `feat: Czech 3-year time test calc`.

### Task 4.2: Tax UI

- Web: `src/components/TaxPanel.tsx` (collapsible, below pie charts in `PortfolioContent`), uses `czkAt` built from `PortfolioPnLChart`'s FX history — **extract** `fetchFxHistory` + `fxHistCache` + `convertAt` from `PortfolioPnLChart.tsx` into `src/utils/fxHistory.ts` (pure move, chart imports it). Badges in `PortfolioTable` lot mini-table (`.tax-badge.exempt|.pending|.taxable`).
- Android: `TaxCard.kt` on Insights; badge in `LotListSection`. FX via `HistoryClient.fetchFxHistory` (already cached) + `priceAt`.
- [ ] Footer text: "Estimate — not tax advice. Gains converted to CZK at buy- and sell-date rates."
- [ ] Layout tests both. Commit per platform.

---

## Phase 5 — Benchmark overlay

### Task 5.1: Benchmark math (both platforms)

**Files:** `src/utils/benchmark.ts` + test; `android/core/calc/.../Benchmark.kt` + test.

```ts
export const BENCHMARKS = { msci: { ticker: 'URTH', label: 'MSCI World' }, sp500: { ticker: '^GSPC', label: 'S&P 500' } } as const
/** Cash-flow-matched benchmark return per chart date, display currency. */
export function benchmarkSeries(
  positions: Position[], dates: string[], bench: TickerHistory, benchCurrency: string, displayCurrency: string,
  convertAt: (amount: number, from: string, to: string, date: string) => number,
): { date: string; value: number }[]
// buy lot: units += convertAt(buyPrice*qty, lot.cur, benchCur, buyDate) / priceAt(bench, buyDate)
// sell lot: units -= convertAt(sellPrice*qty, lot.cur, benchCur, sellDate) / priceAt(bench, sellDate)
// invested(d) = Σ convertAt(cost, lot.cur, display, buyDate) − Σ convertAt(proceeds, lot.cur, display, sellDate) for events ≤ d
// value(d) = convertAt(units(d) * priceAt(bench, d), benchCur, display, d) − invested(d)
```
Test: a single lot whose own history *is* the benchmark (same series, same currency) → `benchmarkSeries` equals the chart's price-P&L line at every date; empty history → `[]`.
- [ ] TDD both. Commit `feat: cash-flow-matched benchmark math`.

### Task 5.2: Benchmark UI

- Web: header select `Benchmark: None | MSCI World | S&P 500` (`localStorage['chart_benchmark_portfolio']`), fetch via `fetchYahooHistory(ticker, yahooRange)`; second `<Line>` dashed on the Total Return view only, tooltip row "MSCI World (excl. dividends)". Uses `chartPositions` (respects type filter).
- Android: dropdown in `PortfolioPnlChartCard`; VM fetches benchmark history with the existing `HistoryClient.fetchHistory`, stores in `HistoryFetchState.benchmark`; `AreaLineChart` gets optional overlay series (add `overlay: List<Double>?` param drawn as dashed path).
- [ ] Layout tests; commit per platform.

---

## Phase 6 — Dividend forecast

### Task 6.1: Forecast math (both platforms)

**Files:** `src/utils/dividendForecast.ts` + test; `android/core/calc/.../DividendForecast.kt` + test; shared `test-fixtures/forecast/cases.json`.

```ts
export interface ForecastEntry { ticker: string; date: string; net: number; currency: string }
export function dividendForecast(rows: PortfolioRow[], dividends: Map<string, DividendEvent[]>,
  taxOverrides: Record<string, number>, today: string):
  { next12m: ForecastEntry[] }
// for each open row: events with date in (today−365d, today]; each → entry dated date+1y (if > today),
// net = openQty * amount * (1 − getDividendTaxRate(ticker)); currency = event.currency; sorted by date
```
Display layer sums `convert(net, currency, displayCurrency)`. Cases: quarterly payer → 4 entries; closed row → none; payer whose last event is 14 months old → none.
- [ ] TDD both. Commit `feat: dividend forecast calc`.

### Task 6.2: Forecast UI
- Web: extra summary card "Est. dividends 12 m" in `PortfolioTable` summary grid + `DividendCalendar.tsx` panel (next 12 entries, "estimated" label) below the chart.
- Android: `DividendForecastCard.kt` on Insights (total + next 6 entries).
- [ ] Layout tests; commit per platform.

---

## Phase 7 — Android widget + alerts

### Task 7.1: Price-alert storage (Room v4)

**Files:** `core/database/.../PriceAlertEntity.kt`, `PriceAlertDao.kt`, modify `StockTrackerDatabase.kt` (version 4, entities, `MIGRATION_3_4`), migration test in `core/database/src/test` (or androidTest per existing pattern).

```kotlin
@Entity(tableName = "price_alerts")
data class PriceAlertEntity(
    @PrimaryKey val id: String, val ticker: String, val above: Boolean, val threshold: Double,
    val currency: String, val enabled: Boolean = true, val armed: Boolean = true, val lastTriggeredAt: Long? = null,
)
val MIGRATION_3_4 = object : Migration(3, 4) { override fun migrate(db: SupportSQLiteDatabase) {
    db.execSQL("CREATE TABLE IF NOT EXISTS price_alerts (id TEXT NOT NULL PRIMARY KEY, ticker TEXT NOT NULL, above INTEGER NOT NULL, threshold REAL NOT NULL, currency TEXT NOT NULL, enabled INTEGER NOT NULL, armed INTEGER NOT NULL, lastTriggeredAt INTEGER)")
} }
```
- [ ] Migration test v3→v4 keeps existing rows. Commit.

### Task 7.2: Crossing logic + worker

**Files:** `core/calc/.../AlertCrossing.kt` + test; `core/data/.../PriceAlertRepository.kt`; `app/.../PriceAlertWorker.kt`; notification channel in `StockTrackerApp`.

```kotlin
/** Returns the new armed state and whether to notify. Fires once per crossing; re-arms when price returns across. */
fun evaluateAlert(above: Boolean, threshold: Double, armed: Boolean, price: Double): Pair<Boolean, Boolean> {
    val crossed = if (above) price >= threshold else price <= threshold
    return when {
        armed && crossed -> false to true
        !armed && !crossed -> true to false
        else -> armed to false
    }
}
```
- [ ] Tests: armed+cross → notify, disarm; disarmed+still crossed → silent; disarmed+back → re-arm.
- [ ] Worker: periodic 15 min, `NetworkType.CONNECTED`, enqueued unique (`KEEP`) when ≥1 enabled alert exists, cancelled when none. Fetch via `QuoteClient`, convert quote into alert currency with `convert`, update rows, post notification (tap → detail deep link).
- [ ] `POST_NOTIFICATIONS` in manifest; runtime request when creating first alert (API 33+).
- [ ] Detail screen "Alerts" section: list + add dialog (above/below, threshold prefilled with current price) + delete.
- [ ] Commit.

### Task 7.3: Glance widget

**Files:** `gradle/libs.versions.toml` (+ `glance = "1.1.1"`, `androidx-glance-appwidget`), `app/build.gradle.kts`, `app/.../widget/PortfolioWidget.kt`, `PortfolioWidgetReceiver.kt`, `res/xml/portfolio_widget_info.xml`, manifest receiver.
- [ ] Widget content from Room + `deriveRow`/`portfolioDailyChange` for the active portfolio in settings currency: name, value, today's change (colored), "updated HH:mm". Reads quotes cached by the last refresh (store last quote snapshot in `QuoteRepository` → Room if not already persisted; if only in-memory, persist a `widget_snapshot` DataStore entry written after each list refresh instead — pick whichever exists, no new network in the widget).
- [ ] Refresh: `PortfolioWidget().updateAll(context)` after each list refresh and from a 30-min periodic worker.
- [ ] Emulator: add widget to home screen, screenshot. Commit.

---

## Finish

- [ ] Re-run Phase 0 checks (parity, tsc, lint, logs).
- [ ] Update `CLAUDE.md` (new utils, Room v4, parity fixture, widget/alerts) — normal prose.
- [ ] Bump Android version (`android-release` skill), release APK; deploy web (`deploy` skill).
- [ ] Final report: findings table + feature list.
