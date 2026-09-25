export interface Position {
  id: string
  ticker: string
  name: string
  type: 'stock' | 'etf' | 'fund' | 'commodity' | 'crypto'
  quantity: number
  buyPrice: number
  buyDate: string
  currency: string
  broker?: string
  isin?: string
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
  nativeCurrency: string  // currency the instrument itself trades in (e.g. EUR for 4GLD.DE), regardless of lot-recorded currency
  lots: number           // number of separate purchase lots
  positions: Position[]  // individual lots, sorted by buyDate
  totalQuantity: number
  /** Shares still held (open lots). totalQuantity also counts sold lots. */
  openQuantity: number
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
  dailyChange: number         // price-only change in row currency, kept for existing consumers
  /** Headline today's change in the DISPLAY currency, anchored to the user's local midnight and
   *  including the currency's own move. */
  dailyChangeDisplay: number
  dailyChangePercent: number
  /** Broker-style figure: price move only, FX held at today's rate, in the display currency. */
  dailyPriceOnlyDisplay: number
  /** Which rule produced the number — the UI marks a fallback and explains a zero. */
  dailyChangeMethod: 'anchored' | 'noTradeToday' | 'prevCloseFallback'
  /** Epoch seconds of the last trade, for the "closed since Fri" hint. */
  lastTradedAt: number | null
}
