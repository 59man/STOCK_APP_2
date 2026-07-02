# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run dev       # starts Vite (http://localhost:5173) + persist server (port 3001) via concurrently
npm run build     # type-check + production build to dist/
npm run preview   # serve the production build locally
npm start         # runs server/index.js directly (production mode, requires prior npm run build)
npm test          # vitest run — money-math unit tests (src/utils/money.test.ts: xirr, applyFifo, calcNetDividends)
```

If port 3001 is already in use: `kill $(lsof -ti:3001)`

No linter configured.

## Docker

```bash
docker build -t 59man/stock-tracker:latest .
docker push 59man/stock-tracker:latest

# Run locally on port 4000
docker run -d --name stock-tracker -p 4000:8080 \
  -v /absolute/path/to/server/data.json:/app/server/data.json \
  -v /absolute/path/to/backups:/app/server/backups \
  --log-opt max-size=10m --log-opt max-file=3 \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

- Always use an **absolute path** for the volume mount — `~/...` resolves relative to the shell user and often points to a different file.
- `server/data.json` is excluded from the image via `.dockerignore`; the bind-mount provides it at runtime.
- The backups mount is optional but recommended — without it the daily backups stay inside the container and vanish on `docker rm`.
- The image has a `HEALTHCHECK` hitting `/api/persist/...` every 60 s; `docker ps` shows healthy/unhealthy.
- In production (`NODE_ENV=production`), Express also serves `dist/` as static files and proxies `/api/yahoo/*` → Yahoo Finance and `/api/stooq/*` → Stooq (replacing the Vite dev proxy). Both use a shared `proxyRequest()` helper with a 15 s AbortController timeout.

### Update a running container

```bash
docker pull 59man/stock-tracker:latest
docker stop stock-tracker && docker rm stock-tracker
docker run -d --name stock-tracker -p 4000:8080 \
  -v /absolute/path/to/server/data.json:/app/server/data.json \
  -v /absolute/path/to/backups:/app/server/backups \
  --log-opt max-size=10m --log-opt max-file=3 \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

## Architecture

**React 18 + Vite + TypeScript** SPA, no routing. `App.tsx` manages global state (portfolios, active portfolio, display currency). Per-portfolio state lives in `PortfolioContent`.

### Data flow

1. `usePortfolios` (`src/hooks/usePortfolios.ts`) — manages the list of `Portfolio { id, name }` objects and `activeId`. Two-phase init. On first load, migrates the legacy single-key `stock_tracker_positions` → `stock_tracker_positions_${defaultId}`. Storage keys: `stock_tracker_portfolios`, `stock_tracker_active_portfolio`.

2. `usePortfolio(portfolioId)` (`src/hooks/usePortfolio.ts`) — owns the positions list for one portfolio. Two-phase init: sync from `localStorage` (instant), then async from server (server wins). Storage key: `stock_tracker_positions_${portfolioId}`. Seed migration: `applyMigration()` checks `SEED_VERSION` (currently `'4'`); bump it and add entries to `SEED_POSITIONS` when new tickers are added.

3. `useFxRates` (`src/hooks/useFxRates.ts`) — fetches 7 FX pairs (USD, EUR, GBP, CHF, JPY, CAD, AUD vs CZK) from Yahoo Finance in parallel on mount. Per-pair fallback to hardcoded defaults if a fetch fails, so one bad pair doesn't wipe the rest. `Rates` type includes all 7 currencies. Exports `convert(amount, from, to)` — converts via CZK as base; all cross-rates go through CZK. The `?? 1` fallback in `convert` now only triggers for currencies truly unknown to the app (should not happen with the expanded set).

4. `useQuotes` (`src/hooks/useQuotes.ts`) — fetches live prices. Yahoo Finance v8 proxy first (`/api/yahoo/*`), Stooq CSV fallback (`/api/stooq/*` — also proxied to avoid CORS). Module-level 60 s cache; `inFlight` ref prevents duplicate concurrent requests. A Yahoo 429 sets a shared 120 s cooldown (`yahooCooldownUntil`) so subsequent tickers skip Yahoo instead of hammering it; if all sources fail, a stale cache entry is served instead of throwing. Tickers in `NO_FEED_TICKERS` (`src/data/noFeedTickers.ts` — the three LU funds + FIOG.PR) are never fetched at all (quotes, dividends, or chart history) — they 404 everywhere and are manual-priced only; `PortfolioContent` filters them out via `feedTickers`. Tickers in `FX_CONVERTED_SET` (XAU, 4GLD.DE, EXUS.DE) fetch a price ticker + FX pair from `FX_CONVERTED_TICKERS` and multiply to produce a CZK value. HTTP 429 is detected and surfaced with a specific error message. For FX-converted tickers, `fetchFxConvertedQuote` uses `range=5d&interval=1d` and computes the previous close via `prevDailyClose()` — the last bar close whose exchange-local date (via `meta.gmtoffset`) differs from the latest bar's date. This is needed because with `range=5d` Yahoo returns `meta.previousClose = null` and `meta.chartPreviousClose` = the close *before the 5d window* (~a week old), and FX pairs append an extra live bar for today so a naive "penultimate bar" is wrong too. Fallback chain: `prevDailyClose → previousClose → regularMarketPrice`.

5. `useDividends` (`src/hooks/useDividends.ts`) — fetches dividend events from Yahoo Finance `range=max&events=div`. Module-level cache (only on success — errors are not cached, allowing retry). `DIVIDEND_TICKER_ALIASES` maps renamed tickers (e.g. `CZG.PR → COLT.PR` — Yahoo delisted `CZG.PR` and moved all data, including dividends, to `COLT.PR`).

6. `useManualPrices(portfolioId)` (`src/hooks/useManualPrices.ts`) — stores per-unit prices for funds with no live feed. Same two-phase init + dual-persist pattern as `usePortfolio`. Storage key: `stock_tracker_manual_prices_${portfolioId}`. Stored as `{ [TICKER]: { price, updatedAt } }`. `price` is per-unit (total value ÷ quantity entered by user).

7. `useManualDividendTaxes(portfolioId)` (`src/hooks/useManualDividendTaxes.ts`) — stores per-event dividend tax overrides. Storage key: `stock_tracker_div_tax_${portfolioId}`. Type: `Record<string, number>` where key = `TICKER::YYYY-MM-DD`. Same two-phase init + dual-persist pattern. Exports `{ taxOverrides, setDivTax, clearDivTax }`.

8. `PortfolioContent` (`src/components/PortfolioContent.tsx`) — extracted from App.tsx; mounts once per portfolio (via `key={portfolioId}`). Runs hooks 2, 4, 5, 6, 7 above and derives `PortfolioRow[]` via `useMemo`, merging lots with quotes, manual prices, and dividends. Renders `PortfolioTable`, `PortfolioPnLChart`, `PortfolioPieCharts`, and `AddPositionModal`. `App.tsx` passes `displayCurrency` + `convert` down as props.

### Storage layer (`src/utils/storage.ts`)

`getItem(key)` / `setItem(key, value)` — async wrapper around the persist server with `localStorage` fallback. `setItem` logs a warning if the server returns a non-OK status.

- `GET /api/persist/:key` → `{ value: string | null }`
- `POST /api/persist/:key` body `{ value: string }` → `{ ok: true }`

The Express server (`server/index.js`) maintains an **in-memory store** loaded from `server/data.json` at startup. Writes are debounced (500 ms) and flushed atomically: `.tmp` → `renameSync` → `data.json`, with a `.bak` copy before each write. The first flush of each day also writes a dated copy to `server/backups/data-YYYY-MM-DD.json` (last 7 kept, older pruned). All server events (portfolio create/rename/delete, proxy failures, flush errors) are logged to stdout with ISO timestamps — visible via `docker logs`. Express error middleware and process signal handlers (`SIGINT`/`SIGTERM`) flush before exit. In dev, Vite proxies `/api/persist/*` → `http://localhost:3001`. In production/Docker, Express handles both the API and static file serving on a single port.

**Storage key schema:**
- `stock_tracker_portfolios` — JSON array of `{ id, name }` objects
- `stock_tracker_active_portfolio` — active portfolio ID string
- `stock_tracker_positions_${id}` — JSON array of `Position[]` for each portfolio
- `stock_tracker_manual_prices_${id}` — manual price store for each portfolio
- `stock_tracker_div_tax_${id}` — dividend tax overrides for each portfolio

### Key types (`src/types/index.ts`)

- `Position` — a single purchase lot: ticker, name, type (`stock|etf|fund|commodity`), quantity, buyPrice, buyDate, currency; **optional** `broker?: string`, `isin?: string`, `sellPrice?: number`, and `sellDate?: string`
- `Quote` — live price response: price, change, changePercent, currency, name
- `PortfolioRow` — one row per ticker; all aggregated fields plus `positions: Position[]`, `priceIsManual: boolean`, `manualPriceDate?: string`, `irr: number | null`, **`isClosed: boolean`**, **`dailyChange: number`** (absolute daily P&L change = `quote.change × openQty`)

### Closed position logic (`PortfolioContent.tsx` rows useMemo)

```
openLots  = lots where sellPrice/sellDate are absent
closedLots = lots where both sellPrice and sellDate are present
isClosed   = openLots.length === 0

currentValue  = currentPrice × openQty          (0 for fully closed)
realizedPnl   = Σ (sellPrice − buyPrice) × qty  for closedLots
unrealizedPnl = (currentPrice − avgBuyOpen) × openQty
pricePnl      = realizedPnl + unrealizedPnl

IRR cash flows: buy outflows, sell inflows (on sellDate), dividend inflows
(dividends are excluded for a lot if the ex-date falls after the lot was sold),
terminal value of open lots today. Portfolio-level IRR converts all cash flows
to `displayCurrency` via `convert()` so mixed-currency portfolios are handled correctly.
```

Fully-closed tickers are hidden by default in `PortfolioTable` — toggled by a **"Show closed (N)"** button. Closed rows dim to 55 % opacity with a grey **SOLD** badge. The expanded lot mini-table conditionally adds Sell Date / Sell Price columns.

### Components

- `PortfolioContent` — per-portfolio state container; mounts fresh on portfolio switch via `key`; owns all hooks and row computation; renders PortfolioTable + PortfolioPnLChart + PortfolioPieCharts + AddPositionModal.
- `PortfolioPieCharts` (`src/components/PortfolioPieCharts.tsx`) — three donut charts rendered below the P&L line chart: **Cost Basis**, **Current Value**, and **Total Return incl. Dividends**. Props: `rows: PortfolioRow[]`, `displayCurrency`, `convert`. A **By Type / By Ticker** toggle (state: `GroupBy`) aggregates slices by asset class or by individual ticker. Type colours are fixed (`stock` blue, `etf` green, `fund` purple, `commodity` gold); ticker colours cycle through a 15-entry palette with stable assignment per ticker index. Negative total-return entries are excluded from the Total Return chart (a group whose net return is a loss simply doesn't appear). Zero-value slices are always filtered out before rendering. Uses Recharts `PieChart` + `Pie` + `Cell` + `Tooltip` + `Legend` + `ResponsiveContainer`; three charts collapse from 3-column to 1-column at ≤ 960 px.
- `PortfolioTable` — 17-column aggregated position table (expand btn + 15 data cols + actions). Props include `showClosed` / `onToggleClosed`, `displayCurrency`, `convert`, `onSellPositions`, `onUpdatePosition`, `dividendsByTicker`, `taxOverrides`, `onSetDivTax`, `onClearDivTax`. Each row has a `▶` expand button that reveals individual-lots mini-table, dividend event panel with editable tax rates, and `PriceChart`. **Dividend panel Gross/Net columns** are converted to `displayCurrency` via `cv()` (same as all other monetary cells) — `fmt(cv(gross, r.currency), displayCurrency)` — so they always show in the active display currency, not the native asset currency. Sell buttons on main rows and individual open lots open `SellPositionModal`. Manual price editing (Set / M badge / ×) lives in the Cur. Value cell. Delete buttons show a confirmation modal before removing. Toolbar has ↓ Export (JSON download) and **✎ Edit / ✓ Done** toggle for edit mode. Summary section uses a CSS grid of 7 `.summary-card` elements. Row fragments use `<Fragment key={r.ticker}>`.

  **Edit mode** (toolbar toggle): the Ticker cell expands into a block with an editable ticker input, a **▶ Test** button (live Yahoo fetch bypassing the 60 s cache — shows price + currency on success, HTTP status / error on failure), an ISIN input (stored as `isin` on all lots of the ticker), and a **⟲** lookup button (Yahoo search to resolve ticker+name from an ISIN). The Name cell becomes an editable input. The Type badge becomes a `<select>`. In the expanded lot view, each lot shows a **✎** button that replaces the row with inline inputs for: Buy Date, Qty, Buy Price + Currency mini-select, Broker, and (for sold lots) Sell Date + Sell Price. Committing calls `onUpdatePosition(id, updates)` from `PortfolioContent` → `usePortfolio`. In view mode, if a position has an `isin` stored it is shown below the name as a small monospace `.isin-display` label.

  **Column config**: `COLUMN_DEFS` now has a `hideBelow?: number` field per column. `loadColConfig()` uses `window.innerWidth` to set responsive defaults on first visit (no stored config). Column visibility is JS-only — the CSS breakpoints no longer hide columns by class. The column panel opens as a bottom sheet on ≤ 640 px screens (`.col-panel-backdrop` + fixed positioning).
- `SellPositionModal` — enter sell date + sell price for one ticker's open lots; shows a live P&L preview; calls `onSellPositions(ids, sellPrice, sellDate)`.
- `ImportModal` — shows file summary (position count, open/closed breakdown, up to 8 tickers); radio to import into a new portfolio (name pre-filled from filename, strips `.json`/`.xlsx`/`.pdf`/`.csv`) or append to current; calls `onConfirm(mode, newPortfolioName?)`.
- `ColumnMappingModal` — generic column-mapping wizard shown for unknown tabular files (`NeedsMapping` result from `parseFile`). Props: `fileName`, `rows`, `onConfirm(mapping, defaults)`, `onClose`. Shows preview table (first 3 data rows), 10 column dropdowns (4 required, 6 optional), skip-rows control, default currency/broker inputs. `autoDetectMapping` pre-fills dropdowns. Import button disabled until all required fields are mapped.
- `AddPositionModal` — controlled form; calls `onAdd` / `onClose`. On ticker blur, fetches `/api/yahoo/v1/finance/search?q=…` and auto-fills both the **Ticker** field (with `hit.symbol`) and the Name field. This means entering an ISIN resolves to the correct ticker symbol so price fetching works immediately. The Buy Price field has a **/ share | total** toggle — in total mode the entered amount is divided by quantity on submit (a live "= X / share" hint shows the computed per-unit price). Has a "Closed position" checkbox that reveals Sell Date + Sell Price fields. Has a **Broker / Platform** field with a datalist (XTB, Revolut, IBKR, Fio banka, Degiro, Trading 212).
- `PriceChart` — self-contained; fetches history from Yahoo Finance proxy with range selector (1M–All); handles FX conversion for EUR/USD assets via `FX_CONVERTED_TICKERS` (imported from `src/data/fxConvertedTickers.ts`); respects `displayCurrency` prop (converts chart values via `convert`); selected range persisted to `localStorage` key `chart_range_price`.
- `PortfolioPnLChart` — portfolio total return chart (price P&L + net dividends) in the selected display currency. Fetches per-ticker daily history; uses `FX_CONVERTED_TICKERS` from `src/data/fxConvertedTickers.ts`; builds synthetic history for manual-priced tickers from buy-date price anchors + current manual price; selected range persisted to `localStorage` key `chart_range_portfolio`. Accepts optional `taxOverrides` and `quotes` props. **Live-price injection**: on weekdays, injects `quotes.get(ticker.toUpperCase()).price` as today's final history bar in `effectiveHistories`, ensuring the chart's last data point matches the table's live intraday total return instead of lagging at yesterday's close. Skipped on weekends (no phantom non-trading-day bars). Price is validated (`> 0 && isFinite`) before injection. **Y-axis formatter**: values ≥ 1 000 display as `X.Xk` (1 decimal, trailing `.0` stripped) so tight ranges like 2 700–3 100 show distinct labels (`2.7k`, `3.1k`) instead of all rounding to `3k`; values < 1 000 show as `parseFloat(v.toFixed(2)).toString()` to avoid float noise.
- `ErrorBoundary` (`src/components/ErrorBoundary.tsx`) — React class component; wraps `<App />` in `src/main.tsx`. Catches render-phase errors, logs to console, shows an inline error + Retry button. Accepts optional `fallback` prop for custom error UI.

### FX conversion pattern

**Display currency** (`useFxRates` → `convert`): a single `convert(amount, from, to)` function is passed as a prop from `App.tsx` through `PortfolioContent` to all table and chart components. All monetary values in the UI are passed through `convert(value, nativeCurrency, displayCurrency)` before display. The native currency of each row is `row.currency` (the currency field of its first lot).

**Asset-level FX** (fetching CZK prices for foreign assets): single source of truth in `src/data/fxConvertedTickers.ts`. Exports `FX_CONVERTED_TICKERS` (Record) and `FX_CONVERTED_SET` (Set). All three consumers import from this file:
- `useQuotes.ts` — live quote fetch + FX multiplication
- `PriceChart.tsx` — single-ticker history
- `PortfolioPnLChart.tsx` — portfolio history

**When adding a new foreign-currency asset:** add one entry to `FX_CONVERTED_TICKERS` in `src/data/fxConvertedTickers.ts` only — all three consumers pick it up automatically.

### Import parsing

**`src/utils/importParser.ts`** — dispatcher and shared types:
- `ParseResult { valid, skipped, dividendTaxOverrides?, manualPrices? }` — success result
- `NeedsMapping { type: 'needs-mapping'; rows: unknown[][] }` — returned for unknown tabular formats
- `ParseFileResult = ParseResult | NeedsMapping`
- `ColumnMapping` — 10 optional column indices (ticker, date, quantity, buyPrice, name, isin, currency, broker, sellDate, sellPrice)
- `MappingDefaults { currency, broker, skipRows }`
- `parseFile(file)` — dispatcher: PDF→`parsePdf`, XTB XLSX→`parseXtbXlsx`, T212→`parseT212`, Degiro→`parseDegiro`, unknown tabular→`{ type:'needs-mapping', rows }`, other→`parsePositionsFromJson`
- `parseWithMapping(rows, mapping, defaults)` — extracts columns by index, calls `batchTickers` for type enrichment
- `autoDetectMapping(header)` — keyword-matches header names (multilingual) to pre-fill the wizard
- `parsePositionsFromJson(raw)` — handles three JSON formats: `Position[]` array, `{ stock_tracker_positions: "..." }`, multi-portfolio `{ stock_tracker_positions_uuid: "..." }`

**`src/utils/yahooLookup.ts`** — shared Yahoo Finance ISIN/ticker → type lookup used by all parsers:
- `batchIsins(isins)` / `batchTickers(tickers)` → `Record<string, { ticker, type }>`
- Maps `quoteType`: `ETF→'etf'`, `MUTUALFUND→'fund'`, `COMMODITY→'commodity'`, else `'stock'`

**`src/utils/fifoMatcher.ts`** — FIFO lot matching:
- `RawLot { ticker, name, qty, price, date, currency, broker, isin?, type, isSell }` — intermediate struct
- `applyFifo(lots)` — groups by ticker, sorts chronologically, consumes sells from oldest buys; partial sells split a lot; returns `Position[]`

**`src/utils/xlsxParser.ts`** — XTB Cash Operations XLSX: `Stock purchase`/`Stock sale` rows, fill qty from comment, `.CZ`→`.PR` ticker conversion, calls `applyFifo` + `batchTickers`.

**`src/utils/pdfParser.ts`** — PDF parsers (pdfjs-dist loaded dynamically for code-splitting):
- Text extraction groups pdfjs items by Y/X into lines (±3 unit threshold)
- **Fio banka** (`"Fio banka"`/`"FIOBCZPP"`): date, ISIN, `Nákup`/`Prodej`, Czech numbers; calls `batchIsins` + `applyFifo`
- **Revolut XAU** (`"Revolut"` + `"XAU"`): `"Exchanged to XAU"` pattern, extracts qty and CZK amount
- **Generic heuristic**: ISIN + multilingual buy keyword + date + numbers; `broker: 'Unknown (verify)'`

**`src/utils/csvParser.ts`** — CSV/XLSX parsers:
- **Trading 212**: `"No. of shares"` + `"Price / share"` header fingerprint; `"Market buy"` rows
- **Degiro**: `"Order ID"` + `"ISIN"` + `"Description"` fingerprint; extracts qty/price from Description; calls `batchIsins`

### Dividend utilities (`src/utils/dividends.ts`)

- `COUNTRY_WITHHOLDING_RATES` — per-country withholding tax rates (21 countries; CZ default 15 %)
- `TICKER_COUNTRY` — maps display ticker to ISO country code (e.g. `VIG.PR → AT`, `EXUS.DE → IE`)
- `getDividendTaxRate(ticker)` — looks up country from `TICKER_COUNTRY`, returns rate from `COUNTRY_WITHHOLDING_RATES`; defaults to 15 % (CZ) for unlisted tickers
- `DIVIDEND_TICKER_ALIASES` — maps app tickers to the Yahoo ticker that holds dividend history
- `STATIC_DIVIDENDS` — hardcoded events Yahoo lost (COLT.PR 2021–2025, gone since the CZG.PR rename); merged with the Yahoo response, deduped by ex-date (Yahoo wins)
- `fetchDividendEvents(ticker)` — fetches and parses Yahoo Finance dividend events, merges `STATIC_DIVIDENDS`
- `calcNetDividends(lots, events, ticker, taxOverrides?)` — net dividend income for a position; filters events after each lot's buyDate **and** before each lot's sellDate; applies per-country tax rate or per-event override from `taxOverrides`

To add a new foreign ticker: add one line to `TICKER_COUNTRY`. To add a new country: add one line to `COUNTRY_WITHHOLDING_RATES`.

### IRR (`src/utils/xirr.ts`)

Newton-Raphson with bisection fallback. Cash flows: negative on each buy date, sell proceeds on sell dates (closed lots), positive for each dividend received (per lot, per-country withholding tax applied via `getDividendTaxRate`, overridable via `taxOverrides`, skipped if lot was sold before ex-date), positive terminal value of open lots today.

### Manual prices for unlisted funds

Three UniCredit onemarkets funds (ISINs LU2606422355, LU2606421548, LU2595011649) and FIO Global Fond (FIOG.PR) have no public price API. The user enters the total position value from their bank report; the app stores `price = totalValue / totalQuantity`. In `PortfolioPnLChart`, synthetic histories are built from individual lot buy prices as anchors plus today's manual price — this makes each lot's P&L start at 0 on its buy date.

### Seed data (`src/data/seedPositions.ts`)

`SEED_POSITIONS` is an empty array — all real positions are stored in `server/data.json` (excluded from git). The migration mechanism (`applyMigration` / `SEED_VERSION`) remains in place for future use: bump `SEED_VERSION` and add entries to `SEED_POSITIONS` to append new tickers on next load without wiping existing data.

### Vite proxy (`vite.config.ts`)

- `/api/yahoo/*` → `https://query1.finance.yahoo.com` with a browser-like `User-Agent`. Required because Yahoo blocks requests without proper headers.
- `/api/stooq/*` → `https://stooq.com`. Required to avoid CORS — Stooq doesn't serve CORS headers, so direct browser fetches are blocked.
- `/api/persist/*` → `http://localhost:3001` (Express persist server).

All three only active during `npm run dev`. In production/Docker, Express handles all routes directly via a shared `proxyRequest()` helper.

`optimizeDeps: { exclude: ['pdfjs-dist'] }` — prevents Vite from pre-bundling pdfjs-dist, which breaks the dynamic `import('pdfjs-dist')` code-split inside `parsePdf()`. Without this, pdfjs lands in the main bundle (~1.1 MB); with it, it splits into a lazy 472 KB async chunk loaded only when a PDF is imported.

### Styling

Single flat `src/App.css`. CSS custom properties on `:root`. Dark theme (`--bg: #0f0f1a`). Full-width layout (no `max-width` cap). Notable classes:
- Position/lot badges: `.badge-{stock|etf|fund|commodity}`, `.badge-manual`, `.badge-sold`
- P&L colours: `.gain`, `.loss`
- Closed rows: `.row-closed`, `.lot-closed`
- Table structure: `.expand-btn`, `.detail-container`, `.lot-table`, `.closed-toggle`, `.closed-fields`
- Modals/actions: `.btn-danger`, `.price-edit-error`, `.price-edit-err-msg`, `.sell-btn`, `.sell-btn-sm`, `.sell-lots-summary`, `.sell-lots-list`, `.sell-lot-row`, `.sell-pnl-preview`
- Portfolio bar: `.portfolio-bar`, `.portfolio-tab`, `.portfolio-tab.active`, `.portfolio-tab-name`, `.portfolio-tab-rename`, `.portfolio-tab-delete`, `.portfolio-tab-input`, `.portfolio-tab-add`
- Currency switcher: `.currency-tabs`, `.currency-tab`, `.currency-tab.active`
- Summary section: `.summary-section`, `.summary-grid`, `.summary-card`, `.summary-label`, `.summary-value`, `.summary-sub`
- Broker: `.broker-badge`
- Dividend panel: `.div-panel`, `.div-panel-title`, `.div-tax-cell`, `.div-tax-custom`, `.div-tax-default`, `.div-tax-edit`, `.div-tax-input`, `.div-tax-clear`
- Import modal: `.import-summary`, `.import-summary-row`
- Edit mode: `.ticker-edit-block`, `.ticker-edit-row`, `.ticker-edit-input`, `.isin-edit-input`, `.fetch-test-btn`, `.fetch-test-result`, `.name-edit-input`, `.type-edit-select`, `.edit-lot-btn`, `.lot-draft-input`, `.currency-mini-select`, `.isin-display`
- Column panel: `.col-panel-backdrop` (mobile overlay), `.col-panel-wrap`, `.col-panel`, `.col-panel-item`, `.col-panel-arrows`, `.col-panel-reset`
- Pie charts: `.pie-charts-section`, `.pie-charts-header`, `.pie-charts-title`, `.pie-group-toggle`, `.pie-group-btn`, `.pie-group-btn.active`, `.pie-charts-grid`, `.pie-chart-card`, `.pie-chart-title`, `.pie-empty`, `.pie-tooltip`, `.pie-tooltip-name`

### Responsive breakpoints (`src/App.css`)

Column visibility is **JS-controlled** via `COLUMN_DEFS[n].hideBelow` — the CSS media queries no longer hide columns. `loadColConfig()` reads `window.innerWidth` to set responsive defaults on first visit; after that the stored user config is used.

Three `@media` blocks handle non-column layout adjustments:

| Breakpoint | Changes |
|---|---|
| ≤ 960 px | summary-grid → 4 cols; reduced padding; pie-charts-grid → 1 col |
| ≤ 640 px | summary-grid → 2 cols; toolbar `flex-wrap`; column panel → fixed bottom sheet; modal form-row stacks; pie-charts-header wraps |
| ≤ 400 px | reduced padding / font sizes |

Default column visibility by viewport (matches former CSS behaviour):

| Column | Hidden below |
|---|---|
| Type, Cur. Price, Total Return | 640 px |
| Qty | 400 px |
| Avg Buy, First Buy, Lots, Broker, Today, Cost Basis, Dividends, IRR | 960 px |
