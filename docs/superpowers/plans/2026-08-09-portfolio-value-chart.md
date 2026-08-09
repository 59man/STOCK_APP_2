# Portfolio Value Chart (Cost Basis + Current Value) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Portfolio Value" view to the existing Total Return chart, showing Cost Basis and Current Value of open positions as two time-series lines, toggled via the chart header.

**Architecture:** Single-file extension of `src/components/PortfolioPnLChart.tsx`. The existing per-date computation loop (which already walks every position for every date to compute price P&L) gains two more accumulators computed in the same pass — no new data fetching, no new files. A view-mode toggle (`'return' | 'value'`) switches which Recharts chart renders from the same underlying `chartData` array.

**Tech Stack:** React 18, TypeScript, Recharts (`AreaChart`/`LineChart`/`Line`/`Legend` — all already a project dependency), `localStorage` for view-mode persistence (same pattern as the existing range-tab persistence).

**Design doc:** `docs/superpowers/specs/2026-08-09-portfolio-value-chart-design.md`

## Global Constraints

- No new files — this is an in-place extension of `PortfolioPnLChart.tsx`.
- No new network requests — reuse the existing `effectiveHistories`/`fxHistories`/`convertAt` infrastructure already fetched for the Total Return view.
- Reuse existing CSS classes for the toggle UI (`.pie-group-toggle` / `.pie-group-btn` from `App.css`, already shared by the Add Position modal's price-mode switch) — no new CSS.
- Cost Basis and Current Value are computed **only for lots open as of each chart date** (`pos.buyDate <= date && (!pos.sellDate || pos.sellDate > date)`) — both drop to 0 for a lot once it's sold. This is not a cumulative-ever-invested figure.
- `costBasis` converts at the lot's **buy-date** FX rate (frozen); `currentValue` converts at **that date's** FX rate — see design doc "Per-date computation" section for the exact formula.

---

### Task 1: Cost Basis / Current Value time series + Portfolio Value view

**Files:**
- Modify: `src/components/PortfolioPnLChart.tsx`
- Modify: `CLAUDE.md` (update the `PortfolioPnLChart` bullet in the Components section)

**Interfaces:**
- Consumes: existing `effectiveHistories: Map<string, TickerHistory>`, `fxHistories: Map<string, TickerHistory>`, `priceAt()`, `histCurrency()`, `convert()` prop — all already defined earlier in this same file, unchanged.
- Produces: `ChartPoint` gains `costBasis: number` and `currentValue: number` fields, populated on every point regardless of active view (cheap to compute, avoids conditional logic branching inside the memo).

- [ ] **Step 1: Extend `ChartPoint` with the two new fields**

In `src/components/PortfolioPnLChart.tsx`, replace:

```ts
interface ChartPoint {
  label: string
  pnl: number
}
```

with:

```ts
interface ChartPoint {
  label: string
  pnl: number
  costBasis: number
  currentValue: number
}

type PnlView = 'return' | 'value'
```

- [ ] **Step 2: Add view-mode state, persisted the same way as the range tabs**

Directly below the existing range state block:

```ts
  const [range, setRange] = useState<Range>(
    () => (localStorage.getItem('chart_range_portfolio') as Range | null) ?? 'All'
  )

  const handleRangeChange = (r: Range) => {
    setRange(r)
    localStorage.setItem('chart_range_portfolio', r)
  }
```

add:

```ts
  const [view, setView] = useState<PnlView>(
    () => (localStorage.getItem('chart_view_portfolio') as PnlView | null) ?? 'return'
  )

  const handleViewChange = (v: PnlView) => {
    setView(v)
    localStorage.setItem('chart_view_portfolio', v)
  }
```

- [ ] **Step 3: Compute `costBasis` and `currentValue` in the existing per-date loop**

Inside the `chartData` useMemo, the price P&L block currently reads:

```ts
      let pricePnl = 0
      positions.forEach((pos) => {
        if (pos.buyDate > date) return

        // If this lot was sold on or before this date, use the frozen realized gain
        // so the chart matches the table's realizedPnl (not live market price).
        if (pos.sellDate && pos.sellDate <= date && pos.sellPrice != null) {
          pricePnl += convertAt((pos.sellPrice - pos.buyPrice) * pos.quantity, pos.currency, displayCurrency, pos.sellDate)
          return
        }

        const h = effectiveHistories.get(pos.ticker)
        if (!h || h.length === 0) return
        const price = priceAt(h, date)
        if (price === null) return
        const hCurrency = histCurrency(pos.ticker, pos.currency)
        // Normalise buy price to history currency at the buy date's rate — that is
        // the actual exchange the trade settled at (e.g. EUR paid for JPY shares).
        const buyInHistCurrency = convertAt(pos.buyPrice, pos.currency, hCurrency, pos.buyDate)
        pricePnl += convertAt((price - buyInHistCurrency) * pos.quantity, hCurrency, displayCurrency, date)
      })
```

Replace it with (adds `costBasis`/`currentValue` accumulation to the same loop — sold lots fall into the early-return branch above and correctly contribute 0 to both):

```ts
      let pricePnl = 0
      let costBasis = 0
      let currentValue = 0
      positions.forEach((pos) => {
        if (pos.buyDate > date) return

        // If this lot was sold on or before this date, use the frozen realized gain
        // so the chart matches the table's realizedPnl (not live market price).
        // Sold lots contribute 0 to costBasis/currentValue — those track capital
        // currently deployed in open positions, not lifetime invested capital.
        if (pos.sellDate && pos.sellDate <= date && pos.sellPrice != null) {
          pricePnl += convertAt((pos.sellPrice - pos.buyPrice) * pos.quantity, pos.currency, displayCurrency, pos.sellDate)
          return
        }

        const h = effectiveHistories.get(pos.ticker)
        if (!h || h.length === 0) return
        const price = priceAt(h, date)
        if (price === null) return
        const hCurrency = histCurrency(pos.ticker, pos.currency)
        // Normalise buy price to history currency at the buy date's rate — that is
        // the actual exchange the trade settled at (e.g. EUR paid for JPY shares).
        const buyInHistCurrency = convertAt(pos.buyPrice, pos.currency, hCurrency, pos.buyDate)
        pricePnl += convertAt((price - buyInHistCurrency) * pos.quantity, hCurrency, displayCurrency, date)

        // costBasis freezes at the buy-date FX rate ("what was actually paid");
        // currentValue floats at this date's FX rate — mirrors the price leg above.
        costBasis += convertAt(pos.buyPrice * pos.quantity, pos.currency, displayCurrency, pos.buyDate)
        currentValue += convertAt(price * pos.quantity, hCurrency, displayCurrency, date)
      })
```

Then update the point object returned at the end of the same `.map()`, from:

```ts
      return {
        label: new Date(date).toLocaleDateString('en-US', {
          month: 'short', day: 'numeric',
          year: (range === 'All' || range === '5Y' || range === '3Y') ? '2-digit' : undefined,
        }),
        pnl: Math.round(pricePnl + divPnl),
      }
```

to:

```ts
      return {
        label: new Date(date).toLocaleDateString('en-US', {
          month: 'short', day: 'numeric',
          year: (range === 'All' || range === '5Y' || range === '3Y') ? '2-digit' : undefined,
        }),
        pnl: Math.round(pricePnl + divPnl),
        costBasis: Math.round(costBasis),
        currentValue: Math.round(currentValue),
      }
```

- [ ] **Step 4: Add a Y-axis domain helper for the value view**

Directly below the existing:

```ts
  const values = chartData.map((d) => d.pnl)
  const minVal = values.length ? Math.min(...values) : 0
  const maxVal = values.length ? Math.max(...values) : 0
  const finalPnl = values[values.length - 1] ?? 0
  const color = finalPnl >= 0 ? '#22c55e' : '#ef4444'
  const pad = Math.max(Math.abs(maxVal), Math.abs(minVal)) * 0.08 || 1000
```

add:

```ts
  const valueMax = chartData.length
    ? Math.max(...chartData.map((d) => Math.max(d.costBasis, d.currentValue)))
    : 0
  const valuePad = valueMax * 0.08 || 1000
```

- [ ] **Step 5: Manually verify the computation before wiring up rendering**

Run `npm run dev`, open a portfolio with at least one open and one closed position. Temporarily add `console.log(chartData[chartData.length - 1])` right after the `chartData` useMemo and check the browser console: the last point's `currentValue` should be close to the sum of the table's "Cur. Value" column across open rows (converted to display currency), and `costBasis` should be close to the sum of "Cost Basis" for open rows only (the table's own Cost Basis column includes closed lots too, per `PortfolioContent.tsx:150`, so expect the chart's figure to be somewhat lower — that's correct, not a bug). Remove the `console.log` once confirmed.

- [ ] **Step 6: Add `Line`, `LineChart`, `Legend` to the Recharts import**

Replace:

```ts
import {
  AreaChart, Area, XAxis, YAxis,
  CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer,
} from 'recharts'
```

with:

```ts
import {
  AreaChart, Area, LineChart, Line, Legend, XAxis, YAxis,
  CartesianGrid, Tooltip, ReferenceLine, ResponsiveContainer,
} from 'recharts'
```

- [ ] **Step 7: Switch the header title/subtitle on `view`, gate the P&L summary figure to the return view, and add the toggle buttons**

Replace:

```tsx
      <div className="chart-header">
        <div>
          <h3>Portfolio Total Return</h3>
          <span style={{ fontSize: 10, color: '#666' }}>price P&L + net dividends (after withholding tax)</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {!loading && values.length > 0 && (
            <span className={finalPnl >= 0 ? 'gain' : 'loss'} style={{ fontSize: 13, fontWeight: 600 }}>
              {fmtCurrency(finalPnl, displayCurrency)}
            </span>
          )}
          <div className="range-tabs">
```

with:

```tsx
      <div className="chart-header">
        <div>
          <h3>{view === 'return' ? 'Portfolio Total Return' : 'Portfolio Value'}</h3>
          <span style={{ fontSize: 10, color: '#666' }}>
            {view === 'return'
              ? 'price P&L + net dividends (after withholding tax)'
              : 'capital in open positions vs. mark-to-market value'}
          </span>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          {view === 'return' && !loading && values.length > 0 && (
            <span className={finalPnl >= 0 ? 'gain' : 'loss'} style={{ fontSize: 13, fontWeight: 600 }}>
              {fmtCurrency(finalPnl, displayCurrency)}
            </span>
          )}
          <div className="pie-group-toggle">
            <button
              className={`pie-group-btn${view === 'return' ? ' active' : ''}`}
              onClick={() => handleViewChange('return')}
            >
              Total Return
            </button>
            <button
              className={`pie-group-btn${view === 'value' ? ' active' : ''}`}
              onClick={() => handleViewChange('value')}
            >
              Portfolio Value
            </button>
          </div>
          <div className="range-tabs">
```

- [ ] **Step 8: Render the Portfolio Value chart when `view === 'value'`**

The existing chart body is:

```tsx
      {loading && <div className="chart-placeholder">Loading portfolio history…</div>}
      {!loading && error && <div className="chart-placeholder error-text">History error: {error}</div>}
      {!loading && !error && chartData.length === 0 && <div className="chart-placeholder">No data for this range.</div>}
      {!loading && !error && chartData.length > 0 && (
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <defs>
              <linearGradient id="pnlGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor={color} stopOpacity={0.3} />
                <stop offset="95%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: '#888' }}
              interval={Math.floor(chartData.length / 7)}
              tickLine={false}
            />
            <YAxis
              domain={[minVal - pad, maxVal + pad]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) =>
                Math.abs(v) >= 1000
                  ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
                  : parseFloat(v.toFixed(2)).toString()
              }
              width={42}
            />
            <ReferenceLine y={0} stroke="#555" strokeDasharray="4 4" />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number) => [fmtCurrency(v, displayCurrency), 'Total Return']}
            />
            <Area
              type="monotone"
              dataKey="pnl"
              stroke={color}
              strokeWidth={2}
              fill="url(#pnlGrad)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
```

Replace the final block (`{!loading && !error && chartData.length > 0 && ( ... )}`) so it branches on `view`:

```tsx
      {!loading && !error && chartData.length > 0 && view === 'return' && (
        <ResponsiveContainer width="100%" height={260}>
          <AreaChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <defs>
              <linearGradient id="pnlGrad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="5%"  stopColor={color} stopOpacity={0.3} />
                <stop offset="95%" stopColor={color} stopOpacity={0} />
              </linearGradient>
            </defs>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: '#888' }}
              interval={Math.floor(chartData.length / 7)}
              tickLine={false}
            />
            <YAxis
              domain={[minVal - pad, maxVal + pad]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) =>
                Math.abs(v) >= 1000
                  ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
                  : parseFloat(v.toFixed(2)).toString()
              }
              width={42}
            />
            <ReferenceLine y={0} stroke="#555" strokeDasharray="4 4" />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number) => [fmtCurrency(v, displayCurrency), 'Total Return']}
            />
            <Area
              type="monotone"
              dataKey="pnl"
              stroke={color}
              strokeWidth={2}
              fill="url(#pnlGrad)"
              dot={false}
            />
          </AreaChart>
        </ResponsiveContainer>
      )}
      {!loading && !error && chartData.length > 0 && view === 'value' && (
        <ResponsiveContainer width="100%" height={260}>
          <LineChart data={chartData} margin={{ top: 8, right: 8, left: 8, bottom: 0 }}>
            <CartesianGrid strokeDasharray="3 3" stroke="#2a2a3a" />
            <XAxis
              dataKey="label"
              tick={{ fontSize: 10, fill: '#888' }}
              interval={Math.floor(chartData.length / 7)}
              tickLine={false}
            />
            <YAxis
              domain={[0, valueMax + valuePad]}
              tick={{ fontSize: 10, fill: '#888' }}
              tickLine={false}
              axisLine={false}
              tickFormatter={(v) =>
                Math.abs(v) >= 1000
                  ? `${(v / 1000).toFixed(1).replace(/\.0$/, '')}k`
                  : parseFloat(v.toFixed(2)).toString()
              }
              width={42}
            />
            <Tooltip
              contentStyle={{ background: '#1e1e2e', border: '1px solid #333', borderRadius: 6 }}
              labelStyle={{ color: '#aaa' }}
              formatter={(v: number, name: string) => [fmtCurrency(v, displayCurrency), name]}
            />
            <Legend wrapperStyle={{ fontSize: 11 }} />
            <Line type="monotone" dataKey="costBasis" name="Cost Basis" stroke="#64748b" strokeWidth={2} dot={false} />
            <Line type="monotone" dataKey="currentValue" name="Current Value" stroke="#3b82f6" strokeWidth={2} dot={false} />
          </LineChart>
        </ResponsiveContainer>
      )}
```

- [ ] **Step 9: Run the build to catch type errors**

Run: `npm run build`
Expected: succeeds with no TypeScript errors (this project has no separate `tsc --noEmit` script — `npm run build` type-checks as part of the Vite build).

- [ ] **Step 10: Manually verify in the browser**

With `npm run dev` running, open a portfolio and confirm:
- The chart header shows a "Total Return" / "Portfolio Value" toggle next to the range tabs.
- Clicking "Portfolio Value" swaps the title/subtitle and renders two lines (grey "Cost Basis", blue "Current Value") with a legend.
- Hovering shows a tooltip with both values formatted as currency.
- The Y-axis starts at 0 (no negative region) in the Value view.
- Switching range tabs (1M/3M/.../All) updates both views correctly.
- Reload the page — the last-selected view persists (stored in `localStorage` under `chart_view_portfolio`).
- If the portfolio has a sold position, confirm Cost Basis visibly steps down around its sell date rather than continuing to climb.
- Switch back to "Total Return" — confirm it renders exactly as before (unchanged).

- [ ] **Step 11: Update `CLAUDE.md`**

In the `### Components` section, find the `PortfolioPnLChart` bullet (currently starts with `- **PortfolioPnLChart** — portfolio total return chart...`). Append a new sentence at the end of that bullet describing the new view toggle:

```text
 A header toggle switches between **Total Return** (existing price P&L + dividends area chart) and **Portfolio Value** (two-line chart: Cost Basis vs. Current Value of currently-open lots — both drop to 0 for a lot once sold, so this is capital-at-risk, not lifetime-invested capital); selected view persisted to `localStorage` key `chart_view_portfolio`. Cost Basis converts at each lot's buy-date FX rate (frozen); Current Value converts at each chart date's own FX rate, mirroring the price leg of the Total Return calculation.
```

- [ ] **Step 12: Commit**

```bash
git add src/components/PortfolioPnLChart.tsx CLAUDE.md
git commit -m "$(cat <<'EOF'
feat: add Portfolio Value view (cost basis + current value) to PnL chart

Toggle between Total Return and a two-line Cost Basis / Current Value
chart, reusing the existing history/FX fetching so no extra requests
are made. Cost basis freezes at each lot's buy-date FX rate; current
value floats at each date's rate. Both drop to 0 once a lot is sold.

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review Notes

- **Spec coverage:** cost basis time series ✓ (Step 3, frozen buy-date rate, drops on sell per user's confirmed choice), current value time series ✓ (Step 3), same-chart toggle ✓ (Steps 2, 7), reused `.pie-group-btn` styling ✓ (Step 7), Y-axis starts at 0 for value view ✓ (Step 4, Step 8), two plain lines with legend, no shading ✓ (Step 8), no new network calls ✓ (Step 3 reuses `effectiveHistories`/`convertAt` already fetched), range tabs apply to both views ✓ (both branches read the same `chartData`).
- **Placeholder scan:** none — every step has literal before/after code.
- **Type consistency:** `ChartPoint.costBasis`/`currentValue` (Step 1) match the fields populated in the `.map()` return (Step 3) and the `dataKey`s referenced in the `Line` components (Step 8). `PnlView` type (Step 1) matches `view`/`setView`/`handleViewChange` usage (Steps 2, 7, 8).
