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

---

## Session 7 — 2026-06-16 — Multi-format import (XLSX, PDF, CSV) + column mapping wizard

### What was built

The import function was upgraded from JSON-only to a multi-format dispatcher that handles broker export files directly, resolves ISINs and asset types via Yahoo Finance, and applies FIFO matching for sell transactions.

### New files

**`src/utils/yahooLookup.ts`**  
Shared Yahoo Finance lookup utility used by all parsers.
- `lookupIsin(isin)` / `lookupTicker(ticker)` — single ISIN or ticker → `{ ticker, type }`
- `batchIsins(isins)` / `batchTickers(tickers)` — deduplicated batch lookup
- Maps Yahoo `quoteType`: `ETF→'etf'`, `MUTUALFUND→'fund'`, `COMMODITY→'commodity'`, else `'stock'`

**`src/utils/fifoMatcher.ts`**  
FIFO matching of buy/sell lots per ticker.
- `RawLot` — intermediate struct with `isSell: boolean`
- `applyFifo(lots)` — groups by ticker, sorts chronologically, consumes sells from the oldest buys first; partial sells split buy lots; returns `Position[]` with `sellDate`/`sellPrice` on fully-closed lots

**`src/utils/xlsxParser.ts`**  
Parses XTB Cash Operations XLSX.
- Detects buy (`Stock purchase`) and sell (`Stock sale`) rows
- Extracts fill qty from comment string: `OPEN BUY 3/3.4281 @ 37.185` → `3`
- Converts `.CZ` → `.PR` suffix for Prague exchange tickers
- `buyPrice = |Amount| / qty` (amount is already CZK)
- Calls `applyFifo` then `batchTickers` for type enrichment

**`src/utils/pdfParser.ts`**  
Three parsers dispatched by PDF content fingerprint:
- **Fio banka** (fingerprint: `"Fio banka"` or `"FIOBCZPP"`) — parses Czech date+time, ISIN, qty, and unit price; handles `Nákup` (buy) and `Prodej` (sell); Czech number format (`1 228,00`); calls `batchIsins` + `applyFifo`
- **Revolut XAU** (fingerprint: `"Revolut"` + `"XAU"`) — finds `"Exchanged to XAU"` lines, extracts net XAU quantity and CZK amount; produces commodity lots directly (no FIFO needed)
- **Generic heuristic** — scans every line for ISIN + buy keyword (multilingual) + date + numbers; `broker: 'Unknown (verify)'`; best-effort fallback for unsupported broker PDFs
- Text extraction: sorts pdfjs items by Y descending (top of page first) then X ascending, groups within ±3 PDF units as one line
- pdfjs-dist imported dynamically inside `parsePdf()` for code-splitting (Vite splits it into a 472 KB async chunk, keeping the main bundle at 633 KB)

**`src/utils/csvParser.ts`**  
Two CSV/XLSX format parsers:
- **Trading 212** — detected by `"No. of shares"` + `"Price / share"` headers; parses `"Market buy"` rows
- **Degiro** — detected by `"Order ID"` + `"ISIN"` + `"Description"` headers; extracts qty/price from Description field (`"Buy N @ PRICE CURRENCY"`); calls `batchIsins`

**`src/components/ColumnMappingModal.tsx`**  
Generic column-mapping wizard for unknown tabular formats.
- Shows file info + preview table (first 3 data rows)
- 10 column-mapping dropdowns (Ticker, Buy Date, Quantity, Buy Price required; Name, ISIN, Currency, Broker, Sell Date, Sell Price optional)
- Skip header rows control (default 1), default currency (default CZK), default broker
- Import button disabled until all four required fields are mapped
- `autoDetectMapping` pre-fills dropdowns by matching header names against keywords in multiple languages

### Modified files

**`src/utils/importParser.ts`** — extended significantly:
- New types: `NeedsMapping { type: 'needs-mapping'; rows }`, `ParseFileResult = ParseResult | NeedsMapping`, `ColumnMapping`, `MappingDefaults`
- New exports: `parseWithMapping(rows, mapping, defaults)`, `autoDetectMapping(header)`, `parseFile(file)`
- `parseFile` dispatcher: PDF → `parsePdf`; XTB XLSX → `parseXtbXlsx`; T212 → `parseT212`; Degiro → `parseDegiro`; unknown tabular → `{ type:'needs-mapping', rows }`; other → `parsePositionsFromJson`
- `parseAnyDate` — flexible date parser (ISO, D.M.YYYY, D/M/YYYY, Month DD YYYY)
- `parseWithMapping` — extracts columns by index, validates required fields, calls `batchTickers` for type enrichment

**`src/App.tsx`** — wires up the new flow:
- Imports `ColumnMappingModal`, `NeedsMapping`, `parseWithMapping`, `ColumnMapping`, `MappingDefaults`
- New state: `columnMapData: { fileName, rows } | null`
- `handleFileSelected` — now checks for `NeedsMapping` result and routes to the column wizard instead of `ImportModal`
- `handleColumnMappingConfirm` — calls `parseWithMapping` then `setImportData`; clears wizard state
- `<ColumnMappingModal>` rendered in JSX when `columnMapData` is set

**`vite.config.ts`** — added `optimizeDeps: { exclude: ['pdfjs-dist'] }` to prevent Vite pre-bundling the PDF worker (causes chunk splitting to fail otherwise).

**`src/components/ImportModal.tsx`** — `baseName` now strips `.xlsx`, `.pdf`, `.csv` extensions in addition to `.json` when pre-filling the new portfolio name.

### Notes

- pdfjs-dist pdf.worker.min.mjs is 1.2 MB — unavoidable for client-side PDF parsing; it loads as a separate async chunk only when a PDF is imported
- xlsx library is 429 KB — also deferred via dynamic import, only loaded on first XLSX/CSV import
- Generic PDF heuristic is intentionally conservative; it marks results with `broker: 'Unknown (verify)'` so the user knows to review prices and quantities
- Revolut Stocks PDF not implemented — no sample file available; XAU (gold purchases) is handled

---

## Session 8 — 2026-06-16 — Code audit & bug fixes (static analysis)

Playwright browser testing was attempted but failed: the MCP Playwright server expects Google Chrome at `/opt/google/chrome/chrome`; only Chromium (at `~/.cache/ms-playwright/chromium-1228/`) and Brave Browser (`/opt/brave-bin/brave-browser`) are installed. Fix for future sessions:

```bash
sudo mkdir -p /opt/google/chrome
sudo ln -sf /opt/brave-bin/brave-browser /opt/google/chrome/chrome
```

All bugs were found via static code analysis instead.

### Bug 1 — Portfolio IRR mixes currencies (HIGH)

**File:** `src/components/PortfolioContent.tsx`

`portfolioIrr` summed `r.currentValue`, `p.buyPrice * p.quantity`, sell proceeds, and dividend inflows directly across positions without currency conversion. For a mixed-currency portfolio (e.g. USD stocks + CZK stocks) the XIRR input was meaningless.

**Fix:** added `toDC(amount, currency)` helper calling `convert(amount, currency, displayCurrency)` and applied it to every cash flow. Added `convert` and `displayCurrency` to the `useMemo` dependency array so IRR recalculates when FX rates update.

### Bug 2 — Non-CZK/USD/EUR currencies silently treated as 1:1 with CZK (HIGH)

**File:** `src/hooks/useFxRates.ts`

`rates[from as keyof Rates] ?? 1` fell back to `1` for any currency not in `{ CZK, USD, EUR }`. `AddPositionModal` and the lot editor both offer GBP, CHF, JPY, CAD, AUD — any position stored in those currencies had completely wrong P&L and value calculations.

**Fix:** expanded `Rates` type to include GBP, CHF, JPY, CAD, AUD with sensible defaults. Added `FX_PAIRS` constant and fetched all 7 pairs in parallel; individual pair failures fall back to defaults without failing the others.

### Bug 3 — Sell price 0 silently erased in lot editor (MEDIUM)

**File:** `src/components/PortfolioTable.tsx:888`

`parseFloat(e.target.value) || undefined` — `parseFloat("0") === 0 → falsy → undefined`. Typing `0` in the sell price field cleared the value instead of saving it.

**Fix:** `e.target.value !== '' ? parseFloat(e.target.value) : undefined`.

### Bug 4 — ISIN entry in Add Position modal didn't resolve ticker (MEDIUM)

**File:** `src/components/AddPositionModal.tsx`

`handleTickerBlur` fetched Yahoo Finance search and only copied the resolved `longname`/`shortname` into the name field. If the user entered an ISIN (e.g. `US0378331005`) in the ticker field the position was stored with the ISIN as its ticker, which broke price fetching until the user manually fixed it in edit mode.

**Fix:** added `if (hit.symbol) set('ticker', hit.symbol)` so the ticker field is also updated with the resolved symbol.

### Bug 5 — Dead export `cumNetDividendsAt` (LOW)

**File:** `src/utils/dividends.ts`

`cumNetDividendsAt` was exported but had zero callers. `CLAUDE.md` documentation said it was used by `PortfolioPnLChart` but the chart was refactored in a previous session to use its own inline per-date dividend loop.

**Fix:** deleted the function.

---

## Session 9 — 2026-06-16 — Playwright feature testing + two bug fixes

### Testing methodology

Full Playwright browser test run via the MCP Playwright server (Brave symlinked to `/opt/google/chrome/chrome` from session 8). Tested: summary card math, individual lot calculations, currency switching (CZK/USD/EUR), closed position toggle, row expand + lot detail, chart range buttons, By Ticker pie charts, Add Position modal, Export, Import (new portfolio + append), and data_test_1 portfolio (Czech stocks, XAU, 4GLD.DE, EXUS.DE, manual-price funds).

### Verified correct

- **Summary math**: Today's Change, Total Value, Cost Basis, Price P&L, Net Dividends, Total Return all reconcile to the cent across open and closed rows (2 closed positions: T and PFE).
- **Lot calculations**: cost, P&L, P&L%, avg buy price all exact (within 1–2 cents of float rounding).
- **Currency switching**: CZK → USD → EUR updates all values and symbols everywhere including summary cards, table cells, chart labels, and pie chart tooltips. IRR p.a. and Return % stay consistent (percentage, currency-agnostic).
- **Export**: produces valid `{ version: 1, positions, manualPrices }` JSON with all 13 positions (open + closed) and manual prices.
- **Import → new portfolio**: correct 7 total / 5 open / 2 closed count, filename pre-filled as portfolio name, today's change math matched after import.
- **Import → append to current**: reads existing positions from server, appends with fresh UUIDs, merges tax overrides and manual prices (imported wins on key conflict), force-remounts via `setContentKey`. No deduplication by design.
- **Add Position modal**: Closed Position checkbox reveals Sell Date + Sell Price fields.
- **data_test_1**: all Czech stocks, FX-converted commodities, and ETF calculations correct.
- **Console errors**: only expected 404s for manual-price tickers (FIOG.PR, 3× LU ISINs) — Yahoo + Stooq both 404 on unlisted funds.

### Bug 1 — Dividend panel showed raw native currency (`$`) regardless of display currency (FIXED)

**File:** `src/components/PortfolioTable.tsx` lines 1010, 1019

The expanded dividend detail panel's Gross and Net columns used `fmt(gross, r.currency)` and `fmt(net, r.currency)` — i.e., the native asset currency (USD for US stocks). The main table `Divid. (net)` cell was correctly converted via `cv()`, but the detail panel was not.

**Fix:**
```tsx
// Before
<td>{fmt(gross, r.currency)}</td>
<td className="gain">{fmt(net, r.currency)}</td>

// After
<td>{fmt(cv(gross, r.currency), displayCurrency)}</td>
<td className="gain">{fmt(cv(net, r.currency), displayCurrency)}</td>
```

`cv` (the `convert` shorthand bound to `displayCurrency`) was already available in scope at that render location.

### Bug 2 — Y-axis labels all showed same rounded value on tight chart ranges (FIXED)

**File:** `src/components/PortfolioPnLChart.tsx` line 332

`tickFormatter` used `.toFixed(0)` for k-values, so values like €2,769 and €3,100 both displayed as `3k`. On any 1M or short-range view where the total return moved less than ±500, all y-axis ticks were identical.

**Fix:**
```tsx
// Before
tickFormatter={(v) =>
  Math.abs(v) >= 1000 ? `${(v / 1000).toFixed(0)}k` : String(v)
}

// After
tickFormatter={(v) =>
  Math.abs(v) >= 1000
    ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
    : parseFloat(v.toFixed(2)).toString()
}
```

`.toFixed(1)` with trailing-`.0` strip: large round values like `63k` stay as `63k`; tight ranges like 2 700–3 100 now show `2.7k`, `3.1k`. Small values use `parseFloat(…toFixed(2))` to avoid float noise (`String(950.7600000001)`).

### Docker

**Image pushed to Docker Hub:** `docker.io/59man/stock-tracker:latest`  
Digest: `sha256:617005f7736a6186648b621824c4c9aab2f35dda14a7f35ee48a7cbf0ccc1d2a`

---

## Session 10 — 2026-07-02 — COLT.PR dividends, 4GLD Today fix, total-price entry, server logging, hardening

### Bug fixes

**COLT.PR dividends missing** (`src/utils/dividends.ts`)
Yahoo delisted `CZG.PR` and moved all data to `COLT.PR` — the alias pointed the wrong way (`COLT.PR → CZG.PR`, hitting the dead symbol). Reversed to `CZG.PR → COLT.PR`. Yahoo also lost all pre-rename dividend history, so a `STATIC_DIVIDENDS` table now hardcodes the known events (2021: 7.50, 2022: 25, 2023: 30, 2024: 30, 2025: 15 CZK gross; verified against coltczgroup.com IR / digrin / divvydiary) and merges them with the live response, deduped by ex-date (Yahoo wins). The rodice portfolio's `CZG.PR` position was renamed to `COLT.PR` via the persist API (Yahoo 404s and Stooq has no symbol — it could never price).

**4GLD.DE wrong Today change** (`src/hooks/useQuotes.ts`)
With `range=5d`, Yahoo returns `meta.previousClose = null` and `meta.chartPreviousClose` = the close *before the whole 5-day window* (~a week old). The fallback chain used chartPreviousClose, so the Today column compared against a week-old price (+1.84% shown vs +0.53% real). FX pairs also append an extra live bar, so "penultimate bar" was wrong too. New `prevDailyClose()` picks the last bar close from a prior exchange-local day (via `meta.gmtoffset`); chain is now `prevDailyClose → previousClose → regularMarketPrice`. Affects 4GLD.DE, XAU, EXUS.DE.

**Pie chart** — loss slices were briefly restored (matching a stale CLAUDE.md description), then reverted on request: negative total-return groups stay excluded by design (commit 5903d41 behaviour). CLAUDE.md corrected.

### Features

**Total-price entry** (`src/components/AddPositionModal.tsx`) — Buy Price gains a **/ share | total** toggle. In total mode the amount paid for the whole lot is divided by quantity on save, with a live "= X / share" hint. Verified via Playwright.

**Server logging** (`server/index.js`) — timestamped stdout/stderr logging visible via `docker logs`: portfolio created/renamed/deleted (diffed on each `stock_tracker_portfolios` write), proxy failures/timeouts, flush errors, startup. Verified end-to-end with an isolated server instance.

### Hardening

- **Daily backups** — first flush of each day writes `server/backups/data-YYYY-MM-DD.json`, keeps last 7. Verified with isolated instance.
- **Money-math tests** — vitest added (`npm test`); `src/utils/money.test.ts` covers `xirr` (gain/loss/degenerate), `applyFifo` (partial-sell split, oldest-first), `calcNetDividends` (date filters, country rates, overrides). 11 tests.
- **NO_FEED_TICKERS** (`src/data/noFeedTickers.ts`) — FIOG.PR + 3 LU funds are never fetched (quotes/dividends/history); console now loads with 0 errors (was ~20 guaranteed 404s).
- **429 backoff** — one Yahoo 429 sets a shared 120 s cooldown; stale cached quote served when all sources fail.
- **Dockerfile HEALTHCHECK** on the persist endpoint; docker-compose + docs gained backups volume and `--log-opt` rotation.
- **Git hygiene** — `server/data.json.bak` (real portfolio data) untracked and gitignored (`server/*.bak`); note: old history still contains it.

### Docker

**Image pushed to Docker Hub:** `docker.io/59man/stock-tracker:latest`
