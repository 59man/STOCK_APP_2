# Stock Tracker

A dark-themed personal portfolio tracker for Czech and international stocks, ETFs, funds, and commodities. Tracks live prices, dividends, P&L, IRR, and realized gains — with support for closed positions, manual price overrides for unlisted funds, and a fully responsive mobile layout.

## Features

- **Multiple portfolios** — create, rename, and delete portfolios; switch via a tab bar; each portfolio's positions and manual prices are stored independently
- **Multi-asset portfolio** — stocks, ETFs, funds, commodities; any currency
- **Display currency switcher** — toggle between CZK / USD / EUR at any time; all table values, summary totals, and both charts convert on the fly using live FX rates
- **Live prices** — Yahoo Finance v8 API via proxy; 60 s module-level cache; Stooq CSV fallback — both sources routed through a server-side proxy to avoid CORS
- **FX conversion** — EUR-denominated assets (4GLD.DE, EXUS.DE) and USD-denominated gold (XAU via GC=F) are automatically converted using real-time FX rates; cross-rates go via CZK as the base
- **Net dividends** — fetched from Yahoo Finance `events.dividends`; per-country withholding tax applied automatically (15 % CZ default, 27.5 % AT, 0 % IE/LU, etc.); aliases handle renamed tickers (e.g. COLT.PR → CZG.PR)
- **Custom dividend tax rates** — override the default withholding rate for any individual dividend event (click the % in the expanded row); custom rates are highlighted in amber and included in all P&L and IRR calculations
- **IRR (XIRR)** — annualised internal rate of return per position and for the whole portfolio, including dividend and sell cash flows
- **Broker / platform column** — optionally record which broker each lot was bought through (XTB, Revolut, IBKR, Fio banka, Degiro, Trading 212); shown as a badge per row with "Mixed" when lots differ
- **Daily P&L column (Today)** — shows today's absolute gain/loss and percentage for each open position based on the live quote's daily change
- **Configurable columns** — click **⚙ Columns** in the toolbar to show/hide any of the 15 data columns and reorder them with ↑ ↓ arrows; config saved to localStorage; opens as a bottom sheet on mobile; responsive defaults match the former CSS breakpoints but the user's choices always win
- **Sell positions** — click **Sell** on any open row or individual lot; enter sell date + sell price and confirm; realized P&L is computed separately from unrealized
- **Closed positions** — fully-closed tickers are hidden by default with a "Show closed (N)" toggle; each shows a grey **SOLD** badge; the lot table gains Sell Date / Sell Price columns when applicable
- **ISIN support** — optional ISIN field stored per position; displayed below the ticker name in the table; editable in edit mode with a **⟲ Lookup** button that resolves ticker + name from an ISIN via Yahoo Finance search
- **Live name lookup** — typing a ticker or ISIN in the Add Position modal auto-fetches the company name from Yahoo Finance on blur
- **Portfolio P&L chart** — total return (price P&L + net dividends) over selectable ranges (1M / 3M / 6M / 1Y / 3Y / 5Y / All) in the selected display currency; range preference persisted to localStorage; unlisted funds with manual prices included via synthetic price history
- **Expandable rows** — click ▶ on any row to reveal individual lots and an embedded price chart with full range controls (range preference persisted); price chart also respects the display currency
- **Manual price override** — for funds with no public price feed: enter the current total value from your bank report; the app divides by quantity to derive the per-unit price; invalid input shows an inline error
- **Enhanced JSON export** — ↓ Export bundles positions, custom dividend tax rates, and manual prices into a single versioned JSON file (`version: 1`)
- **Enhanced JSON import** — ↑ Import restores positions, tax overrides, and manual prices from an enhanced export; the import modal shows which extras are included; appending to an existing portfolio merges overrides
- **Delete confirmation** — removing a row or lot shows a confirmation dialog; cannot be accidentally triggered
- **Persistent file storage** — all data is saved to `server/data.json` via a local Express server with atomic writes (`.tmp` → rename + `.bak` backup); survives browser clears and restarts
- **Docker support** — single-container production image; Express serves the built frontend, proxies Yahoo Finance, and persists data via a bind-mounted `data.json`
- **Fully responsive** — the table adapts at three breakpoints (960 px, 640 px, 400 px) by progressively hiding non-essential columns

## Getting Started (local dev)

```bash
npm install
npm run dev      # starts Vite (http://localhost:5173) + persist server (http://localhost:3001)
npm run build    # type-check + production build
npm run preview  # serve the production build locally
```

`npm run dev` runs both servers via `concurrently`. Both must be running for data to be saved to disk.

If port 3001 is already in use:
```bash
kill $(lsof -ti:3001)
```

## Docker

### Build and push

```bash
# Build image
docker build -t 59man/stock-tracker:latest .

# Push to Docker Hub
docker push 59man/stock-tracker:latest
```

### Deploy on a server via SSH

**1. SSH into your server**
```bash
ssh your_user@your_server_ip
```

**2. Install Docker (Ubuntu/Debian, if not already installed)**
```bash
curl -fsSL https://get.docker.com | sh
sudo usermod -aG docker $USER
newgrp docker
```

**3. Create the data directory and copy your portfolio data**

On your local machine:
```bash
# Create the target directory on the server first
ssh your_user@your_server_ip "mkdir -p /DATA/stock-tracker"

# Copy your data file across
scp server/data.json your_user@your_server_ip:/DATA/stock-tracker/data.json
```

**4. Pull the image**
```bash
docker pull 59man/stock-tracker:latest
```

**5. Run the container**

Replace `4000` with any free port on your server:
```bash
docker run -d \
  --name stock-tracker \
  -p 4000:8080 \
  -v /DATA/stock-tracker/data.json:/app/server/data.json \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

The app is now at `http://your_server_ip:4000`.

> **Important:** always use the **absolute path** for the volume mount (e.g. `/DATA/stock-tracker/data.json`), not `~/...`. A wrong path mounts a different file and the app starts empty.

**6. Open the firewall port if needed**
```bash
# Ubuntu/Debian
sudo ufw allow 4000/tcp && sudo ufw reload

# CentOS/RHEL
sudo firewall-cmd --permanent --add-port=4000/tcp && sudo firewall-cmd --reload
```

### Useful container commands

```bash
docker ps                        # check status and port mapping
docker logs stock-tracker        # view logs
docker logs -f stock-tracker     # follow live logs
docker stop stock-tracker        # stop
docker start stock-tracker       # start again
```

### Update to a new image version

```bash
docker pull 59man/stock-tracker:latest
docker stop stock-tracker && docker rm stock-tracker
docker run -d --name stock-tracker -p 4000:8080 \
  -v /DATA/stock-tracker/data.json:/app/server/data.json \
  --restart unless-stopped \
  59man/stock-tracker:latest
```

### Update portfolio data on the server

```bash
# From your local machine:
scp server/data.json your_user@your_server_ip:/DATA/stock-tracker/data.json
# No container restart needed — file is bind-mounted and read on every request.
```

## Architecture

React 18 + Vite + TypeScript SPA. No routing — `App.tsx` manages global state (portfolios, active portfolio, display currency); per-portfolio state lives in `PortfolioContent`.

### Key hooks

| Hook | Responsibility |
|---|---|
| `usePortfolios` | Manages the list of portfolios and active selection; two-phase init; legacy key migration on first load |
| `usePortfolio(portfolioId)` | Owns positions list for one portfolio; two-phase init; persists to server + localStorage under `stock_tracker_positions_${id}` |
| `useFxRates` | Fetches USDCZK=X and EURCZK=X from Yahoo Finance; provides `convert(amount, from, to)` helper; defaults while loading |
| `useQuotes` | Fetches live prices; Yahoo Finance first, Stooq fallback; FX conversion for XAU / 4GLD.DE / EXUS.DE |
| `useDividends` | Fetches dividend events from Yahoo Finance `range=max&events=div`; module-level cache |
| `useManualPrices(portfolioId)` | Stores user-entered current values for funds with no live feed; two-phase init; persists under `stock_tracker_manual_prices_${id}` |

### Key types

- `Position` — a single purchase lot: ticker, qty, buyPrice, buyDate, currency, type; optional `broker`, `isin`, `sellPrice` / `sellDate`; fully closed when both sell fields are set
- `Quote` — live price data from the API
- `PortfolioRow` — one row per ticker: aggregated lots + computed financials (pnl, dividendIncome, totalReturn, irr, isClosed, dailyChange) + individual `positions[]`

### Closed position logic (`PortfolioContent.tsx`)

Each ticker's lots are split into `openLots` and `closedLots`:

- **Current value** — only open lots contribute (`currentPrice × openQty`)
- **Realized P&L** — `sum((sellPrice − buyPrice) × qty)` for closed lots
- **Unrealized P&L** — `(currentPrice − avgBuyOpen) × openQty` for open lots
- **IRR** — outflows on buy dates, sell proceeds on sell dates, dividend inflows (skipped if lot was sold before ex-date), terminal value of open lots today
- **`isClosed = true`** when all lots are sold — the row displays the avg sell price, contributes 0 to portfolio current value, and is hidden by default

### Storage

Data is stored in two layers:

1. **`server/data.json`** (primary) — written by the Express persist server at `server/index.js`. Keys follow a per-portfolio pattern: `stock_tracker_positions_${id}`, `stock_tracker_manual_prices_${id}`, and `stock_tracker_portfolios` (list). In dev the server runs on port 3001; in Docker it shares port 8080 with the frontend.
2. **`localStorage`** (fallback) — updated in sync; used for instant display on load and as fallback when the server is unreachable.

On startup, hooks read from localStorage immediately (no flash), then async-fetch from the server. If the server has data it takes priority.

The Express server keeps an **in-memory store** loaded once at startup and flushes to disk with a debounced atomic write: `.tmp` → `renameSync` → `data.json`, with a `.bak` copy before each write. SIGINT/SIGTERM flush before exit.

**Legacy migration:** on first load, if the old single-key `stock_tracker_positions` is found it is copied to `stock_tracker_positions_${defaultId}` and a "Main Portfolio" is created automatically.

### Price sources

| Asset | Price ticker | FX ticker |
|---|---|---|
| Czech/Prague stocks (`.PR`) | Yahoo Finance direct | — |
| Gold (Revolut XAU) | `GC=F` (USD/oz) | `USDCZK=X` |
| Xetra-Gold (4GLD.DE) | `4GLD.DE` (EUR) | `EURCZK=X` |
| iShares MSCI World ex USA (EXUS.DE) | `EXUS.DE` (EUR) | `EURCZK=X` |
| onemarkets funds (LU ISINs) | none — manual price only | — |
| FIO Global Fond (FIOG.PR) | none — manual price only | — |

### Production vs dev proxy

In **dev** (`npm run dev`), Vite proxies `/api/yahoo/*`, `/api/stooq/*`, and `/api/persist/*`.  
In **production / Docker** (`NODE_ENV=production`), Express handles all routes:
- Serves static files from `dist/`
- Forwards `/api/yahoo/*` → Yahoo Finance with a browser-like User-Agent
- Forwards `/api/stooq/*` → Stooq (CSV fallback for price quotes; direct browser fetch blocked by CORS)
- Handles `/api/persist/*` read/write to `data.json`

### Column configuration

Click **⚙ Columns** in the table toolbar to open the column config panel. Each of the 15 configurable columns can be independently shown/hidden and reordered with ↑ ↓ arrows. The configuration is saved to `localStorage` key `stock_tracker_column_config`. On ≤ 640 px screens the panel opens as a **bottom sheet** with a dark backdrop.

Column visibility is **JS-controlled** — there are no CSS `display: none` rules per column. `COLUMN_DEFS` in `PortfolioTable.tsx` has a `hideBelow?: number` field; `loadColConfig()` reads `window.innerWidth` on first visit to set defaults matching the former CSS breakpoints. After that, the stored user config is used — explicitly enabled columns always show regardless of viewport width.

| Default hidden below | Columns |
|---|---|
| 960 px | Avg Buy, First Buy, Lots, Broker, Today, Cost Basis, Dividends, IRR |
| 640 px | Type, Cur. Price, Total Return |
| 400 px | Qty |

### Styling

Single flat CSS file (`src/App.css`) with CSS custom properties on `:root`. Dark theme (`--bg: #0f0f1a`), gain/loss via `--gain` / `--loss`. Full-width layout — no `max-width` cap on the main container.

## Column calculations

Each column in the portfolio table is derived as follows. All monetary values are in CZK.

| Column | Formula |
|---|---|
| **Qty** | Sum of `quantity` across all lots for the ticker (open + closed). |
| **Avg Buy** | `Σ(buyPrice × qty) / Σqty` — weighted average buy price across all lots. |
| **First Buy** | Earliest `buyDate` among all lots for the ticker. |
| **Lots** | Number of individual purchase lots stored for the ticker. |
| **Cur. Price** | Live price from Yahoo Finance (or Stooq fallback). For manual-price funds it is `totalValueEntered / openQty`. For fully-closed tickers it shows the weighted-average sell price instead. Falls back to avg buy price if no quote is available yet. |
| **Cur. Value** | `currentPrice × openQty`. Zero for fully-closed tickers (no open lots). |
| **Cost Basis** | `Σ(buyPrice × qty)` for **all** lots — open and closed combined. Used as the denominator for return percentages. |
| **P&L** | `realizedP&L + unrealizedP&L` where: <br>• **Realized** = `Σ(sellPrice − buyPrice) × qty` for closed lots <br>• **Unrealized** = `(currentPrice − avgBuyOpen) × openQty` (`avgBuyOpen` is the weighted avg buy price of open lots only) |
| **P&L %** | `P&L / costBasis × 100` — price-only return relative to total amount invested. |
| **Dividends** | Net dividend income after per-country withholding tax. For each ex-dividend event from Yahoo Finance: shares held on that date (lots whose `buyDate ≤ exDate` and not yet sold) × gross dividend per share × `(1 − rate)`. The rate is a two-level lookup in `dividends.ts`: `TICKER_COUNTRY` maps the ticker to an ISO country code, then `COUNTRY_WITHHOLDING_RATES` returns the rate for that country. Czech tickers (`.PR`) fall through to the **15 %** default. See the country table below for all configured rates. |
| **Total Return** | `P&L + dividendIncome` — combines price gains/losses with net dividend income. |
| **Return %** | `totalReturn / costBasis × 100` — total return (price + dividends) relative to total amount invested. |
| **IRR p.a.** | Annualised XIRR via Newton-Raphson (bisection fallback). Cash flows: negative outflow on each `buyDate`, positive inflow on each `sellDate` (closed lots), positive inflow for each dividend received (shares × grossDiv × `(1 − rate)`, skipped if the lot was sold before ex-date), and a positive terminal value of `currentValue` dated today (omitted for fully-closed tickers). |

### Dividend withholding tax rates

Configured in `src/utils/dividends.ts`. Rates reflect what is typically withheld at source for Czech (EU) resident investors. Where the Czech DTA treaty rate is lower but not enforced at source, the full domestic rate is listed — the excess can be reclaimed from the foreign tax authority.

| Country | Code | Rate | Notes |
|---|---|---|---|
| Czech Republic | CZ | 15 % | Default for all `.PR` tickers |
| Austria | AT | 27.5 % | Full KeSt at source; DTA allows 10–15 %, claim refund for excess |
| Belgium | BE | 30 % | Full rate at source; DTA 15 % requires prior exemption filing |
| Germany | DE | 26.375 % | 25 % + 5.5 % solidarity; refund to DTA 15 % via German tax office |
| Denmark | DK | 27 % | At source for non-residents |
| Spain | ES | 19 % | EU resident rate |
| Finland | FI | 20 % | Non-resident rate at source |
| France | FR | 12.8 % | Flat PFU rate for EU residents (social charges waived for non-French EU) |
| Hungary | HU | 0 % | No dividend WHT |
| Ireland | IE | 0 % | UCITS distributions to non-Irish EU investors (statutory exemption) |
| Italy | IT | 26 % | At source; refund to DTA 15 % possible |
| Luxembourg | LU | 0 % | Non-resident EU investors on UCITS / fund distributions |
| Netherlands | NL | 15 % | Matches CZ-NL DTA — no refund needed |
| Norway | NO | 15 % | EEA; CZ-NO DTA rate enforced at source |
| Poland | PL | 19 % | At source; DTA lower rate with prior exemption |
| Portugal | PT | 25 % | Non-resident rate |
| Sweden | SE | 30 % | At source; refund to DTA 15 % possible |
| Slovenia | SI | 15 % | CZ-SI DTA |
| Slovakia | SK | 15 % | CZ-SK DTA |
| Switzerland | CH | 35 % | Non-EU; full rate at source, refund to 15 % under CZ-CH DTA |
| United Kingdom | GB | 0 % | No dividend WHT |
| USA | US | 15 % | CZ-US DTA, enforced at source with IRS Form W-8BEN |

**To add a new foreign position:** add one line to `TICKER_COUNTRY` in `dividends.ts` mapping the ticker to its ISO country code. If the country is not yet in `COUNTRY_WITHHOLDING_RATES`, add it there too.

### Portfolio summary bar

- **Total Return** — `Σ P&L` across all rows + `Σ dividendIncome` across all rows, with percentage `totalReturn / totalCostBasis × 100`.
- **IRR p.a.** — single XIRR over every buy, sell, and dividend cash flow from every lot, with a terminal value of `Σ currentValue` today.

## Notes

- Your portfolio data is stored in `server/data.json` on disk — excluded from git via `.gitignore` and from Docker images via `.dockerignore`.
- For unlisted funds (UniCredit onemarkets, FIO Global), enter the total position value from your bank report in the **Cur. Value** column. Click the orange **M** badge to update; **×** to clear.
- The persist server must be running (`npm run dev`) for changes to be saved to disk. If unreachable, data is saved to localStorage only.
- To sell an open position, click the amber **Sell** button on the row or on any individual lot in the expanded view; enter a sell date and sell price, then confirm.
- To record a position that was already sold in the past, click **+ Add Position**, fill in the buy details, check **Closed position (already sold)**, and enter the sell date and sell price.
- Use **↑ Import** in the portfolio tab bar to load positions from a JSON file — either into a new portfolio or appended to the current one.
- Use **↓ Export** in the toolbar to download a JSON backup of all positions at any time.
- To switch between CZK, USD, and EUR display, use the currency buttons in the top-right of the header — all values and charts update immediately.
