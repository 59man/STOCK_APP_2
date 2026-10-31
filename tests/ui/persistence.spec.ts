import { test, expect, Page } from '@playwright/test'

/**
 * What the web app writes to the server when the server is empty or failing. A blind write here
 * reaches every client, because Android accepts whatever the server holds as a real change.
 *
 * The persist API is mocked in the browser, so no run touches any stored data.
 */
const LIST_KEY = 'stock_tracker_portfolios'
const PORTFOLIO = { id: 'p1', name: 'Real Portfolio' }
const POSITIONS_KEY = `stock_tracker_positions_${PORTFOLIO.id}`
const LOT = {
  id: 'lot-1', ticker: 'ACME', name: 'Acme', type: 'stock',
  quantity: 2, buyPrice: 10, buyDate: '2024-01-02', currency: 'USD',
}

interface Mock {
  /** Value per key; a missing key answers null, like the real server. */
  server?: Record<string, string>
  /** Keys (or '*') whose GET answers 500. */
  failing?: string[]
  local?: Record<string, string>
}

async function mockPersist(page: Page, { server = {}, failing = [], local = {} }: Mock) {
  const posts: { key: string; value: string }[] = []
  const state = { failing }
  await page.route('**/api/persist/**', async (route) => {
    const key = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    if (route.request().method() === 'POST') {
      const { value } = route.request().postDataJSON()
      posts.push({ key, value })
      server[key] = value
      return route.fulfill({ json: { ok: true } })
    }
    if (state.failing.includes('*') || state.failing.includes(key)) {
      return route.fulfill({ status: 500, json: { error: 'down' } })
    }
    return route.fulfill({ json: { value: server[key] ?? null } })
  })
  // Market data and the device registry are irrelevant here and must not hit the network.
  await page.route(/\/api\/(yahoo|stooq|onemarkets|fio-fund|devices)/, (route) => route.abort())
  await page.addInitScript((entries) => {
    if (sessionStorage.getItem('seeded')) return
    sessionStorage.setItem('seeded', '1')
    for (const [k, v] of Object.entries(entries)) localStorage.setItem(k, v)
  }, local)
  return { posts, state }
}

/** Long enough for every hook's load effect and any write it would trigger. */
const settle = (page: Page) => page.waitForTimeout(1_500)

test('a genuinely fresh start still creates the default portfolio', async ({ page }) => {
  const { posts } = await mockPersist(page, {})
  await page.goto('/')
  await expect(page.getByText('Main Portfolio')).toBeVisible()
  expect(posts.map((p) => p.key)).toContain(LIST_KEY)
})

test('a server that lost its data gets it back from this browser, not a new default', async ({ page }) => {
  const { posts } = await mockPersist(page, {
    local: { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) },
  })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  await expect(page.getByText('Main Portfolio')).toHaveCount(0)
  expect(posts.find((p) => p.key === LIST_KEY)?.value).toBe(JSON.stringify([PORTFOLIO]))
  expect(posts.find((p) => p.key === POSITIONS_KEY)?.value).toBe(JSON.stringify([LOT]))
})

test('a failing server with no local copy gets no writes, then loads once it answers', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  const { posts, state } = await mockPersist(page, { server, failing: ['*'] })
  await page.goto('/')
  await expect(page.getByText('Loading portfolios')).toBeVisible()
  await settle(page)
  expect(posts).toEqual([])

  state.failing = []
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible({ timeout: 10_000 })
  await settle(page)
  expect(posts).toEqual([])
})

test('a failed read of one portfolio\'s data does not overwrite it', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  const { posts } = await mockPersist(page, { server, failing: [POSITIONS_KEY] })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  expect(posts).toEqual([])
  expect(server[POSITIONS_KEY]).toBe(JSON.stringify([LOT]))
})

test('loading unchanged data writes nothing back but refreshes localStorage', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  const { posts } = await mockPersist(page, { server })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  expect(posts).toEqual([])
  expect(await page.evaluate((k) => localStorage.getItem(k), POSITIONS_KEY)).toBe(JSON.stringify([LOT]))
})

test('an edit after a clean load is still saved', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  const { posts } = await mockPersist(page, { server })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  await page.getByRole('button', { name: '+ Add Position' }).click()
  await page.getByPlaceholder('e.g. AAPL, SPY, US0378331005').fill('NEWCO')
  await page.getByPlaceholder('Company / fund name').fill('New Co')
  await page.getByPlaceholder('0', { exact: true }).fill('3')
  await page.getByPlaceholder('0.00').first().fill('7')
  await page.locator('form button[type="submit"]').click()

  await expect.poll(() => posts.find((p) => p.key === POSITIONS_KEY)?.value ?? '').toContain('NEWCO')
  expect(JSON.parse(server[POSITIONS_KEY]).map((p: { ticker: string }) => p.ticker)).toEqual(['ACME', 'NEWCO'])
})

test('a key the server rejects asks for the right one, then loads', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  await page.route('**/api/persist/**', (route) => {
    if (route.request().headers()['x-api-key'] !== 'right-key') return route.fulfill({ status: 401, json: { error: 'unauthorized' } })
    const key = decodeURIComponent(new URL(route.request().url()).pathname.split('/').pop()!)
    return route.fulfill({ json: { value: server[key] ?? null } })
  })
  await page.route(/\/api\/(yahoo|stooq|onemarkets|fio-fund|devices)/, (route) => route.abort())
  await page.goto('/')

  await expect(page.getByText('did not accept')).toBeVisible()
  await page.getByLabel('API key').fill('right-key')
  await page.getByRole('button', { name: 'Save' }).click()

  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await expect(page.getByText('did not accept')).toHaveCount(0)
})

test('an edit from a stale tab keeps what another device added meanwhile', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  await mockPersist(page, { server })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  const phoneLot = { ...LOT, id: 'lot-phone', ticker: 'PHONE', name: 'Phone Co' }
  server[POSITIONS_KEY] = JSON.stringify([LOT, phoneLot])

  await page.getByRole('button', { name: '+ Add Position' }).click()
  await page.getByPlaceholder('e.g. AAPL, SPY, US0378331005').fill('NEWCO')
  await page.getByPlaceholder('Company / fund name').fill('New Co')
  await page.getByPlaceholder('0', { exact: true }).fill('3')
  await page.getByPlaceholder('0.00').first().fill('7')
  await page.locator('form button[type="submit"]').click()

  await expect.poll(() => JSON.parse(server[POSITIONS_KEY]).map((p: { ticker: string }) => p.ticker).sort())
    .toEqual(['ACME', 'NEWCO', 'PHONE'])
  await expect(page.getByText('PHONE').first()).toBeVisible()
})

test('an edit made during an outage survives a reload and reaches the server afterwards', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  const { state } = await mockPersist(page, { server })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()
  await settle(page)

  state.failing = [POSITIONS_KEY]
  await page.getByRole('button', { name: '+ Add Position' }).click()
  await page.getByPlaceholder('e.g. AAPL, SPY, US0378331005').fill('NEWCO')
  await page.getByPlaceholder('Company / fund name').fill('New Co')
  await page.getByPlaceholder('0', { exact: true }).fill('3')
  await page.getByPlaceholder('0.00').first().fill('7')
  await page.locator('form button[type="submit"]').click()
  await settle(page)
  expect(server[POSITIONS_KEY]).not.toContain('NEWCO')
  await expect(page.getByText('not saved to the server yet')).toBeVisible()

  state.failing = []
  await page.reload()

  await expect.poll(() => server[POSITIONS_KEY]).toContain('NEWCO')
  await expect(page.getByText('NEWCO').first()).toBeVisible()
  await expect(page.getByText('not saved to the server yet')).toHaveCount(0)
})

test('a row leaves "prev. close" once its midnight anchor arrives', async ({ page }) => {
  const server = { [LIST_KEY]: JSON.stringify([PORTFOLIO]), [POSITIONS_KEY]: JSON.stringify([LOT]) }
  await mockPersist(page, { server })
  // A market that last traded days ago: the anchor is the current price, resolved from the same chart.
  const lastTraded = Math.floor(Date.now() / 1000) - 3 * 86400
  await page.route(/\/api\/yahoo\/v8\/finance\/chart\/ACME/, async (route) => {
    await new Promise((r) => setTimeout(r, 300))
    return route.fulfill({
      json: { chart: { result: [{
        meta: { currency: 'USD', regularMarketPrice: 12, previousClose: 11, regularMarketTime: lastTraded, gmtoffset: 0, shortName: 'Acme' },
        timestamp: [lastTraded], indicators: { quote: [{ close: [12] }] },
      }] } },
    })
  })
  await page.goto('/')
  await expect(page.getByText(PORTFOLIO.name)).toBeVisible()

  await expect(page.getByText(/closed · last traded/).first()).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('prev. close')).toHaveCount(0)
})
