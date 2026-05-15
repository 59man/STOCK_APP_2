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
