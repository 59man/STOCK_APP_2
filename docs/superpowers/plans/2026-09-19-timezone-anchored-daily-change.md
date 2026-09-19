# Time-Zone-Anchored Today's Change Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make "today's change" mean the move since midnight in the user's own time zone, in both apps, including the currency component, with the broker-style price-only figure kept visible alongside.

**Architecture:** One pure function, written twice (TypeScript and Kotlin) against an identical test table so the apps cannot drift. Anchor resolution is a separate, cacheable concern: it runs at most once per ticker per local day. Everything falls back to today's previous-close behaviour, marked in the UI, when an anchor cannot be resolved.

**Tech Stack:** TypeScript/React/Vitest/Playwright on the web; Kotlin/Compose/Room/Robolectric on Android.

**Spec:** `docs/superpowers/specs/2026-09-19-timezone-anchored-daily-change-design.md`

## Global Constraints

- The calculation is a pure function in `src/utils/dailyChange.ts` and `core/calc/DailyChange.kt`, ported line-for-line, sharing a test table. No fetching, no React, no Android imports.
- **Weekend and pre-open report exactly `0.00`.** The last-session fallback was considered and rejected — see the spec. The only exception is the Step-4 fallback below, which is marked in the UI.
- The anchor is constant for a local day, so it is fetched at most once per ticker per day and cached.
- The headline figure **includes** currency movement; the price-only figure appears as a labelled sub-line whenever they differ.
- Android: layout tests need `@GraphicsMode(GraphicsMode.Mode.NATIVE)`.
- Commit after every task.

---

### Task 1: The shared calculation (web)

**Files:**
- Create: `src/utils/dailyChange.ts`
- Test: `src/utils/dailyChange.test.ts`

**Interfaces:**

```ts
export type AnchorMethod = 'anchored' | 'noTradeToday' | 'prevCloseFallback'

export interface DailyChangeInput {
  quantity: number
  currentPrice: number          // native currency
  anchorPrice: number | null    // native currency, null when unresolved
  currentFx: number             // native → display
  anchorFx: number | null       // native → display at local midnight
  prevClose?: number | null     // native, only used by the fallback
}

export interface DailyChangeResult {
  change: number                // display currency, includes FX movement
  changePercent: number
  priceOnlyChange: number       // display currency, FX held at today's rate
  method: AnchorMethod
}

export function dailyChange(input: DailyChangeInput): DailyChangeResult
```

- [ ] **Step 1: Write the failing test** covering, with exact expected numbers:
  - no trade since local midnight (`anchorPrice === currentPrice`) → `change === 0`, `method === 'noTradeToday'`
  - ordinary move, display currency equals native (`currentFx === anchorFx === 1`) → headline equals price-only
  - price flat, FX moved → headline non-zero, price-only exactly `0`
  - price and FX moving in opposite directions → headline smaller than price-only, signs as expected
  - `anchorPrice === null` with a `prevClose` → falls back, `method === 'prevCloseFallback'`
  - `anchorPrice === null` and no `prevClose` → `change === 0`, method `prevCloseFallback`
  - `anchorPrice === 0` → percent is `0`, not `Infinity`
  - quantity `0` (fully closed) → all zeros

- [ ] **Step 2: Run** `npm test -- dailyChange` and confirm it fails.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npm test` — the whole suite, not just the new file.
- [ ] **Step 5: Commit** — `feat(web): midnight-anchored daily change calculation`

---

### Task 2: Anchor resolution (web)

**Files:**
- Create: `src/hooks/useDailyAnchors.ts`
- Test: `src/utils/anchorResolution.test.ts` (pure part extracted into `src/utils/anchorResolution.ts`)

**Interfaces:**
- `resolveAnchorFromDaily(bars, localMidnightEpoch, sessionEndEpoch): number | null`
- `resolveAnchorFromIntraday(bars, localMidnightEpoch): number | null`
- `needsIntraday(periods, localMidnightEpoch): boolean`
- Hook: `useDailyAnchors(tickers, zone, displayCurrency)` → `{ anchorFor(ticker), fxAnchorFor(from, to), loading }`

The three-step resolution, from the spec:

1. `meta.regularMarketTime <= localMidnight` → nothing traded today → anchor is the current price. **No fetch at all.** This is the common case: every weekend, and every weekday before the open.
2. Otherwise, if no session straddles local midnight, fetch `range=5d&interval=1d` and take the close of the last session that *ended* at or before local midnight.
3. If a session was in progress at local midnight (always true for crypto, which trades continuously), fetch `range=2d&interval=5m` and take the last bar at or before it.

Cache key is `TICKER::YYYY-MM-DD` in the user's zone, in a module-level map like `useQuotes`'s. Measured payloads for step 3: 157 bars (AAPL), 156 (8306.T), 409 (BTC-USD).

- [ ] **Step 1: Write the failing tests** for the three pure functions: a daily-bar case; an intraday straddling case; a weekend case (nothing traded → step 1 short-circuits); a fetch returning no usable bars → null.
- [ ] **Step 2: Implement the pure functions.**
- [ ] **Step 3: Implement the hook** with its per-local-day cache.
- [ ] **Step 4: Run** `npm test`
- [ ] **Step 5: Commit** — `feat(web): resolve and cache the midnight price anchor`

---

### Task 3: Web time zone setting and wiring

**Files:**
- Modify: `src/App.tsx` (zone state + picker), `src/components/PortfolioContent.tsx`, `src/components/PortfolioTable.tsx`, `src/App.css`

The setting is web-only and per-browser, defaulting to `Intl.DateTimeFormat().resolvedOptions().timeZone`, stored in `localStorage` under `stock_tracker_timezone`. Not synced to Android, by design.

`PortfolioRow` gains `anchorPrice`, `anchorMethod` and `lastTradedAt`; `dailyChange` keeps its name and meaning as the headline figure so existing consumers do not change shape.

The today cell renders, in order: the headline value, the percentage, the price-only sub-line when it differs, and a muted hint — `closed · last traded Fri` when nothing traded today, `prev. close` when the Step-4 fallback produced the number.

- [ ] **Step 1:** Add the setting and the picker.
- [ ] **Step 2:** Thread anchors through `PortfolioContent`'s rows `useMemo`.
- [ ] **Step 3:** Render the cell.
- [ ] **Step 4: Run** `npm test && npm run build`
- [ ] **Step 5:** Open the app and confirm a weekend shows `0.00` with the hint, not Friday's move.
- [ ] **Step 6: Commit** — `feat(web): today's change anchored to the user's midnight`

---

### Task 4: The shared calculation (Android)

**Files:**
- Create: `core/calc/src/main/kotlin/com/stocktracker/core/calc/DailyChange.kt`
- Test: `core/calc/src/test/kotlin/com/stocktracker/core/calc/DailyChangeTest.kt`

A line-for-line port of Task 1 with **the same test table and the same expected numbers**. This is the mechanism that stops the two apps drifting, so the cases must match exactly rather than merely covering the same ground.

- [ ] **Step 1: Write the failing test** — the same eight cases.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Run** `./gradlew :core:calc:test`
- [ ] **Step 4: Commit** — `feat(android): midnight-anchored daily change calculation`

---

### Task 5: Anchor resolution and cache (Android)

**Files:**
- Create: `core/network/src/main/kotlin/com/stocktracker/core/network/AnchorClient.kt`
- Create: `core/database/.../DailyAnchorEntity.kt`, bump the database to **version 3** with `MIGRATION_2_3`
- Create: `core/data/src/main/kotlin/com/stocktracker/core/data/DailyAnchorRepository.kt`

Room rather than memory so a cold start in the morning does not refetch every anchor. Keyed by ticker and local date; prune anything older than yesterday.

- [ ] **Step 1: Write the failing tests** — parsing (MockWebServer, wire bytes per the `DeviceApiTest` precedent), the migration (rows survive), and the repository's per-day caching.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Run** `./gradlew test`
- [ ] **Step 4: Commit** — `feat(android): fetch and cache the midnight price anchor`

---

### Task 6: Android wiring

**Files:**
- Modify: `core/calc/RowDerivation.kt`, `feature/portfolio/PortfolioListScreen.kt`, `PortfolioListViewModel.kt`, `InsightsScreen.kt`
- Test: extend `PositionCardTest`, `LayoutMatrixTest`, `GoldenScreenshotTest`

`RowDerivation` takes anchors as a parameter and produces the anchored `dailyChange` plus the new fields. The card and the Insights summary render the sub-line and hints.

- [ ] **Step 1: Write the failing tests** — a card with a foreign-currency holding shows both figures; a closed market shows `0.00` with the hint; a fallback row is marked.
- [ ] **Step 2: Implement.**
- [ ] **Step 3: Run** `./gradlew test`, record goldens, review them.
- [ ] **Step 4: Verify on the emulator** with the device clock set either side of local midnight, confirming the figure resets.
- [ ] **Step 5: Commit** — `feat(android): today's change anchored to the user's midnight`

---

### Task 7: Playwright layout checks (web)

**Files:**
- Modify: `package.json`, create `playwright.config.ts`, `tests/ui/layout.spec.ts`
- Modify: `server/index.js` — the `DATA_FILE` env override (also used by the README screenshots)

```js
const DATA_FILE = process.env.DATA_FILE ?? join(__dirname, 'data.json')
```

with `DATA_BAK`, the backups directory and the dated-backup path derived from it.

Viewports 360, 390, 640, 960, 1440 px against a demo dataset, asserting no horizontal page overflow (`documentElement.scrollWidth <= clientWidth`), no clipped cells (`scrollWidth <= clientWidth` per cell/badge/chip), the today cell rendering all its lines at 360 px, and modals fitting at 360 px.

- [ ] **Step 1:** Add the `DATA_FILE` override and a demo dataset generator.
- [ ] **Step 2:** Add Playwright and the config.
- [ ] **Step 3:** Write the spec.
- [ ] **Step 4: Run** `npm run test:ui`. A failure here is a real finding — fix the layout.
- [ ] **Step 5: Commit** — `test(web): Playwright layout checks across five viewports`

---

### Task 8: Documentation

- [ ] **Step 1:** Record in `CLAUDE.md`: the anchored definition and that it deliberately reads zero on a closed market, the three-step resolution with its per-day cache, the FX component and the price-only sub-line, and the `DATA_FILE` override.
- [ ] **Step 2: Commit** — `docs: record the anchored today's change`

---

## Self-Review

**Spec coverage:** definition → Tasks 1 and 4; anchor resolution → 2 and 5; presentation → 3 and 6; web time zone → 3; layout robustness → 7; documentation → 8.

**Type consistency:** `DailyChangeInput`/`DailyChangeResult`/`AnchorMethod` are defined in Task 1 and ported unchanged in Task 4; the resolution helpers from Task 2 are mirrored by Task 5's client.

**Known unknowns:** whether Yahoo continues to serve `range=2d&interval=5m` unauthenticated (measured working 2026-09-19; the Step-4 fallback covers its loss), and whether any held instrument has a session straddling Central European midnight other than crypto (Step 3 handles it generically either way).
