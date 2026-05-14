# Session Notes — 2026-05-14

## What was built

### Persistent file-based storage (replaces localStorage-only)

Portfolio data no longer lives exclusively in the browser. A lightweight Express server now persists everything to `server/data.json` on disk, so data survives browser clears, profile switches, and localhost restarts.

---

## New files

### `server/index.js`
Express server on port 3001. Uses ESM syntax (`import`/`export`) to match the project's `"type": "module"` in `package.json`.

Two endpoints:
- `GET /api/persist/:key` — reads `server/data.json`, returns `{ value: "..." | null }`
- `POST /api/persist/:key` — merges `{ value: "..." }` into `server/data.json`

Data file: `server/data.json` (excluded from git via `.gitignore`).

### `src/utils/storage.ts`
Async `getItem(key)` / `setItem(key, value)` utility. Calls the persist server first; falls back to `localStorage` silently if the server is unreachable. All hooks use this instead of calling `localStorage` directly.

### `.gitignore`
```
node_modules/
dist/
server/data.json
```

---

## Modified files

### `package.json`
- Added `express ^4.19.2` to `dependencies`
- Added `concurrently ^8.2.2` to `devDependencies`
- `"dev"` script changed from `"vite"` to `"concurrently --kill-others-on-fail \"vite\" \"node server/index.js\""` — both processes start together and either dying kills the other

### `vite.config.ts`
New proxy rule added above the existing Yahoo Finance rule:
```ts
'/api/persist': {
  target: 'http://localhost:3001',
  changeOrigin: false,
},
```

### `src/hooks/usePortfolio.ts`
Replaced single sync `load()` + `save()` pattern with a two-phase init:

1. **Sync** (instant, no flash): `syncLoad()` reads `localStorage`, applies seed migration, returns initial state for `useState`.
2. **Async** (on mount): `getItem(STORAGE_KEY)` fetches from server. If server has data, it wins and `setPositions` is called with migrated result. If server returns `null` (first run or unavailable), the sync state is kept unchanged. Either way, `setInitialized(true)` fires.
3. **Save effect**: runs only when `initialized === true`. Calls `setItem(STORAGE_KEY, json)` (writes to server + localStorage) on every `positions` change.

Seed migration extracted to pure `applyMigration(existing, seeded, version)` — used by both sync and async paths.

### `src/hooks/useManualPrices.ts`
Same two-phase init + dual-persist pattern as `usePortfolio`. Removed the old inline `load()` / `persist()` helpers; now uses `getItem` / `setItem` from `src/utils/storage.ts`. The `setPrice` and `removePrice` mutation functions are unchanged — they update React state only; the save effect handles persistence.

---

## Storage keys

| localStorage / server key | Content |
|---|---|
| `stock_tracker_positions` | `Position[]` JSON array |
| `stock_tracker_manual_prices` | `Record<TICKER, { price, updatedAt }>` JSON object |
| `stock_tracker_seeded` | `"1"` after first seed (localStorage only, not server) |
| `stock_tracker_seed_version` | Current seed version string (localStorage only) |

---

## How to run

```bash
npm install       # first time only — installs express + concurrently
npm run dev       # starts Vite on :5173 and persist server on :3001
```

To inspect or back up your data: `cat server/data.json`

To reset all data: delete `server/data.json` and clear `localStorage` in the browser dev tools.
