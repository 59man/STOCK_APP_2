# Sortable Table + Mobile Responsiveness Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Add click-to-sort headers to the main portfolio table, and fix four concrete mobile-layout bugs (page-level horizontal scroll from an overflowing chart range selector, an orphaned summary card, stale desktop column config leaking onto mobile, and a wrapping header title).

**Architecture:** All sort logic and header wiring lives in `src/components/PortfolioTable.tsx` (no other component touches row order or column visibility). The four mobile fixes are CSS-only in `src/App.css` except the column-visibility ceiling, which is a small filter change in the same `PortfolioTable.tsx`.

**Tech Stack:** React 18 + TypeScript, plain CSS (no CSS-in-JS/Tailwind), `localStorage` for client-side persistence (existing pattern — see `COL_STORAGE_KEY` in `PortfolioTable.tsx`).

## Global Constraints

- No new npm dependencies.
- New localStorage keys follow the existing `stock_tracker_*` naming convention used by `COL_STORAGE_KEY` (`stock_tracker_column_config`).
- This component has no automated test coverage today (`npm test` only covers `src/utils/money.test.ts`) — verification is manual, via the running dev server (`npm run dev`, http://localhost:5173) and Chrome DevTools' device toolbar for the mobile tasks. Do not add a test framework or new test files as part of this plan.
- Match existing code style in `PortfolioTable.tsx`: 2-space indent, no semicolon-heavy style beyond what's already there, `try {} catch {}` (no logging) for localStorage read/write helpers, exactly like `loadColConfig`/`saveColConfig`.
- Reference spec: `docs/superpowers/specs/2026-08-14-sortable-table-and-mobile-fixes-design.md`.

---

## Task 1: Sortable table — click-to-sort headers

**Files:**
- Modify: `src/components/PortfolioTable.tsx:1` (import), `:95-124` (add sort storage helpers after `saveColConfig`), `:230-232` (add sort state), `:257-274` region (add `handleHeaderClick`), `:405` region (add `sortValue` + `sortedRows`), `:526-537` (thead), `:539` (tbody source)
- Modify: `src/App.css` (add `.th-sortable` near existing `th`/`td` rules, ~line 538)

**Interfaces:**
- Produces: `type SortDir = 'asc' | 'desc'`, `interface SortConfig { key: ColKey | 'ticker'; dir: SortDir }`, `sortConfig: SortConfig | null` (component state), `sortedRows: PortfolioRow[]` (derived, used by the row-rendering `.map()` instead of `visibleRows`).
- Consumes: existing `ColKey` type (`PortfolioTable.tsx:49`), `COLUMN_DEFS` (`:58-74`), `PortfolioRow` type (imported from `../types`), `convert`/`displayCurrency` props, `visibleRows` (existing local const at `:403`).

- [x] **Step 1: Add `useMemo` to the React import**

`src/components/PortfolioTable.tsx:1` currently reads:

```ts
import { useState, Fragment, useRef, useEffect } from 'react'
```

Change to:

```ts
import { useState, useMemo, Fragment, useRef, useEffect } from 'react'
```

- [x] **Step 2: Add sort-config types and localStorage helpers**

In `src/components/PortfolioTable.tsx`, immediately after the existing `saveColConfig` function (ends at line 124, right before the `// ── Supporting types ──` comment at line 126), insert:

```ts
// ── Sort config ────────────────────────────────────────────────────────────────
type SortDir = 'asc' | 'desc'
interface SortConfig { key: ColKey | 'ticker'; dir: SortDir }

const SORT_STORAGE_KEY = 'stock_tracker_sort_config'

function loadSortConfig(): SortConfig | null {
  try {
    const raw = localStorage.getItem(SORT_STORAGE_KEY)
    if (!raw) return null
    const parsed = JSON.parse(raw) as SortConfig
    if (parsed && typeof parsed.key === 'string' && (parsed.dir === 'asc' || parsed.dir === 'desc')) return parsed
  } catch {}
  return null
}

function saveSortConfig(cfg: SortConfig | null) {
  try {
    if (cfg) localStorage.setItem(SORT_STORAGE_KEY, JSON.stringify(cfg))
    else localStorage.removeItem(SORT_STORAGE_KEY)
  } catch {}
}
```

- [x] **Step 3: Add `sortConfig` state to the component**

In `src/components/PortfolioTable.tsx`, the component currently has (around line 230-232):

```ts
  // Column config
  const [colConfig, setColConfig] = useState<ColConfig[]>(loadColConfig)
  const [showColPanel, setShowColPanel] = useState(false)
  const colPanelRef = useRef<HTMLDivElement>(null)
```

Add a new state block right after it:

```ts
  // Column config
  const [colConfig, setColConfig] = useState<ColConfig[]>(loadColConfig)
  const [showColPanel, setShowColPanel] = useState(false)
  const colPanelRef = useRef<HTMLDivElement>(null)

  // Sort config
  const [sortConfig, setSortConfig] = useState<SortConfig | null>(loadSortConfig)
```

- [x] **Step 4: Add `handleHeaderClick`**

In `src/components/PortfolioTable.tsx`, find `resetColumns` (ends around line 274, right before `const toggle = (ticker: string) =>` at line 276). Add this new function right after `resetColumns` and before `toggle`:

```ts
  const handleHeaderClick = (key: ColKey | 'ticker') => {
    setSortConfig(prev => {
      const next: SortConfig | null =
        !prev || prev.key !== key ? { key, dir: 'asc' }
        : prev.dir === 'asc' ? { key, dir: 'desc' }
        : null
      saveSortConfig(next)
      return next
    })
  }

  const sortIndicator = (key: ColKey | 'ticker') => {
    if (sortConfig?.key !== key) return null
    return <span className="sort-indicator">{sortConfig.dir === 'asc' ? ' ▲' : ' ▼'}</span>
  }
```

- [x] **Step 5: Add `sortValue` and the `sortedRows` memo**

In `src/components/PortfolioTable.tsx`, find this existing line (around line 405):

```ts
  const cv = (amount: number, currency: string) => convert(amount, currency, displayCurrency)
```

Immediately after it (and before `const totalCost = rows.reduce(...)`), insert:

```ts

  const sortValue = (r: PortfolioRow, key: ColKey | 'ticker'): string | number => {
    switch (key) {
      case 'ticker': return r.ticker
      case 'type': return r.type
      case 'qty': return r.totalQuantity
      case 'avgBuy': return cv(r.avgBuyPrice, r.currency)
      case 'firstBuy': return r.firstBuyDate
      case 'lots': return r.lots
      case 'broker': {
        const brokers = [...new Set(r.positions.map((p) => p.broker).filter(Boolean))]
        return brokers.length === 0 ? '' : brokers.length === 1 ? (brokers[0] as string) : 'Mixed'
      }
      case 'curPrice': return cv(r.currentPrice, r.currency)
      case 'today': return cv(r.dailyChange, r.currency)
      case 'costBasis': return cv(r.costBasis, r.currency)
      case 'curValue': return cv(r.currentValue, r.currency)
      case 'pricePnl': return cv(r.pnl, r.currency)
      case 'dividends': return cv(r.dividendIncome, r.currency)
      case 'totalReturn': return cv(r.totalReturn, r.currency)
      case 'returnPct': return r.costBasis > 0 ? (r.totalReturn / r.costBasis) * 100 : 0
      case 'irr': return r.irr ?? -Infinity
    }
  }

  const sortedRows = useMemo(() => {
    if (!sortConfig) return visibleRows
    const { key, dir } = sortConfig
    const sign = dir === 'asc' ? 1 : -1
    return [...visibleRows].sort((a, b) => {
      const va = sortValue(a, key)
      const vb = sortValue(b, key)
      if (typeof va === 'string' || typeof vb === 'string') {
        return sign * String(va).localeCompare(String(vb))
      }
      return sign * ((va as number) - (vb as number))
    })
  }, [visibleRows, sortConfig, displayCurrency])
```

- [x] **Step 6: Wire the clickable headers**

In `src/components/PortfolioTable.tsx`, the `<thead>` block (lines 526-537) currently reads:

```tsx
          <thead>
            <tr>
              <th></th>
              <th>Ticker</th>
              {activeColumns.map(col => (
                <th key={col.key} className={COL_CLASS[col.key]}>
                  {COLUMN_DEFS.find(d => d.key === col.key)?.label}
                </th>
              ))}
              <th></th>
            </tr>
          </thead>
```

Replace with:

```tsx
          <thead>
            <tr>
              <th></th>
              <th className="th-sortable" onClick={() => handleHeaderClick('ticker')}>
                Ticker{sortIndicator('ticker')}
              </th>
              {activeColumns.map(col => (
                <th
                  key={col.key}
                  className={`${COL_CLASS[col.key]} th-sortable`}
                  onClick={() => handleHeaderClick(col.key)}
                >
                  {COLUMN_DEFS.find(d => d.key === col.key)?.label}{sortIndicator(col.key)}
                </th>
              ))}
              <th></th>
            </tr>
          </thead>
```

- [x] **Step 7: Render rows in sorted order**

In `src/components/PortfolioTable.tsx`, find the row-mapping line (around line 539):

```tsx
            {visibleRows.map((r) => {
```

Change to:

```tsx
            {sortedRows.map((r) => {
```

Do not change any other reference to `visibleRows` in the file (the summary totals above the table intentionally still aggregate over all `rows`, unaffected by sort).

- [x] **Step 8: Add `.th-sortable` CSS**

In `src/App.css`, find this existing block (around line 538):

```css
th:nth-child(2) { text-align: left; }   /* Ticker column */
td {
```

Insert a new rule between them:

```css
th:nth-child(2) { text-align: left; }   /* Ticker column */
.th-sortable { cursor: pointer; user-select: none; }
.th-sortable:hover { background: var(--surface3); color: var(--text-2); }
td {
```

- [x] **Step 9: Verify in the browser**

Run: `npm run dev` (if not already running), then open http://localhost:5173.

Check:
1. Click the "Qty" header — rows reorder ascending by quantity, a small ▲ appears next to "Qty".
2. Click "Qty" again — order reverses, indicator becomes ▼.
3. Click "Qty" a third time — order returns to the original (unsorted) order, indicator disappears.
4. Click "Ticker" — rows sort alphabetically by ticker.
5. Click a money column (e.g. "Cur. Value") — sort by underlying value, not the formatted string (confirm order is numerically correct, not string-lexicographic — e.g. 900 sorts before 1200).
6. Sort by any column, reload the page — the same sort is still applied (check `localStorage.getItem('stock_tracker_sort_config')` in DevTools console shows the expected `{"key":"...","dir":"asc"}` JSON).
7. Toggle "Show closed" — sort order is preserved across the toggle.

- [x] **Step 10: Commit**

```bash
git add src/components/PortfolioTable.tsx src/App.css
git commit -m "$(cat <<'EOF'
feat: add click-to-sort headers to the main portfolio table

Every column header is now clickable: asc -> desc -> unsorted cycle,
with a small arrow indicator and the choice persisted to localStorage.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Mobile fix — chart range-tabs overflow the page

**Files:**
- Modify: `src/App.css:813-817` (`.range-tabs` base rule), `:930` region (add a `≤640px` tweak)

**Interfaces:**
- Consumes: existing `.range-tabs` / `.range-tab` classes (used by both `PortfolioPnLChart.tsx` and `PriceChart.tsx` — no component changes needed, this is pure CSS).

- [x] **Step 1: Add `flex-wrap: wrap` to `.range-tabs`**

In `src/App.css`, find (around line 813-817):

```css
.range-tabs {
  display: inline-flex; gap: 1px;
  background: var(--surface2); border: 1px solid var(--border);
  border-radius: 7px; padding: 2px;
}
```

Change to:

```css
.range-tabs {
  display: inline-flex; flex-wrap: wrap; gap: 1px;
  background: var(--surface2); border: 1px solid var(--border);
  border-radius: 7px; padding: 2px;
}
```

- [x] **Step 2: Shrink range-tab buttons on narrow viewports**

In `src/App.css`, inside the existing `@media (max-width: 640px)` block, find this line (around line 930):

```css
  .detail-container { padding: 12px; }
```

Add a new rule right after it (still inside the same media block):

```css
  .detail-container { padding: 12px; }
  .range-tab { padding: 3px 7px; font-size: 10px; }
```

- [x] **Step 3: Verify with Chrome DevTools device toolbar**

Run: `npm run dev` (if not already running), open http://localhost:5173 in Chrome.

Open DevTools (F12), toggle the device toolbar (Ctrl+Shift+M / Cmd+Shift+M), set a custom viewport width of 390px, reload the page.

Check:
1. The portfolio-level P&L chart's period selector (1M/3M/6M/1Y/3Y/5Y/All) wraps onto a second line instead of running off the right edge.
2. In the DevTools console, run `document.body.scrollWidth <= document.body.clientWidth + 2` — should print `true` (small tolerance for scrollbar rounding; previously this was `false` with a ~55px gap).
3. Expand a table row (click ▶) to reveal its individual `PriceChart` — its range selector also fits within the viewport with no horizontal page scroll.

- [x] **Step 4: Commit**

```bash
git add src/App.css
git commit -m "$(cat <<'EOF'
fix: wrap chart range-tabs instead of overflowing the page on mobile

The 1M/3M/6M/1Y/3Y/5Y/All period selector was a non-wrapping flex row
with no width cap, so on narrow viewports it silently extended past
the viewport edge and dragged the entire page into horizontal scroll.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Mobile fix — orphaned 7th summary card

**Files:**
- Modify: `src/App.css:915` region (inside `@media (max-width: 640px)`)

**Interfaces:**
- Consumes: existing `.summary-grid` / `.summary-card` classes, rendered as a fixed set of 7 cards by `PortfolioTable.tsx` (Total Value, Cost Basis, Today's Change, Price P&L, Net Dividends, Total Return, IRR p.a. — see `PortfolioTable.tsx:421-465`). No component changes.

- [x] **Step 1: Make the last summary card span the full row**

In `src/App.css`, inside the `@media (max-width: 640px)` block, find:

```css
  .summary-grid { grid-template-columns: 1fr 1fr; }
```

Change to:

```css
  .summary-grid { grid-template-columns: 1fr 1fr; }
  .summary-card:last-child { grid-column: 1 / -1; }
```

- [x] **Step 2: Verify with Chrome DevTools device toolbar**

With the device toolbar still set to 390px width (from Task 2), reload http://localhost:5173.

Check: the "IRR p.a." card (the 7th/last summary card) spans the full row width at the bottom of the summary grid — no empty gray placeholder box next to it.

- [x] **Step 3: Commit**

```bash
git add src/App.css
git commit -m "$(cat <<'EOF'
fix: make the last summary card span full width on mobile

7 cards in a 2-column mobile grid left the 7th (IRR p.a.) alone next
to an empty grid cell that rendered as a jarring blank box.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Mobile fix — enforce column-visibility ceiling on narrow viewports

**Files:**
- Modify: `src/components/PortfolioTable.tsx:255` (`activeColumns` computation)

**Interfaces:**
- Consumes: existing `colConfig: ColConfig[]` state, `COLUMN_DEFS: ColumnDef[]` (with `hideBelow?: number`), both already defined in this file.
- Produces: `activeColumns` (same name/shape as before — `ColConfig[]` filtered to visible columns — callers elsewhere in the file are unaffected).

- [x] **Step 1: Enforce `hideBelow` as a ceiling, not just a one-time default**

In `src/components/PortfolioTable.tsx`, find (around line 255):

```ts
  const activeColumns = colConfig.filter(c => c.visible)
```

Change to:

```ts
  const activeColumns = colConfig.filter(c => {
    if (!c.visible) return false
    const def = COLUMN_DEFS.find(d => d.key === c.key)
    return !def?.hideBelow || window.innerWidth > def.hideBelow
  })
```

This mirrors the existing `resetColumns` function (`PortfolioTable.tsx:270-274`), which already reads `window.innerWidth` directly with no resize listener — same pattern, no new state.

- [x] **Step 2: Verify the fix reproduces and resolves the original bug**

Run: `npm run dev` (if not already running), open http://localhost:5173 in Chrome, DevTools open.

First, simulate a "stale desktop config" (a user who customized columns at a wide viewport, then opens the app on a phone) — in the DevTools console, at normal desktop width, run:

```js
localStorage.setItem('stock_tracker_column_config', JSON.stringify([
  {key:'type',visible:true},{key:'qty',visible:true},{key:'avgBuy',visible:true},
  {key:'firstBuy',visible:true},{key:'lots',visible:true},{key:'broker',visible:false},
  {key:'curPrice',visible:true},{key:'today',visible:true},{key:'costBasis',visible:true},
  {key:'curValue',visible:true},{key:'pricePnl',visible:true},{key:'dividends',visible:false},
  {key:'totalReturn',visible:false},{key:'returnPct',visible:true},{key:'irr',visible:true}
]))
```

Then toggle the device toolbar to 390px width and reload.

Check:
1. Columns whose `hideBelow` is below 390 (Type: 640, Qty: 400, Avg Buy/First Buy/Lots/Cur. Price(640)/Today/Cost Basis: 960 or 640) are **not** rendered in the table, even though the stored config marks them `visible: true`.
2. Open the "⚙ Columns" panel — the checkboxes for those columns are still checked (the user's stored preference is remembered, it's just not forced onto a screen too narrow for it).
3. Widen the device toolbar back to desktop width (e.g. 1200px) and reload — those columns reappear (ceiling only applies below the threshold).

- [x] **Step 3: Commit**

```bash
git add src/components/PortfolioTable.tsx
git commit -m "$(cat <<'EOF'
fix: enforce column hideBelow as a ceiling, not just a one-time default

Once a user had customized column visibility even once, the saved
config permanently overrode the width-based responsive defaults on
every future load, including on a narrower device sharing the same
browser profile, cramming the table with too many columns to fit.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Mobile fix — header title wraps to two lines

**Files:**
- Modify: `src/App.css:953-956` (`@media (max-width: 400px)` block)

**Interfaces:**
- Consumes: existing `.header h1`, `.currency-tab`, `.btn-primary` classes (rendered in `App.tsx:176-193`). No component changes.

- [x] **Step 1: Keep the header title on one line at very narrow widths**

In `src/App.css`, find:

```css
@media (max-width: 400px) {
  th { padding: 7px 6px; }
  td { padding: 8px 6px; font-size: 12px; }
}
```

Change to:

```css
@media (max-width: 400px) {
  .header h1 { font-size: 12.5px; white-space: nowrap; }
  .currency-tab { padding: 3px 8px; font-size: 10.5px; }
  .btn-primary { padding: 6px 10px; font-size: 11.5px; }
  th { padding: 7px 6px; }
  td { padding: 8px 6px; font-size: 12px; }
}
```

- [x] **Step 2: Verify with Chrome DevTools device toolbar**

Set the device toolbar to 375px width (iPhone SE — the narrowest common width, well under the 400px breakpoint), reload http://localhost:5173.

Check: "📈 Stock Tracker" stays on one line in the header, without colliding with or wrapping under the currency switcher / "+ Add Position" button.

- [x] **Step 3: Commit**

```bash
git add src/App.css
git commit -m "$(cat <<'EOF'
fix: keep header title on one line on narrow mobile viewports

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
