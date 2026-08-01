import { useState, useEffect } from 'react'
import { getItem, setItem } from '../utils/storage'
import { randomUUID } from '../utils/uuid'

export interface Portfolio {
  id: string
  name: string
}

const PORTFOLIOS_KEY = 'stock_tracker_portfolios'
const ACTIVE_KEY = 'stock_tracker_active_portfolio'
// Legacy keys — migrated on first load
const LEGACY_POSITIONS_KEY = 'stock_tracker_positions'
const LEGACY_MANUAL_KEY = 'stock_tracker_manual_prices'

function syncInit(): { portfolios: Portfolio[]; activeId: string } {
  try {
    const raw = localStorage.getItem(PORTFOLIOS_KEY)
    const list: Portfolio[] = raw ? JSON.parse(raw) : []
    if (!list.length) return { portfolios: [], activeId: '' }
    const stored = localStorage.getItem(ACTIVE_KEY) ?? ''
    const activeId = list.some((p) => p.id === stored) ? stored : list[0].id
    return { portfolios: list, activeId }
  } catch {
    return { portfolios: [], activeId: '' }
  }
}

export function usePortfolios() {
  const local = syncInit()
  const [portfolios, setPortfolios] = useState<Portfolio[]>(local.portfolios)
  const [activeId, setActiveId] = useState<string>(local.activeId)
  const [ready, setReady] = useState(local.portfolios.length > 0)

  useEffect(() => {
    async function init() {
      const [rawList, rawActive] = await Promise.all([
        getItem(PORTFOLIOS_KEY).catch(() => null),
        getItem(ACTIVE_KEY).catch(() => null),
      ])

      if (rawList !== null) {
        try {
          const list: Portfolio[] = JSON.parse(rawList)
          setPortfolios(list)
          const active = rawActive ?? list[0]?.id ?? ''
          const validActive = list.some((p) => p.id === active) ? active : (list[0]?.id ?? '')
          setActiveId(validActive)
        } catch {}
      } else {
        // First run — migrate legacy data into a default portfolio
        const [legacyPos, legacyManual] = await Promise.all([
          getItem(LEGACY_POSITIONS_KEY).catch(() => null),
          getItem(LEGACY_MANUAL_KEY).catch(() => null),
        ])
        const defaultId = randomUUID()
        const defaultPortfolio: Portfolio = { id: defaultId, name: 'Main Portfolio' }
        if (legacyPos !== null) {
          await setItem(`stock_tracker_positions_${defaultId}`, legacyPos)
        }
        if (legacyManual !== null) {
          await setItem(`stock_tracker_manual_prices_${defaultId}`, legacyManual)
        }
        const list = [defaultPortfolio]
        await Promise.all([
          setItem(PORTFOLIOS_KEY, JSON.stringify(list)),
          setItem(ACTIVE_KEY, defaultId),
        ])
        setPortfolios(list)
        setActiveId(defaultId)
      }

      setReady(true)
    }
    init()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Mutating from the in-memory `portfolios` snapshot is unsafe: if another
  // browser tab (or a second call before this one's setState lands) wrote a
  // newer list in the meantime, blindly persisting [...portfolios, x] clobbers
  // it — the other portfolio's entry vanishes from the list while its
  // position data (a separate storage key) survives untouched, orphaned.
  // Re-fetching the server's current list right before mutating narrows that
  // race to a single round trip instead of "until this tab reloads".
  const mutatePortfolios = async (mutate: (current: Portfolio[]) => Portfolio[]): Promise<Portfolio[]> => {
    let current = portfolios
    try {
      const raw = await getItem(PORTFOLIOS_KEY)
      if (raw !== null) current = JSON.parse(raw)
    } catch {}
    const next = mutate(current)
    setPortfolios(next)
    await setItem(PORTFOLIOS_KEY, JSON.stringify(next))
    return next
  }

  const addPortfolio = (name: string): string => {
    const id = randomUUID()
    mutatePortfolios((current) => [...current, { id, name }])
    return id
  }

  const removePortfolio = (id: string) => {
    mutatePortfolios((current) => (current.length <= 1 ? current : current.filter((p) => p.id !== id)))
      .then((next) => {
        if (id === activeId && next[0]) {
          const newActive = next[0].id
          setActiveId(newActive)
          setItem(ACTIVE_KEY, newActive)
        }
      })
  }

  const renamePortfolio = (id: string, name: string) => {
    mutatePortfolios((current) => current.map((p) => (p.id === id ? { ...p, name } : p)))
  }

  const switchPortfolio = (id: string) => {
    setActiveId(id)
    setItem(ACTIVE_KEY, id)
  }

  return { portfolios, activeId, ready, addPortfolio, removePortfolio, renamePortfolio, switchPortfolio }
}
