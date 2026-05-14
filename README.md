# Stock Tracker

A dark-themed personal portfolio tracker for Czech and international stocks, ETFs, funds, and commodities. Tracks live prices, dividends, P&L, IRR, and realized gains — with support for closed positions, manual price overrides for unlisted funds, and a fully responsive mobile layout.

## Features

- **Multi-asset portfolio** — stocks, ETFs, funds, commodities; CZK-denominated
- **Live prices** — Yahoo Finance v8 API via Vite dev-server proxy (avoids CORS); 60 s module-level cache
- **FX conversion** — EUR-denominated assets (4GLD.DE, EXUS.DE) and USD-denominated gold (XAU via GC=F) are automatically converted to CZK using real-time FX rates
- **Net dividends** — fetched from Yahoo Finance `events.dividends`; 15 % Czech withholding tax applied automatically; aliases handle renamed tickers (e.g. COLT.PR → CZG.PR)
- **IRR (XIRR)** — annualised internal rate of return per position and for the whole portfolio, including dividend and sell cash flows
- **Closed positions** — record sold lots with a sell date and sell price; realized P&L is computed separately from unrealized; fully-closed tickers are hidden by default with a "Show closed (N)" toggle; each ticker shows a grey **SOLD** badge; the lot table gains Sell Date / Sell Price columns when applicable
- **Live name lookup** — typing a ticker or ISIN in the Add Position modal auto-fetches the company name from Yahoo Finance on blur; the name field is filled automatically if it was empty
- **Portfolio P&L chart** — total return (price P&L + net dividends) over selectable ranges (1M / 3M / 6M / 1Y / 3Y / 5Y / All); unlisted funds with manual prices included via synthetic price history built from buy-date anchors
- **Expandable rows** — click ▶ on any row to reveal individual lots (buy date, qty, sell date if applicable, cost, P&L per lot) and an embedded price chart with full range controls
- **Manual price override** — for funds with no public price feed (e.g. UniCredit onemarkets, FIO Global Fond): enter the current total value from your bank report; the app divides by quantity to derive the per-unit price
- **Persistent file storage** — portfolio data and manual prices are saved to `server/data.json` via a local Express server; survives browser clears, profile changes, and localhost restarts
- **Seed positions with migration** — initial portfolio loaded from `src/data/seedPositions.ts`; bumping `SEED_VERSION` appends new tickers without wiping existing data
- **Fully responsive** — the table adapts at three breakpoints (960 px, 640 px, 400 px) by progressively hiding non-essential columns; the summary bar scrolls horizontally on mobile; the modal stacks to a single column

## Getting Started

```bash
npm install
npm run dev      # starts Vite (http://localhost:5173) + persist server (http://localhost:3001)
npm run build    # type-check + production build
npm run preview  # serve the production build
```

`npm run dev` runs both the Vite dev server and the Express persist server together via `concurrently`. Both must be running for data to be saved to disk.

If port 3001 is already in use on startup (`EADDRINUSE`), kill the old process:
```bash
kill $(lsof -ti:3001)
```

## Architecture

React 18 + Vite + TypeScript SPA. No routing — all state in `App.tsx`, flows down as props.

### Key hooks

| Hook | Responsibility |
|---|---|
| `usePortfolio` | Owns positions list; two-phase init (sync from localStorage, async from server); persists to `server/data.json` + localStorage |
| `useQuotes` | Fetches live prices; Yahoo Finance first, Stooq fallback; FX conversion for XAU / 4GLD.DE / EXUS.DE; skips fully-closed tickers |
| `useDividends` | Fetches dividend events from Yahoo Finance `range=max&events=div`; module-level cache |
| `useManualPrices` | Stores user-entered current values for funds with no live feed; two-phase init; persists to `server/data.json` + localStorage |

### Key types

- `Position` — a single purchase lot: ticker, qty, buyPrice, buyDate, currency, type; optional `sellPrice` / `sellDate` mark it as closed
- `Quote` — live price data from the API
- `PortfolioRow` — one row per ticker: aggregated lots + computed financials (pnl, dividendIncome, totalReturn, irr, isClosed) + individual `positions[]`

### Closed position logic (`App.tsx`)

Each ticker's lots are split into `openLots` and `closedLots`:

- **Current value** — only open lots contribute (`currentPrice × openQty`)
- **Realized P&L** — `sum((sellPrice − buyPrice) × qty)` for closed lots
- **Unrealized P&L** — `(currentPrice − avgBuyOpen) × openQty` for open lots
- **IRR** — outflows on buy dates, sell proceeds on sell dates, dividend inflows (skipped if lot was sold before ex-date), terminal value of open lots today
- **`isClosed = true`** when all lots are sold — the row displays the avg sell price, contributes 0 to portfolio current value, and is hidden by default

### Storage

Data is stored in two layers:

1. **`server/data.json`** (primary) — written by the Express persist server at `server/index.js` (port 3001). Keys: `stock_tracker_positions`, `stock_tracker_manual_prices`.
2. **`localStorage`** (fallback) — updated in sync; used for instant display on load and as fallback when the server is unreachable.

On startup, hooks read from localStorage immediately (no flash), then async-fetch from the server. If the server has data it takes priority; if the server returns nothing (first run or unavailable), the localStorage state is kept.

### Price sources

| Asset | Price ticker | FX ticker |
|---|---|---|
| Czech/Prague stocks (`.PR`) | Yahoo Finance direct | — |
| Gold (Revolut XAU) | `GC=F` (USD/oz) | `USDCZK=X` |
| Xetra-Gold (4GLD.DE) | `4GLD.DE` (EUR) | `EURCZK=X` |
| iShares MSCI World ex USA (EXUS.DE) | `EXUS.DE` (EUR) | `EURCZK=X` |
| onemarkets funds (LU ISINs) | none — manual price only | — |
| FIO Global Fond (FIOG.PR) | none — manual price only | — |

### Responsive breakpoints (`src/App.css`)

| Breakpoint | Columns hidden | Columns visible |
|---|---|---|
| ≤ 960 px (tablet) | Avg Buy, First Buy, Lots, Cost Basis, Dividends, IRR | Ticker, Type, Qty, Cur. Price, Cur. Value, P&L, Total Return, Return % |
| ≤ 640 px (mobile) | + Type, Cur. Price, Total Return | Ticker, Qty, Cur. Value, P&L, Return % |
| ≤ 400 px (small) | + Qty | Ticker, Cur. Value, P&L, Return % |

### Vite proxy

Two proxy rules in `vite.config.ts`:
- `/api/yahoo/*` → `https://query1.finance.yahoo.com` with a browser-like `User-Agent` (avoids Yahoo rejecting requests)
- `/api/persist/*` → `http://localhost:3001` (persist server)

Both only active during `npm run dev`. A production deployment needs a real backend for both.

### Styling

Single flat CSS file (`src/App.css`) with CSS custom properties on `:root`. Dark theme (`--bg: #0f0f1a`), gain/loss via `--gain` / `--loss`. Full-width layout — no `max-width` cap on the main container.

## Notes

- Your portfolio data is stored in `server/data.json` on disk — it is excluded from git via `.gitignore`.
- For unlisted funds (UniCredit onemarkets, FIO Global), enter the total position value from your bank report in the **Cur. Value** column. Click the orange **M** badge to update; **×** to clear.
- The persist server must be running (`npm run dev`) for changes to be saved to disk. If it is unreachable, data is saved to localStorage only.
- To record a sold position, click **+ Add Position**, fill in the buy details, check **Closed position (already sold)**, and enter the sell date and sell price.
