# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Commands

```bash
npm run dev       # starts Vite (http://localhost:5173) + persist server (port 3001) via concurrently
npm run build     # type-check + production build to dist/
npm run preview   # serve the production build locally
npm start         # runs server/index.js directly (production mode, requires prior npm run build)
```

If port 3001 is already in use: `kill $(lsof -ti:3001)`

No test suite or linter configured.

## Docker

```bash
docker build -t 59man/stock-tracker:latest .
docker push 59man/stock-tracker:latest

# Run locally on port 4000
docker run -d --name stock-tracker -p 4000:8080 \
  -v /absolute/path/to/server/data.json:/app/server/data.json \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

- Always use an **absolute path** for the volume mount — `~/...` resolves relative to the shell user and often points to a different file.
- `server/data.json` is excluded from the image via `.dockerignore`; the bind-mount provides it at runtime.
- In production (`NODE_ENV=production`), Express also serves `dist/` as static files and proxies `/api/yahoo/*` to Yahoo Finance (replacing the Vite dev proxy).

## Architecture

**React 18 + Vite + TypeScript** SPA, no routing. All state lives in `App.tsx` and flows down as props.

### Data flow

1. `usePortfolio` (`src/hooks/usePortfolio.ts`) — owns the positions list. Two-phase init: sync from `localStorage` (instant), then async from `GET /api/persist/stock_tracker_positions` (server wins if it has data). Persists changes to both server and `localStorage` after init. Seed migration: `applyMigration()` checks `SEED_VERSION` (currently `'4'`); if stale, appends any seed tickers not yet in storage. Bump `SEED_VERSION` and add entries to `SEED_POSITIONS` when new tickers are added.

2. `useQuotes` (`src/hooks/useQuotes.ts`) — fetches live CZK prices. Yahoo Finance v8 proxy first, Stooq CSV fallback. Module-level 60 s cache; `inFlight` ref prevents duplicate concurrent requests. Tickers in `FX_CONVERTED` (XAU, 4GLD.DE, EXUS.DE) fetch a price ticker + FX pair and multiply.

3. `useDividends` (`src/hooks/useDividends.ts`) — fetches dividend events from Yahoo Finance `range=max&events=div`. Module-level cache. `DIVIDEND_TICKER_ALIASES` maps renamed tickers (e.g. `COLT.PR → CZG.PR`).

4. `useManualPrices` (`src/hooks/useManualPrices.ts`) — stores per-unit prices for funds with no live feed. Same two-phase init + dual-persist pattern as `usePortfolio`. Stored as `{ [TICKER]: { price, updatedAt } }`. `price` is per-unit (total value ÷ quantity entered by user).

5. `App.tsx` derives `PortfolioRow[]` via `useMemo`, merging lots with quotes, manual prices, and dividends. For each ticker, lots are split into `openLots` / `closedLots`. Computes `currentPrice`, `currentValue` (open lots only), `costBasis`, `pnl` (realized + unrealized), `dividendIncome`, `totalReturn`, `irr`, `priceIsManual`, `isClosed`, and `positions[]` sorted by buyDate.

### Storage layer (`src/utils/storage.ts`)

`getItem(key)` / `setItem(key, value)` — async wrapper around the persist server with `localStorage` fallback.

- `GET /api/persist/:key` → `{ value: string | null }`
- `POST /api/persist/:key` body `{ value: string }` → `{ ok: true }`

The Express server (`server/index.js`) reads/writes `server/data.json`. In dev, Vite proxies `/api/persist/*` → `http://localhost:3001`. In production/Docker, Express handles both the API and static file serving on a single port.

### Key types (`src/types/index.ts`)

- `Position` — a single purchase lot: ticker, name, type (`stock|etf|fund|commodity`), quantity, buyPrice, buyDate, currency; **optional** `sellPrice?: number` and `sellDate?: string` mark a lot as closed
- `Quote` — live price response: price, change, changePercent, currency, name
- `PortfolioRow` — one row per ticker; all aggregated fields plus `positions: Position[]`, `priceIsManual: boolean`, `manualPriceDate?: string`, `irr: number | null`, **`isClosed: boolean`**

### Closed position logic (`App.tsx` rows useMemo)

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
terminal value of open lots today.
```

Fully-closed tickers are hidden by default in `PortfolioTable` — toggled by a **"Show closed (N)"** button. Closed rows dim to 55 % opacity with a grey **SOLD** badge. The expanded lot mini-table conditionally adds Sell Date / Sell Price columns.

### Components

- `PortfolioTable` — aggregated position table. Props include `showClosed` / `onToggleClosed`. Each row has a `▶` expand button that reveals individual-lots mini-table and `PriceChart`. Manual price editing (Set / M badge / ×) lives in the Cur. Value cell with inline error feedback. Delete buttons show a confirmation modal before removing. Toolbar has ↓ Export (JSON download).
- `AddPositionModal` — controlled form; calls `onAdd` / `onClose`. On ticker blur, fetches `/api/yahoo/v1/finance/search?q=…` and auto-fills the Name field (works for both ticker symbols and ISINs). Has a "Closed position" checkbox that reveals Sell Date + Sell Price fields.
- `PriceChart` — self-contained; fetches history from Yahoo Finance proxy with range selector (1M–All); handles FX conversion for EUR/USD assets via `CHART_FX` map; selected range persisted to `localStorage` key `chart_range_price`.
- `PortfolioPnLChart` — portfolio total return chart (price P&L + net dividends). Fetches per-ticker daily history; builds synthetic history for manual-priced tickers from buy-date price anchors + current manual price; selected range persisted to `localStorage` key `chart_range_portfolio`.

### FX conversion pattern

Three places carry identical ticker→{priceTicker, fxTicker} maps:
- `useQuotes.ts` → `FX_CONVERTED` (live quote)
- `PriceChart.tsx` → `CHART_FX` (single-ticker history)
- `PortfolioPnLChart.tsx` → `HISTORY_FX` (portfolio history)

When adding a new foreign-currency asset, update all three.

### Dividend utilities (`src/utils/dividends.ts`)

- `COUNTRY_WITHHOLDING_RATES` — per-country withholding tax rates (21 countries; CZ default 15 %)
- `TICKER_COUNTRY` — maps display ticker to ISO country code (e.g. `VIG.PR → AT`, `EXUS.DE → IE`)
- `getDividendTaxRate(ticker)` — looks up country from `TICKER_COUNTRY`, returns rate from `COUNTRY_WITHHOLDING_RATES`; defaults to 15 % (CZ) for unlisted tickers
- `DIVIDEND_TICKER_ALIASES` — maps app tickers to the Yahoo ticker that holds dividend history
- `fetchDividendEvents(ticker)` — fetches and parses Yahoo Finance dividend events
- `calcNetDividends(lots, events, ticker)` — net dividend income for a position; filters events after each lot's buyDate **and** before each lot's sellDate; applies per-country tax rate
- `cumNetDividendsAt(positions, dividendsByTicker, date)` — cumulative net dividends up to a given date (used by `PortfolioPnLChart`); uses per-ticker rate via `getDividendTaxRate`

To add a new foreign ticker: add one line to `TICKER_COUNTRY`. To add a new country: add one line to `COUNTRY_WITHHOLDING_RATES`.

### IRR (`src/utils/xirr.ts`)

Newton-Raphson with bisection fallback. Cash flows: negative on each buy date, sell proceeds on sell dates (closed lots), positive for each dividend received (per lot, per-country withholding tax applied via `getDividendTaxRate`, skipped if lot was sold before ex-date), positive terminal value of open lots today.

### Manual prices for unlisted funds

Three UniCredit onemarkets funds (ISINs LU2606422355, LU2606421548, LU2595011649) and FIO Global Fond (FIOG.PR) have no public price API. The user enters the total position value from their bank report; the app stores `price = totalValue / totalQuantity`. In `PortfolioPnLChart`, synthetic histories are built from individual lot buy prices as anchors plus today's manual price — this makes each lot's P&L start at 0 on its buy date.

### Seed data (`src/data/seedPositions.ts`)

`SEED_POSITIONS` is an empty array — all real positions are stored in `server/data.json` (excluded from git). The migration mechanism (`applyMigration` / `SEED_VERSION`) remains in place for future use: bump `SEED_VERSION` and add entries to `SEED_POSITIONS` to append new tickers on next load without wiping existing data.

### Vite proxy (`vite.config.ts`)

- `/api/yahoo/*` → `https://query1.finance.yahoo.com` with a browser-like `User-Agent`. Required because Yahoo blocks requests without proper headers.
- `/api/persist/*` → `http://localhost:3001` (Express persist server).

Both only active during `npm run dev`. In production/Docker, Express handles both routes directly.

### Styling

Single flat `src/App.css`. CSS custom properties on `:root`. Dark theme (`--bg: #0f0f1a`). Full-width layout (no `max-width` cap). Notable classes: `.badge-{stock|etf|fund|commodity}`, `.gain`/`.loss`, `.badge-manual`, `.badge-sold`, `.row-closed`, `.lot-closed`, `.expand-btn`, `.detail-container`, `.lot-table`, `.closed-toggle`, `.closed-fields`, `.btn-danger`, `.price-edit-error`, `.price-edit-err-msg`.

### Responsive breakpoints (`src/App.css`)

Three `@media` blocks at the bottom of the file:

| Breakpoint | Hidden main-table columns |
|---|---|
| ≤ 960 px | Avg Buy, First Buy, Lots, Cost Basis, Dividends, IRR |
| ≤ 640 px | + Type, Cur. Price, Total Return; modal form-row stacks to 1 column |
| ≤ 400 px | + Qty |

Column visibility is controlled with `.table-scroll > table th/td:nth-child(N)` selectors so no JSX changes are needed to add/remove columns at a breakpoint.
