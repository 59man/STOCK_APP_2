import { useMemo, useState } from 'react'
import { PieChart, Pie, Cell, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { PortfolioRow } from '../types'

interface Props {
  rows: PortfolioRow[]
  displayCurrency: string
  convert: (amount: number, from: string, to: string) => number
}

type GroupBy = 'type' | 'ticker'

interface PieEntry {
  name: string
  value: number
  isLoss: boolean
  color: string
}

const TYPE_COLORS: Record<string, string> = {
  stock:     '#4f8ef7',
  etf:       '#50c878',
  fund:      '#c97ff5',
  commodity: '#f5c842',
}

const TYPE_LABELS: Record<string, string> = {
  stock:     'Stocks',
  etf:       'ETFs',
  fund:      'Funds',
  commodity: 'Commodities',
}

const PALETTE = [
  '#e63946', '#2ec4b6', '#ff9f1c', '#4361ee', '#57cc99',
  '#f72585', '#4cc9f0', '#f4a261', '#7b2d8b', '#b5e48c',
  '#ffd166', '#ef476f', '#06d6a0', '#118ab2', '#ff6b6b',
  '#9b5de5', '#00bbf9', '#fee440', '#fb5607', '#3a86ff',
  '#8338ec', '#ffbe0b', '#f15bb5', '#00f5d4',
]

const LOSS_COLOR = '#e05555'

function fmtCurrency(value: number, currency: string): string {
  return new Intl.NumberFormat('cs-CZ', {
    style: 'currency',
    currency,
    maximumFractionDigits: 0,
  }).format(value)
}

// recharts clones the Tooltip element and injects active/payload
function PieTooltip({ active, payload, displayCurrency }: {
  active?: boolean
  payload?: Array<{ value: number; payload: PieEntry }>
  displayCurrency: string
}) {
  if (!active || !payload?.length) return null
  const { isLoss, name } = payload[0].payload
  const displayValue = isLoss ? -payload[0].value : payload[0].value
  return (
    <div className="pie-tooltip">
      <div className="pie-tooltip-name">{name}</div>
      <div className={isLoss ? 'loss' : 'gain'}>{fmtCurrency(displayValue, displayCurrency)}</div>
    </div>
  )
}

interface ChartCardProps {
  data: PieEntry[]
  title: string
  displayCurrency: string
  emptyLabel?: string
}

const RADIAN = Math.PI / 180

function InsideLabel({ cx, cy, midAngle, outerRadius, percent }: {
  cx: number; cy: number; midAngle: number; outerRadius: number; percent: number
}) {
  if (percent < 0.05) return null
  const r = outerRadius * 0.65
  const x = cx + r * Math.cos(-midAngle * RADIAN)
  const y = cy + r * Math.sin(-midAngle * RADIAN)
  return (
    <text x={x} y={y} fill="#fff" textAnchor="middle" dominantBaseline="central"
      fontSize={11} fontWeight={700} style={{ pointerEvents: 'none', textShadow: '0 1px 2px rgba(0,0,0,0.6)' }}>
      {`${(percent * 100).toFixed(0)}%`}
    </text>
  )
}

function PieChartCard({ data, title, displayCurrency, emptyLabel = 'No data' }: ChartCardProps) {
  const nonZero = data.filter((d) => d.value > 0)
  return (
    <div className="pie-chart-card">
      <div className="pie-chart-title">{title}</div>
      {nonZero.length === 0 ? (
        <div className="pie-empty">{emptyLabel}</div>
      ) : (
        <ResponsiveContainer width="100%" height={300}>
          <PieChart>
            <Pie
              data={nonZero}
              cx="50%"
              cy="44%"
              outerRadius={95}
              dataKey="value"
              nameKey="name"
              paddingAngle={1}
              labelLine={false}
              label={(props) => <InsideLabel {...props} />}
            >
              {nonZero.map((entry, i) => (
                <Cell key={i} fill={entry.color} stroke="var(--surface)" strokeWidth={2} />
              ))}
            </Pie>
            <Tooltip content={<PieTooltip displayCurrency={displayCurrency} />} />
            <Legend
              iconType="circle"
              iconSize={8}
              wrapperStyle={{ fontSize: 12, paddingTop: 4 }}
              formatter={(value: string) => <span style={{ color: 'var(--text-2)' }}>{value}</span>}
            />
          </PieChart>
        </ResponsiveContainer>
      )}
    </div>
  )
}

export function PortfolioPieCharts({ rows, displayCurrency, convert }: Props) {
  const [groupBy, setGroupBy] = useState<GroupBy>('type')

  // Stable color assignment so same ticker gets same color across all 3 charts
  const tickerColors = useMemo(
    () => Object.fromEntries(rows.map((row, i) => [row.ticker, PALETTE[i % PALETTE.length]])),
    [rows],
  )

  const charts = useMemo(() => {
    const cv = (amount: number, currency: string) => convert(amount, currency, displayCurrency)

    if (groupBy === 'type') {
      const agg = new Map<string, { costBasis: number; currentValue: number; totalReturn: number }>()
      rows.forEach((row) => {
        const prev = agg.get(row.type) ?? { costBasis: 0, currentValue: 0, totalReturn: 0 }
        agg.set(row.type, {
          costBasis:    prev.costBasis    + cv(row.costBasis,    row.currency),
          currentValue: prev.currentValue + cv(row.currentValue, row.currency),
          totalReturn:  prev.totalReturn  + cv(row.totalReturn,  row.currency),
        })
      })

      const types = Array.from(agg.entries())
      return {
        costBasis: types.map(([type, v]) => ({
          name: TYPE_LABELS[type] ?? type,
          value: v.costBasis,
          isLoss: false,
          color: TYPE_COLORS[type] ?? '#888',
        })),
        currentValue: types.map(([type, v]) => ({
          name: TYPE_LABELS[type] ?? type,
          value: v.currentValue,
          isLoss: false,
          color: TYPE_COLORS[type] ?? '#888',
        })),
        totalReturn: types.map(([type, v]) => ({
          name: v.totalReturn < 0 ? `${TYPE_LABELS[type] ?? type} (loss)` : (TYPE_LABELS[type] ?? type),
          value: Math.abs(v.totalReturn),
          isLoss: v.totalReturn < 0,
          color: v.totalReturn < 0 ? LOSS_COLOR : (TYPE_COLORS[type] ?? '#888'),
        })),
      }
    }

    // By ticker
    return {
      costBasis: rows.map((row) => ({
        name: row.ticker,
        value: cv(row.costBasis, row.currency),
        isLoss: false,
        color: tickerColors[row.ticker] ?? '#888',
      })),
      currentValue: rows.map((row) => ({
        name: row.ticker,
        value: cv(row.currentValue, row.currency),
        isLoss: false,
        color: tickerColors[row.ticker] ?? '#888',
      })),
      totalReturn: rows.map((row) => {
        const val = cv(row.totalReturn, row.currency)
        return {
          name: val < 0 ? `${row.ticker} (loss)` : row.ticker,
          value: Math.abs(val),
          isLoss: val < 0,
          color: val < 0 ? LOSS_COLOR : (tickerColors[row.ticker] ?? '#888'),
        }
      }),
    }
  }, [rows, groupBy, displayCurrency, convert, tickerColors])

  if (rows.length === 0) return null

  return (
    <div className="pie-charts-section">
      <div className="pie-charts-header">
        <h3 className="pie-charts-title">Portfolio Distribution</h3>
        <div className="pie-group-toggle">
          <button
            className={`pie-group-btn${groupBy === 'type' ? ' active' : ''}`}
            onClick={() => setGroupBy('type')}
          >By Type</button>
          <button
            className={`pie-group-btn${groupBy === 'ticker' ? ' active' : ''}`}
            onClick={() => setGroupBy('ticker')}
          >By Ticker</button>
        </div>
      </div>
      <div className="pie-charts-grid">
        <PieChartCard
          data={charts.costBasis}
          title="Cost Basis"
          displayCurrency={displayCurrency}
        />
        <PieChartCard
          data={charts.currentValue}
          title="Current Value"
          displayCurrency={displayCurrency}
          emptyLabel="No open positions"
        />
        <PieChartCard
          data={charts.totalReturn}
          title="Total Return incl. Dividends"
          displayCurrency={displayCurrency}
          emptyLabel="No returns yet"
        />
      </div>
    </div>
  )
}
