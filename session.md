# Session Notes — 2026-05-14

## Session 1 — Persistent storage

### What was built

Portfolio data no longer lives exclusively in the browser. A lightweight Express server now persists everything to `server/data.json` on disk, so data survives browser clears, profile switches, and localhost restarts.

### New files

**`server/index.js`** — Express server on port 3001. Two endpoints:
- `GET /api/persist/:key` — reads `server/data.json`, returns `{ value: "..." | null }`
- `POST /api/persist/:key` — merges `{ value: "..." }` into `server/data.json`

**`src/utils/storage.ts`** — Async `getItem` / `setItem` utility. Calls the persist server first; falls back to `localStorage` silently if unreachable.

**`.gitignore`** — excludes `node_modules/`, `dist/`, `server/data.json`.

### Modified files

**`package.json`** — added `express`, `concurrently`; `dev` script runs both Vite and the persist server together.

**`vite.config.ts`** — added `/api/persist` proxy rule targeting `http://localhost:3001`.

**`src/hooks/usePortfolio.ts`** and **`useManualPrices.ts`** — two-phase init: sync from localStorage (instant), then async from server (server wins if it has data). Save effect runs only after init completes.

---

## Session 2 — Bug fixes, features, Docker, and documentation

### Bug fixes

**`calcNetDividends` sold-share inflation** (`src/utils/dividends.ts`)  
The function was counting dividends for shares that had already been sold. Added `sellDate` to the lot type and filtered out lots sold before each ex-dividend date — matching the logic already used in the IRR and portfolio-level calculations.

**Manual price silent failure** (`src/components/PortfolioTable.tsx`)  
Entering invalid text in the manual price field previously did nothing. Now shows a red inline error message ("Enter a valid positive number") and highlights the input border in red. The error clears on the next keystroke.

### New features

**Per-country dividend withholding tax** (`src/utils/dividends.ts`)  
Replaced the flat `DIVIDEND_TAX_RATE = 0.15` with a two-level lookup:
- `COUNTRY_WITHHOLDING_RATES` — 21-country table (CZ 15 %, AT 27.5 %, IE 0 %, LU 0 %, DE 26.375 %, FR 12.8 %, etc.)
- `TICKER_COUNTRY` — maps display ticker → ISO country code (`VIG.PR → AT`, `EXUS.DE → IE`, `4GLD.DE → DE`)
- `getDividendTaxRate(ticker)` — exported helper used by all dividend and IRR calculations

All three dividend functions (`calcNetDividends`, `cumNetDividendsAt`) and both IRR calculations (per-ticker and portfolio-level) now use per-country rates. To add a new foreign ticker: one line in `TICKER_COUNTRY`.

**Delete confirmation** (`src/components/PortfolioTable.tsx`)  
Clicking ✕ on any row or individual lot now opens a confirmation modal ("Remove X? This cannot be undone.") with Cancel / Remove buttons. Nothing is deleted until confirmed.

**Chart range persistence** (`src/components/PriceChart.tsx`, `PortfolioPnLChart.tsx`)  
Selected range is saved to `localStorage` (`chart_range_price` / `chart_range_portfolio`) and restored on next load. Both charts now use a `handleRangeChange` handler instead of calling `setRange` directly.

**JSON export** (`src/components/PortfolioTable.tsx`)  
Added an "↓ Export" button to the toolbar. Downloads all positions as a dated JSON file (e.g. `portfolio_2026-05-14.json`) via a temporary object URL.

**`.btn-danger` style** (`src/App.css`)  
Red-tinted button style used by the delete confirmation modal's Remove button.

### Docker

**New files:**
- `Dockerfile` — multi-stage build: Node 20 Alpine builder compiles the frontend; final stage copies only `dist/`, `server/`, and prod dependencies. Image is ~50 MB.
- `docker-compose.yml` — binds host port → container 8080; bind-mounts `server/data.json` so portfolio data persists between restarts.
- `.dockerignore` — excludes `node_modules/`, `dist/`, `.git/`, `Transactions_folder/`, `session.md`, `server/data.json` (data is provided via bind-mount, not baked into the image).

**`server/index.js`** — extended for production mode (`NODE_ENV=production`):
- Yahoo Finance proxy: forwards `/api/yahoo/*` to `https://query1.finance.yahoo.com` with browser-like headers (replicates the Vite dev proxy)
- Static file serving: serves `dist/` via `express.static`
- SPA fallback: all unmatched GET routes return `dist/index.html`
- Port is configurable via `PORT` env var (default 3001 in dev, 8080 in Docker)

**`package.json`** — added `"start": "node server/index.js"` script.

**Image pushed to Docker Hub:** `docker.io/59man/stock-tracker:latest`

### Deploy to server via SSH (summary)

```bash
# 1. On server — create data dir
mkdir -p /DATA/stock-tracker

# 2. From local machine — copy portfolio data
scp server/data.json user@server:/DATA/stock-tracker/data.json

# 3. On server — pull and run (use absolute path for the volume)
docker pull 59man/stock-tracker:latest
docker run -d --name stock-tracker -p 4000:8080 \
  -v /DATA/stock-tracker/data.json:/app/server/data.json \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

Common mistake: using `~/path` instead of `/absolute/path` for the volume mounts a different file and the app starts empty.

### Documentation

**README.md** — added Docker section (build, push, SSH deploy guide, maintenance commands); updated Features list; fixed IRR formula in column calculations table; updated Notes section.

**CLAUDE.md** — fixed all stale sections: dividend utilities (new two-level tax system), IRR (per-country rate), seed data (now empty array), components (delete confirm, error feedback, range persistence), storage layer (production/Docker mode), styling (new CSS classes).

---

## Session 3 — Multiple portfolios, currency switcher, sell UI, import

### New features

**Portfolio summary table with daily change** (`src/components/PortfolioTable.tsx`, `src/types/index.ts`)  
A dedicated summary table below the main positions table shows portfolio-level aggregates: Total Invested, Current Value, Daily Change (today's absolute P&L move), P&L (price only), Total Return (price + dividends), and Portfolio IRR. Daily change is `Σ quote.change × openQty` across all open rows. Values convert to the selected display currency.

**Display currency switcher** (`src/hooks/useFxRates.ts` (NEW), `src/App.tsx`, all table/chart components)  
Toggle CZK / USD / EUR with buttons in the header. `useFxRates` fetches live USDCZK=X and EURCZK=X from Yahoo Finance, defaults `{ CZK:1, USD:25, EUR:27.5 }` while loading, and exports `convert(amount, from, to)` using CZK as the cross-rate base. The `convert` function is passed as a prop through `App → PortfolioContent → PortfolioTable / PriceChart / PortfolioPnLChart`. Every monetary value in the UI and in both charts converts on the fly.

**Sell position UI** (`src/components/SellPositionModal.tsx` (NEW), `src/components/PortfolioTable.tsx`)  
An amber **Sell** button appears on each open row in the main table and on each open individual lot in the expanded mini-table. Clicking opens `SellPositionModal`: sell date (defaults to today), sell price per share (native currency), and a live P&L preview `(sellPrice − avgBuy) × totalQty`. On confirm, `updatePosition(id, { sellPrice, sellDate })` is called for each selected lot. Lots with both fields set are treated as closed throughout the app.

**Multiple portfolios** (`src/hooks/usePortfolios.ts` (NEW), `src/components/PortfolioContent.tsx` (NEW), `src/App.tsx`, `src/hooks/usePortfolio.ts`, `src/hooks/useManualPrices.ts`)  
- `usePortfolios` manages `Portfolio[]` and `activeId`; two-phase init; exports `addPortfolio`, `removePortfolio`, `renamePortfolio`, `switchPortfolio`. Storage keys: `stock_tracker_portfolios`, `stock_tracker_active_portfolio`.
- Legacy migration: on first load, `stock_tracker_positions` is copied to `stock_tracker_positions_${defaultId}` and "Main Portfolio" is created.
- `usePortfolio` and `useManualPrices` now accept a `portfolioId` parameter; storage keys become `stock_tracker_positions_${id}` and `stock_tracker_manual_prices_${id}`.
- `PortfolioContent` was extracted from `App.tsx`; it holds all per-portfolio hooks and row computation. Rendered with `key={activeId}` so switching portfolio remounts the component with fresh state.
- Portfolio tab bar in the header: click to switch, double-click to rename inline (or click the ✎ icon), × to delete (requires confirmation), **+ New** to add. New portfolios auto-enter rename mode.

**JSON import** (`src/components/ImportModal.tsx` (NEW), `src/App.tsx`)  
**↑ Import** button in the portfolio bar opens a hidden file input. The selected `.json` file is parsed by `parsePositionsFromJson`, which handles three formats: a direct `Position[]` array, `{stock_tracker_positions: "..."}`, and the multi-portfolio `{stock_tracker_positions_uuid: "..."}` format. `ImportModal` shows a file summary and lets the user choose: **Create new portfolio** (name pre-filled from filename) or **Add to current portfolio** (append). After merge-import, `contentKey` is incremented to force `PortfolioContent` to remount and re-read storage.

### New files

- `src/hooks/useFxRates.ts` — live FX rates + `convert(amount, from, to)` helper
- `src/hooks/usePortfolios.ts` — multi-portfolio list management + legacy migration
- `src/components/PortfolioContent.tsx` — per-portfolio state container extracted from App.tsx
- `src/components/SellPositionModal.tsx` — sell date + sell price form with live P&L preview
- `src/components/ImportModal.tsx` — import target selection UI
- `server/test-data.json` — 10 US dividend stocks (AAPL, KO, JNJ, PG, MCD, XOM, VZ open; T, PFE fully sold; MSFT partially sold + open) for testing

### Modified files

- `src/types/index.ts` — `PortfolioRow` gains `dailyChange: number`
- `src/hooks/usePortfolio.ts` — accepts `portfolioId` param; per-portfolio storage key
- `src/hooks/useManualPrices.ts` — accepts `portfolioId` param; per-portfolio storage key
- `src/App.tsx` — rewritten to global state only; adds portfolio tab bar, currency switcher, import flow
- `src/components/PortfolioTable.tsx` — adds `displayCurrency` + `convert` props; all values converted before display; sell buttons; summary table; `onSellPositions` prop
- `src/components/PriceChart.tsx` — adds `tickerCurrency`, `displayCurrency`, `convert` props; chart values converted to display currency
- `src/components/PortfolioPnLChart.tsx` — adds `displayCurrency` + `convert` props; all chart P&L and dividend values converted
- `src/App.css` — adds portfolio bar, currency tabs, summary table, sell button, import summary styles

### Docker

**Image pushed to Docker Hub:** `docker.io/59man/stock-tracker:latest`  
Digest: `sha256:cc5279d1bc2e8dfaf8c290eebf84453525c72b6c32ea7bb19587388a6b1718ce`

### Testing the test portfolio

To swap to the test data:
```bash
cp server/data.json server/data.json.backup
cp server/test-data.json server/data.json
```
To restore: `cp server/data.json.backup server/data.json`

The test portfolio uses the old single-key format (`stock_tracker_positions`) so it will be auto-migrated to a "Main Portfolio" on first load.

---

## Session 4 — 2026-06-15 — Today column fix + Distribution pie charts

### Bug fix — Today column showing +CZK 0.00 for FX-converted tickers

**File:** `src/hooks/useQuotes.ts` — `fetchFxConvertedQuote`

**Root cause:** `fetchFxConvertedQuote` fetched with `range=1d&interval=1d` and computed the previous close as:

```js
const prevPrice = (pm.previousClose ?? pm.regularMarketPrice) * (fm.previousClose ?? fm.regularMarketPrice)
```

For 24/7 FX pairs like `EURCZK=X`, `meta.previousClose` is often `null` in the Yahoo Finance v8 chart API response. When both the price ticker and the FX ticker had `null` previousClose, both sides fell back to `regularMarketPrice`, making `prevPrice = price` and `change = 0`. This affected 4GLD.DE and EXUS.DE (both EUR-denominated and both use `EURCZK=X`).

**Fix:**

1. Changed `range=1d` → `range=5d` on both fetches so that 5 daily bars are returned in the response.
2. Parsed the `indicators.quote[0].close` bar array from the chart result.
3. Implemented a three-level fallback chain for each previous close:
   - `meta.previousClose` (the normal case for most instruments)
   - `meta.chartPreviousClose` (intermediate fallback — matches what `fetchFromYahooProxy` already used)
   - **Penultimate non-null bar close** — second-to-last valid daily close from the 5-day history. This is yesterday's completed close whether the market is currently open (today's bar close is null/in-progress) or closed (last bar close = regularMarketPrice, penultimate = yesterday).

```js
const prevPriceBarClose = validPriceCloses.length >= 2
  ? validPriceCloses[validPriceCloses.length - 2]
  : validPriceCloses[0]
```

The resulting `change` value is now a true CZK daily change that accounts for both the asset's EUR price movement and the EUR/CZK rate movement.

---

### New feature — Portfolio Distribution pie charts

**New file:** `src/components/PortfolioPieCharts.tsx`

Three donut charts rendered below the existing portfolio P&L line chart, inside the `chart-section` div in `PortfolioContent`.

**Charts:**

| Chart | Data field | Notes |
|---|---|---|
| Cost Basis | `row.costBasis` | All rows including closed positions |
| Current Value | `row.currentValue` | Closed positions contribute 0 and are filtered out |
| Total Return incl. Dividends | `row.totalReturn` | `pnl + dividendIncome`; negatives shown as red slices |

**Grouping toggle (By Type / By Ticker):**

- **By Type** — aggregates rows into up to four buckets (Stocks, ETFs, Funds, Commodities) using fixed colours per type: stock → `#4f8ef7`, etf → `#50c878`, fund → `#c97ff5`, commodity → `#f5c842`.
- **By Ticker** — one slice per `PortfolioRow`; colours assigned from a 15-entry palette with stable index-based assignment so the same ticker always gets the same colour across all three charts.

**Loss handling (Total Return chart):**

Slices with negative `totalReturn` are rendered as red (`#e05555`) and sized by absolute value. The legend name gets a `(loss)` suffix so the direction is clear without hovering.

Zero-value slices are always filtered before rendering.

**Tooltip:** custom Recharts `content` component (`PieTooltip`) — shows name + value formatted in the selected display currency via `Intl.NumberFormat`. Negative values are displayed as negative numbers in `var(--loss)` colour.

**Responsive layout:** `.pie-charts-grid` is 3-column at > 960 px, collapses to 1-column at ≤ 960 px.

**Recharts components used:** `PieChart`, `Pie`, `Cell`, `Tooltip`, `Legend`, `ResponsiveContainer`.

### Modified files

- `src/components/PortfolioContent.tsx` — imports and renders `PortfolioPieCharts` below `PortfolioPnLChart`, passing `rows`, `displayCurrency`, `convert`
- `src/App.css` — added pie chart CSS block (~80 lines): `.pie-charts-section`, `.pie-charts-header`, `.pie-charts-title`, `.pie-group-toggle`, `.pie-group-btn[.active]`, `.pie-charts-grid`, `.pie-chart-card`, `.pie-chart-title`, `.pie-empty`, `.pie-tooltip`, `.pie-tooltip-name`; responsive collapse at 960 px and 640 px

### Documentation

- **CLAUDE.md** — updated hook 4 (`useQuotes`) with `range=5d` and fallback chain detail; updated `PortfolioContent` component list; added full `PortfolioPieCharts` component entry; added pie chart CSS classes to styling section; updated responsive breakpoints table
- **README.md** — added "Portfolio distribution charts" feature bullet; expanded "Today" column bullet with the previousClose fallback explanation; updated `useQuotes` row in the architecture hooks table

---

## Session 5 — 2026-06-15 — Pie chart label fix + mixed-currency lot bug

### Bug fix — Pie chart percentage labels clipped at container edge

**File:** `src/components/PortfolioPieCharts.tsx`

The percentage labels rendered by Recharts `<Pie label={...}>` were being clipped by the SVG viewport. First attempt used `style={{ overflow: 'visible' }}` on `<PieChart>` — this made labels visible but caused them to render above the SVG element, overlapping the card title.

**Fix:** replaced `overflow: visible` with `margin={{ top: 30, left: 20, right: 20, bottom: 0 }}` on `<PieChart>`. The top margin pushes the chart area down 30 px inside the SVG so labels near the top of the largest slice stay within bounds. Left/right margins handle side labels (e.g. the 92 % slice in the Total Return chart).

---

### Bug fix — Mixed-currency lots producing wrong cost basis, P&L, and current value

**Root cause:** a new 4GLD.DE lot was entered with `currency=EUR` and `buyPrice=119.53` (the raw EUR unit price), while all other 4GLD.DE lots were stored in CZK (buyPrices ~2 583–3 289). The app aggregated all lots without currency normalisation, mixing EUR amounts with CZK amounts in a single sum.

Two separate places were broken:

**`src/components/PortfolioContent.tsx` — row-level aggregation**

`totalCost`, `openCost`, `avgBuyPrice`, `realizedPnl`, and the IRR cash flows all summed raw `buyPrice × quantity` values without converting to the row's currency first. Added a `toRow(amount, lotCurrency)` helper that calls `convert(amount, lotCurrency, rowCurrency)` and applied it to every lot cost before summing. `convert` added to the `useMemo` dependency array.

**`src/components/PortfolioTable.tsx` — per-lot current value / P&L display**

The per-lot `posValue` was computed as `cv(effectivePrice * qty, pos.currency)`. For FX-converted tickers, `effectivePrice` (`r.currentPrice`) is already in `r.currency` (CZK), but wrapping it with `pos.currency = EUR` double-converted the value (×25), producing e.g. CZK 72 416 instead of the correct CZK 3 001.

Fixed by:
- `posValue` → `cv(effectivePrice * qty, r.currency)` (use row currency, not lot currency)
- `buyInRowCcy = convert(pos.buyPrice, pos.currency, r.currency)` — normalize buy price once
- `posPnl` (unsold) → `cv((effectivePrice - buyInRowCcy) * qty, r.currency)`
- `posPnlPct` (unsold) → `((effectivePrice - buyInRowCcy) / buyInRowCcy) * 100`
- Sold-lot paths unchanged (both sellPrice and buyPrice are in pos.currency)

### Docker

**Image pushed to Docker Hub:** `docker.io/59man/stock-tracker:latest`  
Digest: `sha256:4d263ab0b70d9d9c618ca7272538d13a77a736522b39ce6c69414304d0f95df4`

---

## Session 6 — 2026-06-16 — Chart/table alignment + bug fixes

### Feature — Portfolio P&L chart aligned with table Total Return

**Problem:** The "Portfolio Total Return" chart header value differed from the table's "Total Return" summary (e.g. +CZK 31,651 vs +CZK 28,808) even with no closed positions.

**Root cause:** The chart fetched historical closing prices separately (Yahoo Finance chart API). During intraday trading, today's bar has no closing price yet, so the chart's final point was yesterday's close. The table used `quote.price = meta.regularMarketPrice` (live intraday). The difference equalled today's portfolio price move.

**Fix (`src/components/PortfolioPnLChart.tsx`):**
- Added `quotes?: Map<string, Quote>` prop to `PortfolioPnLChart`
- In `effectiveHistories` useMemo, for each ticker with real historical data, injected `quotes.get(ticker.toUpperCase()).price` as today's final bar (replacing or appending), so the chart's last data point always matches the live price the table uses.

**`src/components/PortfolioContent.tsx`:** passes `quotes={quotes}` to `<PortfolioPnLChart>`.

### Bug fixes (code review findings)

#### Bug 1 — Weekend phantom data point (CONFIRMED)

**File:** `src/components/PortfolioPnLChart.tsx`

On weekends/holidays, `quotes` holds Friday's stale close and `new Date().toISOString().slice(0,10)` is a Saturday/Sunday date. The live-price injection unconditionally pushed `["2026-06-21", fridayPrice]` into the history, which then appeared in `chartData`'s `dateSet` as a phantom non-trading-day bar on the X-axis.

**Fix:** compute `dayOfWeek = new Date().getDay()` and skip injection entirely if `dow === 0 || dow === 6`.

#### Bug 2 — Ticker casing mismatch (PLAUSIBLE)

**Files:** `src/components/PortfolioPnLChart.tsx`, `src/components/PortfolioContent.tsx`

`useQuotes` stores all map entries under `ticker.toUpperCase()` (line 155 of `useQuotes.ts`). The chart's `quotes?.get(t)` and the table's `quotes.get(ticker)` / `errors.get(ticker)` used raw ticker casing. `Map.get` is case-sensitive: a position stored with any non-uppercase ticker would silently miss the quote.

**Fixes:**
- `PortfolioPnLChart.tsx`: `quotes?.get(t)` → `quotes?.get(t.toUpperCase())`
- `PortfolioContent.tsx`: `quotes.get(ticker)` → `quotes.get(ticker.toUpperCase())`, `errors.get(ticker)` → `errors.get(ticker.toUpperCase())`
- Note: `loadingSet.has(ticker)` is correct as-is — `useQuotes` stores the loading `Set` with raw (non-uppercased) ticker keys.

#### Bug 3 — No price validation before injection (PLAUSIBLE)

**File:** `src/components/PortfolioPnLChart.tsx`

`parseHistory` filters bad prices with `price && isFinite(price) && price > 0` but the live-quote injection path bypassed this guard. A structurally corrupt quote (NaN from unexpected API response) could have been injected directly.

**Fix:** added `&& liveQuote.price > 0 && isFinite(liveQuote.price)` to the injection condition.

#### Bug 4 — Redundant `hist.length > 0` guard (Simplification)

**File:** `src/components/PortfolioPnLChart.tsx`

Inside the injection block, `hist` was `[...existing]` where `existing.length > 0` had already been checked at the enclosing `if`. The inner `hist.length > 0 &&` was always true. Removed.

### Modified files

- `src/components/PortfolioPnLChart.tsx` — live-quote injection with weekend guard, casing fix, price validation, redundant guard removed
- `src/components/PortfolioContent.tsx` — passes `quotes` prop to chart; `quotes.get` and `errors.get` now use `.toUpperCase()` for consistent map key lookup
