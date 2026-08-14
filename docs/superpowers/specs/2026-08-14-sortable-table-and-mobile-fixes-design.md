# Sortable main table + mobile responsiveness fixes

## Problem

Two independent asks:

1. `PortfolioTable` has no way to reorder rows — they always render in the order
   `PortfolioContent`'s `rows` useMemo produces them.
2. The app "overflows sometimes" and feels unresponsive on mobile browsers. Reproduced
   live in a 390px-wide viewport (iframe-emulated Chrome, real DOM measurements) and
   traced to four concrete causes — not a vague "make it more responsive" ask.

These two are unrelated in code (one is `PortfolioTable.tsx` feature work, the other is
CSS/layout bug fixes across a few files) but are being designed and shipped together
since both touch `PortfolioTable.tsx` and the user asked for both in one round.

## Part 1: Sortable main table

### Goals

- Click any column header (including the fixed Ticker column) to sort ascending.
- Click again to reverse to descending. Click a third time to clear back to the
  default (unsorted) row order.
- Small ▲/▼ indicator on whichever column is currently sorted.
- Sort choice (column key + direction) persists across reloads via localStorage, same
  pattern as `stock_tracker_column_config`.
- Applies to every visible column: text columns (Ticker, Type, Broker) sort
  alphabetically, date columns (First Buy) chronologically, numeric/money columns by
  underlying value.

### Non-goals

- No multi-column sort.
- No server-side sort — this is a pure client-side re-order of the already-computed
  `PortfolioRow[]`.
- Sorting does not affect the summary cards (still aggregate over all `rows`,
  unaffected by table sort/filter) or the charts below the table.

### Design

New state in `PortfolioTable.tsx`:

```ts
type SortDir = 'asc' | 'desc'
interface SortConfig { key: ColKey | 'ticker'; dir: SortDir }
const SORT_STORAGE_KEY = 'stock_tracker_sort_config'
const [sortConfig, setSortConfig] = useState<SortConfig | null>(loadSortConfig)
```

`loadSortConfig()` / a `saveSortConfig()` helper mirror the existing
`loadColConfig()` / `saveColConfig()` pair (try/catch localStorage read/write, `null`
on missing/invalid data → unsorted default).

Header click cycle: `null → asc → desc → null` for that column; clicking a different
column while one is active starts that column fresh at `asc`.

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
```

A `sortValue(row, key): string | number` function maps each `ColKey`/`'ticker'` to a
comparable value pulled from the same fields the render switch already reads —
e.g. `qty → r.totalQuantity`, `avgBuy → cv(r.avgBuyPrice, r.currency)`,
`firstBuy → r.firstBuyDate` (ISO strings sort correctly as strings),
`curValue → cv(r.currentValue, r.currency)`, `returnPct → totalReturnPctRow`, etc. —
so money columns always sort by the same `displayCurrency`-converted amount that's
rendered, keeping sort order visually consistent for mixed-currency portfolios.
`type`/`broker` sort by their displayed string (`r.type`, `brokerDisplay ?? ''`).

Sorting is applied once, via `useMemo`, to `visibleRows` (i.e. after the existing
`showClosed` filter, before the `.map()` that renders `<tr>`s):

```ts
const sortedRows = useMemo(() => {
  if (!sortConfig) return visibleRows
  const { key, dir } = sortConfig
  const sign = dir === 'asc' ? 1 : -1
  return [...visibleRows].sort((a, b) => {
    const va = sortValue(a, key), vb = sortValue(b, key)
    if (typeof va === 'string' || typeof vb === 'string') return sign * String(va).localeCompare(String(vb))
    return sign * ((va as number) - (vb as number))
  })
}, [visibleRows, sortConfig, displayCurrency])
```

(`displayCurrency` is a dependency because money-column sort values pass through
`cv()`, which converts to the active display currency.)

### Header rendering

The `<thead>` row (currently static `<th>`s at `PortfolioTable.tsx:527-536`) becomes
clickable for every column, fixed Ticker included:

```tsx
<th onClick={() => handleHeaderClick('ticker')} className="th-sortable">
  Ticker{sortIndicator('ticker')}
</th>
{activeColumns.map(col => (
  <th key={col.key} className={`${COL_CLASS[col.key]} th-sortable`} onClick={() => handleHeaderClick(col.key)}>
    {COLUMN_DEFS.find(d => d.key === col.key)?.label}{sortIndicator(col.key)}
  </th>
))}
```

`sortIndicator(key)` renders `' ▲'` / `' ▼'` (small, muted) when `sortConfig?.key ===
key`, else nothing. New CSS class `.th-sortable { cursor: pointer; user-select: none; }`
plus a `:hover` background tint, added near the existing `th`/`td` rules in `App.css`.

### Testing

No existing test coverage touches `PortfolioTable.tsx` (the `npm test` suite covers
`src/utils/money.test.ts` only — xirr/applyFifo/calcNetDividends). This is pure UI
interaction logic with no non-trivial money math, so no new automated test is added;
verified manually via the running dev server (click each column, confirm order +
persistence across reload).

## Part 2: Mobile responsiveness fixes

Reproduced by loading the app in an iframe sized to 390×844 (iPhone 12/13 width)
inside the user's actual Chrome profile and measuring the real DOM — not simulated
guesswork. Four concrete issues found, all fixed via CSS (no component logic changes
except item 3, which needs a small `PortfolioTable.tsx` change):

### 1. Chart range-tabs overflow the page (primary bug — real page-level horizontal scroll)

**Root cause**: `.range-tabs` (the 1M/3M/6M/1Y/3Y/5Y/All period selector, used by both
`PortfolioPnLChart` and `PriceChart`) is `display: inline-flex` with no wrap and no
width cap. At 390px viewport width, measured directly: `document.body.scrollWidth`
(430px) > `document.body.clientWidth` (375px) — a real, page-wide 55px horizontal
overflow, not contained to any scrollable child. Traced through `.main` (scrollWidth
430 vs width 375) down to `.chart-section` (scrollWidth 417 vs width 351) down to
`.range-tabs` buttons themselves rendering past `right: 430` — buttons for `3Y`/`5Y`/
`All` were pushed off-screen with no way to reach them (not wrapped, not scrollable).
None of the ancestors (`.chart-section`, `.main`) clip overflow-x, so it visibly drags
the *entire page* into horizontal-scroll mode.

**Fix**: add `flex-wrap: wrap` to `.range-tabs` in `App.css` (it already sits inside
`.chart-header`, which has `flex-wrap: wrap` at the *item* level — this extends
wrapping to the buttons *within* range-tabs itself, so on a narrow screen the period
buttons flow onto a second line inside their own pill instead of overflowing). Add a
`≤480px` tweak reducing `.range-tab` padding/font-size slightly so more buttons fit
per line before wrapping.

### 2. Orphaned 7th summary card

**Root cause**: `.summary-grid` has 7 `.summary-card` children (Total Value, Cost
Basis, Today's Change, Price P&L, Net Dividends, Total Return, IRR p.a.). At the
`≤640px` breakpoint the grid switches to `grid-template-columns: 1fr 1fr` — 7 items in
a 2-column grid leaves the last row with one real card (IRR p.a.) and one empty grid
cell, which renders as a blank gray box since `.summary-card` has a background/border
even when empty... except the empty cell has *no* `.summary-card` element at all, it's
just uncovered grid track — visually reads as a jarring empty gap next to the last
card. Confirmed in the live screenshot at 390px.

**Fix**: in the `≤640px` media query, add
`.summary-card:last-child { grid-column: 1 / -1; }` so the 7th (odd-one-out) card
spans the full row width instead of leaving a dangling neighbor cell. This is
positionally safe since the summary cards are a fixed, hardcoded set of 7 in
`PortfolioTable.tsx` (not dynamically generated), so `:last-child` reliably targets
IRR p.a.

### 3. Column visibility ceiling not enforced once a user has customized it

**Root cause**: `loadColConfig()` (`PortfolioTable.tsx:99-120`) only computes
responsive (width-based) defaults for columns *not already present* in the saved
`stock_tracker_column_config`. The first time a user opens the column panel and
toggles/reorders anything, `saveColConfig()` persists the *entire* column list with
its current visibility — permanently. From then on, every future load (any viewport,
any device sharing that browser profile/localStorage) uses that frozen list, and the
`hideBelow` responsive logic never runs again. Verified directly against the user's
real stored config: `Type`, `Qty`, `Avg Buy`, `First Buy`, `Lots`, `Cur. Price`,
`Today`, `Cost Basis` were all `visible: true` despite viewing at 390px — well under
their `hideBelow` thresholds (640/400/960) — cramming the table with far more columns
than fit, forcing heavy horizontal scroll to see anything.

**Fix**: `hideBelow` becomes an enforced ceiling, not just a one-time default.
`activeColumns` (currently `colConfig.filter(c => c.visible)`) becomes:

```ts
const activeColumns = colConfig.filter(c => {
  if (!c.visible) return false
  const def = COLUMN_DEFS.find(d => d.key === c.key)
  return !def?.hideBelow || window.innerWidth > def.hideBelow
})
```

This mirrors the existing `resetColumns()` function (`PortfolioTable.tsx:270-274`),
which already does a plain `window.innerWidth` read with no resize listener — same
pattern, no new state or effect needed. It fixes the actual reported case (loading the
app fresh on a phone with a stale desktop-saved config); reacting to a live desktop
window resize isn't something this bug report asked for, so it's left out per the
existing codebase convention of reading width once per render rather than subscribing
to `resize`.

The column panel checkbox still reflects the user's raw stored preference (`c.visible`,
unchanged) — so their choice is remembered and the checkbox stays checked — it just
won't force a column onto a screen too narrow to reasonably show it. This is the same
rule already used to compute first-visit defaults, now applied continuously instead of
once.

### 4. Header title wraps to two lines (minor polish)

**Root cause**: `.header-inner` is `display: flex; justify-content: space-between`
with no wrap/shrink handling. Between the checkbox+"Stock Tracker" title on the left
and the currency-tabs pill + "+ Add Position" button on the right, there isn't enough
room at ≤400px, so the `h1` text wraps to "Stock" / "Tracker" on two lines.

**Fix**: in the existing `≤400px` media query block, shrink `.header h1` font-size
further (e.g. 12.5px) and reduce `.currency-tab` / Add Position button padding
slightly, with `.header h1 { white-space: nowrap }` so it stays on one line down to
~360px viewports. No layout restructuring.

### Non-goals for Part 2

- No redesign of the mobile table itself — per-column horizontal scroll inside
  `.table-scroll` is existing, intentional, working behavior (confirmed contained,
  does not leak to the page). Only the *page-level* leaks (range-tabs) are bugs.
- No changes to `PriceChart.tsx` / `PortfolioPnLChart.tsx` component logic — both bugs
  fixed here are pure CSS (`.range-tabs` is a shared class already used by both).
- No new mobile-specific component variants or breakpoints beyond what's listed above.

### Testing

CSS/layout fixes — verified by reloading the app in the same 390px iframe-emulation
setup used to find the bugs, confirming `document.body.scrollWidth ===
document.body.clientWidth` (no more page-level horizontal scroll), the summary grid
has no orphaned cell, and a column config with `hideBelow`-violating `visible: true`
entries no longer renders those columns at narrow widths.
