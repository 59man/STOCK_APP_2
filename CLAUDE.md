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

Android companion app: see `/android` and the "Android Companion App" section below.

## Auth

`/api/persist/*` (and, in production, `/api/yahoo/*` + `/api/stooq/*`) require an `X-API-Key` header matching `PERSIST_API_KEY`. Copy `.env.example` to `.env` and fill in a real secret (`openssl rand -hex 32`) — `PERSIST_API_KEY` is read by the server at runtime, `VITE_PERSIST_API_KEY` (same value) is baked into the browser bundle at build time, so both must be set and must match. `npm run dev` fails open with a console warning if `.env` is missing (no setup needed for local dev); `NODE_ENV=production` refuses to start without `PERSIST_API_KEY` set. `GET /api/health` is always unauthenticated.

## Docker

```bash
docker compose up -d --build   # local compose run (port 8080, data + backups bind-mounted, log rotation)
                                # reads VITE_PERSIST_API_KEY / PERSIST_API_KEY from .env automatically

docker build -t 59man/stock-tracker:latest --build-arg VITE_PERSIST_API_KEY=<same-secret> .
docker push 59man/stock-tracker:latest

# Run locally on port 4000
docker run -d --name stock-tracker -p 4000:8080 \
  -v /absolute/path/to/server/data.json:/app/server/data.json \
  -v /absolute/path/to/backups:/app/server/backups \
  -e PERSIST_API_KEY=<same-secret> \
  --log-opt max-size=10m --log-opt max-file=3 \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

- Always use an **absolute path** for the volume mount — `~/...` resolves relative to the shell user and often points to a different file.
- All personal files in `server/` are excluded from the image via `.dockerignore` (`server/*.json`, `server/*.bak`, `server/*.pdf`, `server/backups/`) — never weaken these, the directory holds real portfolio data and bank statements. The bind-mounts provide `data.json` and `backups/` at runtime.
- The backups mount is optional but recommended — without it the daily backups stay inside the container and vanish on `docker rm`.
- **`VITE_PERSIST_API_KEY` is a build arg, not a runtime env var** — it's inlined into the built JS at `npm run build` time inside the image's builder stage. Changing it means rebuilding the image, not just restarting the container. `PERSIST_API_KEY` (the server-side runtime check) can change with a plain restart.
- The image has a `HEALTHCHECK` hitting `/api/health` every 60 s; `docker ps` shows healthy/unhealthy.
- In production (`NODE_ENV=production`), Express also serves `dist/` as static files and proxies `/api/yahoo/*` → Yahoo Finance and `/api/stooq/*` → Stooq (replacing the Vite dev proxy). Both use a shared `proxyRequest()` helper with a 15 s AbortController timeout, and both require `X-API-Key` like `/api/persist/*`.

### Update a running container

```bash
docker pull 59man/stock-tracker:latest
docker stop stock-tracker && docker rm stock-tracker
docker run -d --name stock-tracker -p 4000:8080 \
  -v /absolute/path/to/server/data.json:/app/server/data.json \
  -v /absolute/path/to/backups:/app/server/backups \
  -e PERSIST_API_KEY=<same-secret> \
  --log-opt max-size=10m --log-opt max-file=3 \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

## Architecture

**React 18 + Vite + TypeScript** SPA, no routing. `App.tsx` manages global state (portfolios, active portfolio, display currency). Per-portfolio state lives in `PortfolioContent`.

### Data flow

1. `usePortfolios` (`src/hooks/usePortfolios.ts`) — manages the list of `Portfolio { id, name }` objects and `activeId`. Two-phase init. On first load, migrates the legacy single-key `stock_tracker_positions` → `stock_tracker_positions_${defaultId}`. Storage keys: `stock_tracker_portfolios`, `stock_tracker_active_portfolio`.

2. `usePortfolio(portfolioId)` (`src/hooks/usePortfolio.ts`) — owns the positions list for one portfolio. Two-phase init: sync from `localStorage` (instant), then async from server (server wins). Storage key: `stock_tracker_positions_${portfolioId}`. Seed migration: `applyMigration()` checks `SEED_VERSION` (currently `'4'`); bump it and add entries to `SEED_POSITIONS` when new tickers are added.

3. `useFxRates` (`src/hooks/useFxRates.ts`) — fetches 7 FX pairs (USD, EUR, GBP, CHF, JPY, CAD, AUD vs CZK) from Yahoo Finance in parallel on mount. Per-pair fallback to hardcoded defaults if a fetch fails, so one bad pair doesn't wipe the rest. `Rates` type includes all 7 currencies. Exports `convert(amount, from, to)` — converts via CZK as base; all cross-rates go through CZK. Unknown currencies fall back to `?? 1` (treated as CZK) with a once-per-currency `console.warn`.

4. `useQuotes` (`src/hooks/useQuotes.ts`) — fetches live prices. Yahoo Finance v8 proxy first (`/api/yahoo/*`), Stooq CSV fallback (`/api/stooq/*` — also proxied to avoid CORS). Module-level 60 s cache; `inFlight` ref prevents duplicate concurrent requests. A Yahoo 429 sets a shared 120 s cooldown (`yahooCooldownUntil`) so subsequent tickers skip Yahoo instead of hammering it; if all sources fail, a stale cache entry is served instead of throwing. Tickers in `NO_FEED_TICKERS` (`src/data/noFeedTickers.ts`, currently empty — reserved for any ticker with genuinely no feed anywhere) are never fetched at all (quotes, dividends, or chart history) and are manual-priced only; `PortfolioContent` filters them out via `feedTickers`. Tickers in `FUND_PROVIDER_SET` (`src/data/fundProviderTickers.ts` — the three onemarkets LU funds + FIOG.PR, extensible) are auto-fetched through a dedicated server proxy instead of Yahoo/Stooq — see "Auto-fetched prices for onemarkets funds + Fio funds" below. Tickers in `FX_CONVERTED_SET` (XAU, 4GLD.DE, EXUS.DE) fetch a price ticker + FX pair from `FX_CONVERTED_TICKERS` and multiply to produce a CZK value. HTTP 429 is detected and surfaced with a specific error message. For FX-converted tickers, `fetchFxConvertedQuote` uses `range=5d&interval=1d` and computes the previous close via `prevDailyClose()` — the last bar close whose exchange-local date (via `meta.gmtoffset`) differs from the latest bar's date. This is needed because with `range=5d` Yahoo returns `meta.previousClose = null` and `meta.chartPreviousClose` = the close *before the 5d window* (~a week old), and FX pairs append an extra live bar for today so a naive "penultimate bar" is wrong too. Fallback chain: `prevDailyClose → previousClose → regularMarketPrice`. `Quote.currency` is Yahoo's `meta.currency` (`GBp` normalised to GBP with price/change ÷ 100); the Stooq fallback guesses currency from the ticker suffix (`.PR`/`.CZ` → CZK, `.VI`/`.AS`/`.DE` → EUR, `.T` → JPY, else USD). **Quote prices can be in a different currency than the position's lots** (e.g. `8306.T` quotes JPY, lots bought in EUR) — `PortfolioContent` converts quote price and change into row currency via `convert()`; never use `quote.price` against lot amounts directly.

5. `useDividends` (`src/hooks/useDividends.ts`) — fetches dividend events from Yahoo Finance (`yahooDividendQuery()`, see "Yahoo chart query windows" below). Module-level cache (only on success — errors are not cached, allowing retry). `DIVIDEND_TICKER_ALIASES` maps renamed tickers (e.g. `CZG.PR → COLT.PR` — Yahoo delisted `CZG.PR` and moved all data, including dividends, to `COLT.PR`). Each `DividendEvent` carries `currency` (Yahoo `meta.currency`, GBp → GBP ÷ 100) — per-share amounts are in the ticker's native currency, and all consumers (row income, IRR flows, dividend panel, PnL chart) convert via `div.currency ?? lotCurrency`.

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

### Device registry (`/api/devices/*`)

Separate from the generic `/api/persist/:key` store — see `docs/superpowers/specs/2026-08-29-device-registry-design.md` for full design rationale. Descriptive metadata only (device id, label, platform, first/last-seen), not an auth boundary — every device still shares the one `X-API-Key`. Server keeps it in `store.devices` (same in-memory store + flush/backup machinery as everything else, just a typed array instead of an opaque client-authored blob), with dedicated routes doing real server-side upsert/delete rather than a client read-modify-write:

- `POST /api/devices/heartbeat` `{id, label?, platform: 'web'|'android'}` — creates on first sight, else only bumps `lastSeen` (never overwrites an existing label, so a user rename survives the client's next heartbeat)
- `GET /api/devices` — list, sorted by `lastSeen` desc
- `PATCH /api/devices/:id` `{label}` — rename
- `DELETE /api/devices/:id` — remove, idempotent (200 even if already gone)

**Web**: `useDeviceRegistry` hook (heartbeats on mount + every 5 min, id in `localStorage['stock_tracker_device_id']`) + `DeviceListModal`, opened via a 📶 header button next to the 🔑 API-key button. **Android**: device id + label live in the Settings `DataStore` (`SettingsRepository.getOrCreateDeviceId()`); `DeviceRegistry` (`core/data`) heartbeats piggyback on `SyncCoordinator.pullPortfolioList()`'s existing cadence; Settings' **Disconnect** button best-effort unregisters this device, then clears the stored Server URL/API key (which is what actually stops syncing) and the stored device id (so a future reconnect registers fresh, not resurrecting the old `firstSeen`).

> **Gotcha · kotlinx.serialization silently drops fields at their default value.** `PersistJson` (`core/network/PersistKeys.kt`) doesn't set `encodeDefaults`, so a Retrofit `@Body` data class field left at its Kotlin default is omitted from the JSON entirely — bit `DeviceHeartbeatBody.platform` once (`= "android"` default meant the field vanished from the wire body, and the server's manual `platform !== 'web' && platform !== 'android'` check 400'd on the missing field). Fixed by making it a required constructor param with no default. `DeviceApiTest.kt`'s wire-bytes assertion (MockWebServer, same pattern as `PersistApiTest.kt`) is what would have caught it — prefer that pattern over type-only tests for any new persist/device API body.

### Key types (`src/types/index.ts`)

- `Position` — a single purchase lot: ticker, name, type (`stock|etf|fund|commodity|crypto`), quantity, buyPrice, buyDate, currency; **optional** `broker?: string`, `isin?: string`, `sellPrice?: number`, and `sellDate?: string`
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
- `ImportModal` — shows file summary (position count, open/closed breakdown, up to 8 tickers); radio to import into a new portfolio (name pre-filled from filename, strips `.json`/`.xlsx`/`.pdf`/`.csv`) or append to current; calls `onConfirm(mode, newPortfolioName?, currencyOverride?)`. When the parse result has `currencyUncertain` (XTB file without a `EUR_`/`CZK_` filename prefix), shows an account-currency `<select>` and passes the choice as `currencyOverride`, which `App.handleImportConfirm` stamps onto every imported position.
- `ColumnMappingModal` — generic column-mapping wizard shown for unknown tabular files (`NeedsMapping` result from `parseFile`). Props: `fileName`, `rows`, `onConfirm(mapping, defaults)`, `onClose`. Shows preview table (first 3 data rows), 10 column dropdowns (4 required, 6 optional), skip-rows control, default currency/broker inputs. `autoDetectMapping` pre-fills dropdowns. Import button disabled until all required fields are mapped.
- `AddPositionModal` — controlled form; calls `onAdd` / `onClose`. On ticker blur, fetches `/api/yahoo/v1/finance/search?q=…` and auto-fills the **Ticker** field (with `hit.symbol`), the Name field, and the **Type** dropdown (via `mapType()`, exported from `src/utils/yahooLookup.ts` — `ETF`/`MUTUALFUND`/`COMMODITY`/`CRYPTOCURRENCY` → `etf`/`fund`/`commodity`/`crypto`, else `stock`). This means entering an ISIN or a crypto ticker like `BTC-USD` resolves to the correct ticker symbol and type so price fetching works immediately with no manual dropdown pick. The Buy Price field has a **/ share | total** toggle — in total mode the entered amount is divided by quantity on submit (a live "= X / share" hint shows the computed per-unit price). Has a "Closed position" checkbox that reveals Sell Date + Sell Price fields. Has a **Broker / Platform** field with a datalist (XTB, Revolut, IBKR, Fio banka, Degiro, Trading 212).
- `PriceChart` — self-contained; fetches history from Yahoo Finance proxy with range selector (1M–All); handles FX conversion for EUR/USD assets via `FX_CONVERTED_TICKERS` (imported from `src/data/fxConvertedTickers.ts`); respects `displayCurrency` prop (converts chart values via `convert`); selected range persisted to `localStorage` key `chart_range_price`.
- `PortfolioPnLChart` — portfolio total return chart (price P&L + net dividends) in the selected display currency. Fetches per-ticker daily history; uses `FX_CONVERTED_TICKERS` from `src/data/fxConvertedTickers.ts`; builds synthetic history for manual-priced tickers from buy-date price anchors + the manual price anchored at its `updatedAt` date (forward-filled by the step-lookup `priceAt`; anchoring at "today" would fake a one-day jump on the final bar); selected range persisted to `localStorage` key `chart_range_portfolio`. Accepts optional `taxOverrides` and `quotes` props. **History currency**: each ticker's history is in Yahoo's `meta.currency` (GBp → GBP ÷ 100), cached in the module-level `yahooHistCurrency` map filled during fetch; `histCurrency()` reads it (FX-converted tickers → CZK, fallback lot currency). **Historical FX**: `CUR→CZK` daily FX histories (`yahooFxHistoryQuery()`, module-level `fxHistCache`, fetched once per currency per session — USD/EUR always + every lot/history currency) let `convertAt(amount, from, to, date)` convert each chart point at that date's rate: buy cost at buy date, realized gains at sell date, dividends at ex-date, open value at each bar's date. Falls back to spot `convert()` when a rate is missing. **Live-price injection**: on weekdays, injects `quotes.get(ticker.toUpperCase()).price` as today's final history bar in `effectiveHistories`, ensuring the chart's last data point matches the table's live intraday total return instead of lagging at yesterday's close. Skipped on weekends (no phantom non-trading-day bars), when the price is invalid (`> 0 && isFinite`), or when the quote currency mismatches the history currency (rare Stooq fallback during a Yahoo cooldown). **Y-axis formatter**: values ≥ 1 000 display as `X.Xk` (1 decimal, trailing `.0` stripped) so tight ranges like 2 700–3 100 show distinct labels (`2.7k`, `3.1k`) instead of all rounding to `3k`; values < 1 000 show as `parseFloat(v.toFixed(2)).toString()` to avoid float noise. A header toggle switches between **Total Return** (existing price P&L + dividends area chart) and **Portfolio Value** (two-line chart: Cost Basis vs. Current Value of currently-open lots — both drop to 0 for a lot once sold, so this is capital-at-risk, not lifetime-invested capital); selected view persisted to `localStorage` key `chart_view_portfolio`. Cost Basis converts at each lot's buy-date FX rate (frozen). Current Value is *not* an independent price(date)×qty conversion — it's Cost Basis plus that lot's Total Return price gain (the same `lotPricePnl` value added to the Total Return line), so `Current Value − Cost Basis` always equals Total Return's price P&L exactly, for every lot at every date. A naive independent conversion would silently include currency-translation gain/loss (buy-date rate vs. the date's own rate) that Total Return's price P&L does not, making the two views disagree on a foreign-currency lot whose native price never moved. When a lot has no available price (fetch failure or unset manual price), it is valued at cost in the Current Value line rather than dropped, so Cost Basis and Current Value never silently diverge from a missing feed.
- `ErrorBoundary` (`src/components/ErrorBoundary.tsx`) — React class component; wraps `<App />` in `src/main.tsx`. Catches render-phase errors, logs to console, shows an inline error + Retry button. Accepts optional `fallback` prop for custom error UI.
- `ApiKeyModal` (`src/components/ApiKeyModal.tsx`) — opened via a 🔑 header button; shows `import.meta.env.VITE_PERSIST_API_KEY` (masked by default, Show/Copy) so the value can be pasted into the Android app's Settings → API Key field without digging through `.env` or devtools. Read-only display only — the key is baked in at build time, so this can't rotate it live.

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

**`src/utils/xlsxParser.ts`** — XTB Cash Operations XLSX: `Stock purchase`/`Stock sale` rows, fill qty from comment, `.CZ`→`.PR` ticker conversion, calls `applyFifo` + `batchTickers`. The `Amount` column is in the **account** currency, taken from the statement filename prefix (`EUR_53675935_…`, `CZK_…`); the `@ price` in the comment is in the instrument's exchange currency — never use it. No prefix → defaults CZK and sets `currencyUncertain` so `ImportModal` asks the user.

**`src/utils/pdfParser.ts`** — PDF parsers (pdfjs-dist loaded dynamically for code-splitting):
- Text extraction groups pdfjs items by Y/X into lines (±3 unit threshold)
- **Fio banka** (`"Fio banka"`/`"FIOBCZPP"`): date, ISIN, `Nákup`/`Prodej`, Czech numbers; calls `batchIsins` + `applyFifo`
- **Revolut trading statement** (`"Revolut"` + `"Trade - "`): per-currency `XXX Transactions` sections with `Trade - Market … Buy/Sell` rows; symbol → ISIN resolved from the `Portfolio breakdown` table (`batchIsins`), fully-sold symbols fall back to `batchTickers`; calls `applyFifo`. Pure row parser `parseRevolutTradingLines` is exported and unit-tested in `src/utils/pdfParser.test.ts`
- **Revolut XAU** (`"Revolut"` + `"XAU"`): `"Exchanged to XAU"` pattern, extracts qty and CZK amount
- **Generic heuristic**: ISIN + multilingual buy keyword + date + numbers; `broker: 'Unknown (verify)'`

**`src/utils/csvParser.ts`** — CSV/XLSX parsers:
- **Trading 212**: `"No. of shares"` + `"Price / share"` header fingerprint; `"Market buy"` rows
- **Degiro**: `"Order ID"` + `"ISIN"` + `"Description"` fingerprint; extracts qty/price from Description; calls `batchIsins`

### Dividend utilities (`src/utils/dividends.ts`)

- `COUNTRY_WITHHOLDING_RATES` — per-country withholding tax rates (22 countries incl. JP 15.315 %; CZ default 15 %)
- `TICKER_COUNTRY` — maps display ticker to ISO country code (e.g. `VIG.PR → AT`, `EXUS.DE → IE`)
- `getDividendTaxRate(ticker)` — looks up country from `TICKER_COUNTRY`, returns rate from `COUNTRY_WITHHOLDING_RATES`; defaults to 15 % (CZ) for unlisted tickers
- `DIVIDEND_TICKER_ALIASES` — maps app tickers to the Yahoo ticker that holds dividend history
- `STATIC_DIVIDENDS` — hardcoded events Yahoo lost (COLT.PR 2021–2025, gone since the CZG.PR rename); merged with the Yahoo response, deduped by ex-date (Yahoo wins)
- `fetchDividendEvents(ticker)` — fetches and parses Yahoo Finance dividend events, merges `STATIC_DIVIDENDS`
- `calcNetDividends(lots, events, ticker, taxOverrides?)` — net dividend income for a position; filters events after each lot's buyDate **and** before each lot's sellDate; applies per-country tax rate or per-event override from `taxOverrides`

To add a new foreign ticker: add one line to `TICKER_COUNTRY`. To add a new country: add one line to `COUNTRY_WITHHOLDING_RATES`.

### IRR (`src/utils/xirr.ts`)

Newton-Raphson with bisection fallback. Cash flows: negative on each buy date, sell proceeds on sell dates (closed lots), positive for each dividend received (per lot, per-country withholding tax applied via `getDividendTaxRate`, overridable via `taxOverrides`, skipped if lot was sold before ex-date), positive terminal value of open lots today.

### Auto-fetched prices for onemarkets funds + Fio funds

Three UniCredit onemarkets funds (ISINs LU2606422355, LU2606421548, LU2595011649) and FIO Global Fond (FIOG.PR) have no Yahoo/Stooq listing, but each provider's own website widget calls a public, unauthenticated-but-undocumented data endpoint — the app proxies these server-side (CORS blocks a direct browser call) and treats them as a normal live quote. Both routes are **generic over any fund from that provider**, not hardcoded to today's 4 tickers — adding another onemarkets or Fio fund later is a one-line addition to `FUND_PROVIDER_TICKERS`, no server change needed:
- **onemarkets** — stateless CSV passthrough at `GET /api/onemarkets/*` → `https://www.onemarkets.cz` (same pattern as `/api/yahoo`/`/api/stooq`, registered under `IS_PROD` in `server/index.js`, mirrored in `vite.config.ts` for dev). `fetchOnemarketsQuote` (`src/hooks/useQuotes.ts`) requests `bin/onemarkets-relaunch/multi-chartdata.csv?isin=<ISIN>&...` and parses `DD.MM.YYYY;price;;` rows via `parseOnemarketsCsv` (`src/utils/fundQuoteParsers.ts`) — already generic over any ISIN.
- **Fio funds** — `GET /api/fio-fund/quote?slug=<slug>`, a dedicated (non-passthrough) route in `server/index.js`, generic over `<slug>` (the fund's URL path segment at `fiofondy.cz/cs/podilove-fondy/<slug>`, validated against `/^[a-z0-9-]+$/` to reject anything else): fiofondy.cz's NAV chart is a Nette "signal" endpoint gated by a session cookie, so the route first GETs a fund page to capture `Set-Cookie` (cached ~30 min in `fioCookieCache`, shared across every slug — the session is site-wide, verified), then GETs `<slug's fund page>?do=getFundChartData` with that cookie + `X-Requested-With: XMLHttpRequest` + matching `Referer`, and passes the raw JSON array through. `fetchFioFundQuote` (`src/hooks/useQuotes.ts`) parses it via `parseFioFundJson`.

`FUND_PROVIDER_TICKERS`/`FUND_PROVIDER_SET` (`src/data/fundProviderTickers.ts`) is the single source of truth for which tickers use which provider (and, for `fio-fund` entries, which `slug`) — checked in `useQuotes.ts`'s `fetchQuote()` before falling through to the normal Yahoo/Stooq chain. `PortfolioContent.tsx` still excludes these from the dividend fetch (`dividendTickers`) since neither provider exposes dividend history, but no longer excludes them from the quote fetch — `NO_FEED_TICKERS` (`src/data/noFeedTickers.ts`) is now empty, reserved for any future ticker with genuinely no feed anywhere.

Once a quote exists, `PortfolioContent.tsx`'s `priceIsManual = !isClosed && !quote && !!manual` means the live price automatically wins over any previously-stored manual entry — no migration needed, old manual prices just go dormant. The manual "Set/M/×" UI (`PortfolioTable.tsx`) still works unchanged as a fallback if a provider route ever breaks. `PortfolioPnLChart`'s synthetic-history fallback (for when Yahoo history is unavailable, which it always is for these tickers) now prefers the live quote over a stale manual price as the "today" knot when both exist.

**Self-healing manual-price cache**: `useQuotes`' 60 s in-memory cache doesn't survive a page reload, so a reload landing before a provider responds (or a transient provider hiccup) would otherwise fall back to whatever manual price happens to be stored — which, before this, could be a genuinely stale pre-automation bank-report entry from months ago. `PortfolioContent.tsx` has a second effect that, whenever a fund-provider ticker's quote fetch succeeds, silently calls `setManualPrice(ticker, quote.price)` if it differs from what's stored — so the persisted manual-price fallback is always the last real live price, not an old one. This is a pure data-correctness fix: display logic is unchanged (a live quote always wins when present), only what's *stored as the fallback* changes. Verified by deliberately corrupting a stored manual price server-side and confirming a single page load repairs it back to match the live feed.

Parsing is unit-tested against real recorded fixtures in `src/utils/fundQuoteParsers.test.ts` (no network, runs under plain `npm test`). `node server/scripts/check-fund-feeds.mjs` is an opt-in smoke script (not part of CI) that hits the live endpoints directly — run it if a fund's price looks stuck, to check whether the provider changed its endpoint shape.

### Seed data (`src/data/seedPositions.ts`)

`SEED_POSITIONS` is an empty array — all real positions are stored in `server/data.json` (excluded from git). The migration mechanism (`applyMigration` / `SEED_VERSION`) remains in place for future use: bump `SEED_VERSION` and add entries to `SEED_POSITIONS` to append new tickers on next load without wiping existing data.

### Yahoo chart query windows (`src/utils/yahooWindow.ts` / `core/network/YahooWindow.kt`)

**Never send `range=max` to Yahoo's v8 chart API.** Yahoo silently ignores the requested
`interval` on a `max` range and answers with 3mo bars for equities / 1mo bars for FX. With
`events=div` that is not just a resolution loss: the dividend map comes back covering
1962–2003 plus *only the single most recent event*, so every distribution paid in the last
~20 years except the newest disappears — silently under-reporting dividend income, total
return and IRR everywhere. (This is what broke Android dividends; fixed 2026-09-05.) An
explicit `period1=0&period2=<now>` window is what makes Yahoo honour the interval.

Three builders, mirrored one-for-one between web and Android — always use them, never
hand-build a chart query:

| Builder | Interval | Used by |
|---|---|---|
| `yahooChartQuery(range)` | `1d`, or `1wk` for `max` | `PriceChart`, `PortfolioPnLChart`, `HistoryClient.fetchHistory` |
| `yahooDividendQuery(interval?)` | `1wk` over the full window, `1d` on retry | `dividends.ts`, `DividendClient` |
| `yahooFxHistoryQuery()` | `1d` over the full window | `PortfolioPnLChart` FX histories, `HistoryClient.fetchFxHistory` |

Why those intervals: Yahoo emits at most one dividend per bar, so `3mo` collapses JNJ's 227
payouts to 168 and `1mo` drops one — `1wk` returned event-for-event identical sets to daily
for JNJ/VIG.PR/8306.T/DTE.DE at ~1/5 the payload. A weekly-distribution ETF (QDTE, XDTE, …)
*would* saturate a weekly bar, so both dividend fetchers check for the saturation signature
— one event per bar — and transparently refetch at `interval=1d`, which no real
distribution schedule can saturate. For charts, an
unbounded *daily* max request is 14 291 bars / 1.4 MB **per ticker**, which the portfolio
chart would multiply by every holding; weekly is ~5x smaller and still far finer than the
3mo bars `range=max` yielded. FX history stays daily because `convertAt()` resolves every
chart point at that date's own rate.

Both dividend fetchers **throw on a non-OK response** rather than resolving empty: the
caches (`useDividends`, `DividendRepository`) keep whatever resolves for the whole session,
so swallowing a 429/500 would pin a ticker at "no dividends" with no retry. A genuinely
empty but *successful* response (an accumulating ETF) still caches, as it should.

A daily epoch-to-now FX history is ~600 KB per currency, so it must be fetched once per
session, not once per chart render: the web has `PortfolioPnLChart`'s module-level
`fxHistCache` and Android has a `ConcurrentHashMap` inside `HistoryClient.fetchFxHistory`
(only successful fetches are cached, so a transient failure still retries).

### Vite proxy (`vite.config.ts`)

- `/api/yahoo/*` → `https://query1.finance.yahoo.com` with a browser-like `User-Agent`. Required because Yahoo blocks requests without proper headers.
- `/api/stooq/*` → `https://stooq.com`. Required to avoid CORS — Stooq doesn't serve CORS headers, so direct browser fetches are blocked.
- `/api/persist/*` → `http://localhost:3001` (Express persist server).

All three only active during `npm run dev`. In production/Docker, Express handles all routes directly via a shared `proxyRequest()` helper.

`optimizeDeps: { exclude: ['pdfjs-dist'] }` — prevents Vite from pre-bundling pdfjs-dist, which breaks the dynamic `import('pdfjs-dist')` code-split inside `parsePdf()`. Without this, pdfjs lands in the main bundle (~1.1 MB); with it, it splits into a lazy 472 KB async chunk loaded only when a PDF is imported.

### Styling

Single flat `src/App.css`. CSS custom properties on `:root`. Dark theme (`--bg: #0f0f1a`). Full-width layout (no `max-width` cap). Notable classes:
- Position/lot badges: `.badge-{stock|etf|fund|commodity|crypto}`, `.badge-manual`, `.badge-sold`
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

## Android Companion App

A native Kotlin + Jetpack Compose app lives in `/android` — a separate Gradle project (own `settings.gradle.kts`), full CRUD parity with the web app, offline-first (Room + WorkManager sync), calculations ported natively into `core:calc` so the app works fully with zero connectivity. Prebuilt debug APK: [GitHub Releases](https://github.com/59man/STOCK_APP_2/releases/latest). Full design rationale, phased build plan, and the sync/merge strategy: `android/docs/mobile-sync-blueprint.md` (a planning-time snapshot — cross-check against the code below for current state).

```bash
cd android
./gradlew :core:calc:test     # calc-module unit tests (xirr/fifo/dividends/chart math) — plain JVM, no emulator
./gradlew test                # full unit test suite across all modules
./gradlew :app:assembleDebug  # → android/app/build/outputs/apk/debug/app-debug.apk
```

### Module layout

- `app` — `MainActivity`, `NavHost`, DI root
- `core/model` — pure Kotlin data classes, zero Android deps (mirrors `src/types/index.ts`)
- `core/calc` — plain JVM module: `Xirr.kt`/`FifoMatcher.kt`/`Dividends.kt`/`RowDerivation.kt` port `xirr.ts`/`fifoMatcher.ts`/`dividends.ts`/the row-derivation logic field-for-field; `ChartMath.kt` ports the `PortfolioPnLChart.tsx` date math (`priceAt` step-lookup, `interpolateDaily`, `buildPortfolioChartData`). Tested independently of Android (`ChartMathTest.kt`, `XirrTest.kt`, etc.)
- `core/database` — Room entities/DAOs/TypeConverters
- `core/network` — Retrofit `PersistApi` (sync only) + direct-to-Yahoo/Stooq clients: `QuoteClient`, `HistoryClient`, `DividendClient`, `FxRateClient`, `YahooLookupClient`. Quotes, chart history, dividends, FX rates, and ISIN/ticker lookup all go phone→Yahoo/Stooq directly and never touch the user's own server — only sync does (Mobile Sync Blueprint, Phase 2 §00)
- `core/import` — on-device PDF/XLSX/CSV statement parsing, all five broker formats
- `core/data` — repositories, settings DataStore, sync workers (`SyncCoordinator`, `KeyedListSyncEngine`/`KeyedMapSyncEngine`, `ConflictCenter`, `PushWorker`)
- `core/designsystem` — Material3 theme (`StockTrackerTheme`, `StockTrackerColors`) + a hand-rolled Compose Canvas charting layer under `chart/` (`AreaLineChart`, `MultiLineChart`, `DonutChart`, `sparseLabelIndices`, `formatChartValue`) — no external charting dependency, to match the app's minimal-dependency approach
- `feature/portfolio` — list screen (XTB-style compact rows: circular logo/avatar via Coil3 `SubcomposeAsyncImage` — the app's one external image-loading dependency, falling back to a colored initial-letter avatar on load failure — ticker, type badge, native-currency badge, name, price, today's %, total return incl. dividends), a CZK/USD/EUR display-currency quick-switcher (`CurrencyTabs`, colored per currency, persists to the same `SettingsRepository` value the Settings screen's dropdown writes), position detail screen (P&L/Return/IRR, Set price/Sell/Delete, lot table, dividend table: Date/Gross/Tax %/Net), add/edit/sell dialogs, import screen, charts, ViewModels. The portfolio summary card lives on the Insights tab, not here (one value per row) — Portfolio tab is just the position list.
- `feature/settings` — server URL, API key, display currency

### Sync model

Room is the offline-first source of truth; every mutation commits locally first, then enqueues a `WorkManager` push of the **entire array** for that key (matches the server's whole-array-per-key storage model — there's no per-record endpoint). A three-way merge (local diff + remote diff, both against a last-synced snapshot) reconciles concurrent edits made on the phone and the web app while one was offline. A genuine same-record conflict (e.g. a sell price edited on both devices) surfaces a one-tap resolution prompt (`ConflictCenter`/`ConflictScreen`) instead of silently picking a winner. `ManualPriceEntity` carries a real `updatedAt`, so a same-ticker manual-price conflict resolves by recency automatically; positions and portfolios have no modification timestamp, so they always prompt.

### Charts

Ported from `PriceChart.tsx` / `PortfolioPnLChart.tsx` / `PortfolioPieCharts.tsx`: per-ticker price history (in each position card's expanded view), portfolio Total Return / Portfolio Value (Cost Basis vs. Current Value, toggle in `PortfolioPnlChartCard`), and three full pie charts (Cost Basis / Current Value / Total Return incl. Dividends, grouped by type/ticker/currency in `PortfolioPieChartsCard`) — `DonutChart` in `core:designsystem/chart` despite the name now draws a solid pie (`useCenter = true`, no stroke ring), matching the web's solid `Pie`. Per-ticker and portfolio history fetch directly from Yahoo via `HistoryClient`; portfolio-chart math (FX-at-date conversion, synthetic histories for manual-priced tickers, live-quote injection as today's bar) lives in `core:calc`'s `ChartMath.kt`, orchestrated by `PortfolioChartViewModel`.

> **Gotcha · FX_CONVERTED_TICKERS must stay unencoded.** `QuoteClient`/`HistoryClient` each call `URLEncoder.encode()` on every ticker they fetch, exactly once. Storing a pre-encoded string there (e.g. `"EURCZK%3DX"`, matching the web's `fxConvertedTickers.ts` convention) gets encoded a second time into `"EURCZK%253DX"` and 404s — this broke every FX-converted ticker (XAU, 4GLD.DE, EXUS.DE) until fixed 2026-08-18. Always store raw tickers (`"EURCZK=X"`) in `core:model`'s `FxConvertedTickers.kt`.

### Releases

Debug APKs are distributed via [GitHub Releases](https://github.com/59man/STOCK_APP_2/releases), one fresh tag per version bump — `android-v<versionName>` (matching `versionName` in `app/build.gradle.kts`; do not reuse/clobber a prior tag, `gh release list` shows the real current one since this note goes stale). Asset filename convention: `stock-tracker-v<version>.apk`.

```bash
# 1. bump versionCode/versionName in android/app/build.gradle.kts, commit, push
cd android && ./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk /tmp/stock-tracker-v<version>.apk
gh release create android-v<version> /tmp/stock-tracker-v<version>.apk --title "Android v<version>" --notes "..."
```
