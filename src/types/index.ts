export interface Position {
  id: string
  ticker: string
  name: string
  type: 'stock' | 'etf' | 'fund' | 'commodity'
  quantity: number
  buyPrice: number
  buyDate: string
  currency: string
  sellPrice?: number
  sellDate?: string
}

export interface Quote {
  ticker: string
  price: number
  change: number
  changePercent: number
  currency: string
  name: string
  lastUpdated: string
}

export interface PortfolioRow {
  ids: string[]          // all lot IDs in this group
  ticker: string
  name: string
  type: Position['type']
  currency: string
  lots: number           // number of separate purchase lots
  positions: Position[]  // individual lots, sorted by buyDate
  totalQuantity: number
  avgBuyPrice: number    // weighted average
  firstBuyDate: string
  currentPrice: number
  currentValue: number
  costBasis: number
  pnl: number           // price-only P&L (currentValue - costBasis)
  pnlPercent: number
  dividendIncome: number // net dividends received (after per-country withholding tax)
  totalReturn: number   // pnl + dividendIncome
  loading: boolean
  error: string | null
  priceIsManual: boolean      // true when price comes from manual override (no live quote)
  manualPriceDate?: string    // YYYY-MM-DD when manual price was last set
  irr: number | null          // annualised XIRR including dividends, null while loading
  isClosed: boolean           // all lots have been sold
  dailyChange: number         // today's absolute P&L change (quote.change × openQty), 0 for closed/manual
}
