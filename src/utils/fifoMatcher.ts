import { Position } from '../types'

export interface RawLot {
  ticker: string
  name: string
  qty: number
  price: number
  date: string
  currency: string
  broker: string
  isin?: string
  type: Position['type']
  isSell: boolean
}

/**
 * Given raw buy/sell lots (in any order), applies FIFO matching per ticker.
 * Returns Position[] with sellDate/sellPrice set on closed lots.
 * Partial sells split a buy lot into a closed portion and an open remainder.
 */
export function applyFifo(lots: RawLot[]): Position[] {
  const byTicker: Record<string, RawLot[]> = {}
  for (const lot of lots) {
    (byTicker[lot.ticker] ??= []).push(lot)
  }

  const result: Position[] = []

  for (const tickerLots of Object.values(byTicker)) {
    const buys = tickerLots.filter(l => !l.isSell).sort((a, b) => a.date.localeCompare(b.date))
    const sells = tickerLots.filter(l => l.isSell).sort((a, b) => a.date.localeCompare(b.date))

    if (!sells.length) {
      buys.forEach(b => result.push(toLot(b)))
      continue
    }

    // Mutable queue: each entry tracks remaining qty of a buy lot
    const queue = buys.map(b => ({ ...b, remaining: b.qty }))
    let qi = 0

    for (const sell of sells) {
      let toSell = sell.qty
      while (toSell > 1e-6 && qi < queue.length) {
        const buy = queue[qi]
        const closed = Math.min(buy.remaining, toSell)
        result.push({
          id: '', ticker: buy.ticker, name: buy.name, type: buy.type,
          quantity: closed, buyPrice: buy.price, buyDate: buy.date,
          currency: buy.currency, broker: buy.broker,
          ...(buy.isin ? { isin: buy.isin } : {}),
          sellPrice: sell.price, sellDate: sell.date,
        } as Position)
        buy.remaining -= closed
        toSell -= closed
        if (buy.remaining < 1e-6) qi++
      }
      // Unmatched sell qty → no buy found; silently ignored
    }

    // Remaining open buy lots
    if (qi < queue.length) {
      const first = queue[qi]
      if (first.remaining > 1e-6) {
        result.push({ ...toLot({ ...first, qty: first.remaining }), id: '' } as Position)
      }
      for (let i = qi + 1; i < queue.length; i++) result.push(toLot(queue[i]))
    }
  }

  return result
}

function toLot(l: RawLot): Position {
  return {
    id: '', ticker: l.ticker, name: l.name, type: l.type,
    quantity: l.qty, buyPrice: l.price, buyDate: l.date,
    currency: l.currency, broker: l.broker,
    ...(l.isin ? { isin: l.isin } : {}),
  }
}
