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
