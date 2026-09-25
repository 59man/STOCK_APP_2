import { test, expect, Page } from '@playwright/test'

/**
 * The failures these catch are the ones unit tests cannot see: a column pushing the page
 * sideways, a cell whose text is cut off, a modal wider than the phone it is on.
 *
 * 360 is the narrowest phone worth supporting, 1440 a desktop; the three in between are where
 * the responsive breakpoints live.
 */
const VIEWPORTS = [
  { name: '360 (phone)', width: 360, height: 780 },
  { name: '390 (phone)', width: 390, height: 844 },
  { name: '640 (breakpoint)', width: 640, height: 900 },
  { name: '960 (breakpoint)', width: 960, height: 900 },
  { name: '1440 (desktop)', width: 1440, height: 900 },
]

async function settle(page: Page) {
  await page.goto('/')
  await page.waitForSelector('.app', { timeout: 20_000 })
  // Quotes and anchors resolve asynchronously; the layout is only worth measuring once the
  // numbers that size the columns are actually in the DOM.
  await page.waitForTimeout(4_000)
}

for (const vp of VIEWPORTS) {
  test.describe(`at ${vp.name}`, () => {
    test.use({ viewport: { width: vp.width, height: vp.height } })

    test('the page never scrolls sideways', async ({ page }) => {
      await settle(page)
      const overflow = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      expect(overflow.scrollWidth, 'page is wider than the viewport').toBeLessThanOrEqual(
        overflow.clientWidth,
      )
    })

    test('no cell, badge or card has its text cut off', async ({ page }) => {
      await settle(page)
      const clipped = await page.evaluate(() =>
        [...document.querySelectorAll('td, th, .summary-card, .currency-tab, .portfolio-tab')]
          .filter((el) => el.scrollWidth > el.clientWidth + 1)
          .map((el) => `${el.tagName}.${String(el.className)}: ${el.textContent?.slice(0, 40)}`)
          .slice(0, 10),
      )
      expect(clipped, 'elements with clipped text').toEqual([])
    })

    test('the summary cards fill every row they start', async ({ page }) => {
      // A card added without updating the grid's column count wraps alone onto a row of
      // empty cells — the Est. Dividends card did exactly that at 1440 px.
      await settle(page)
      const rows = await page.evaluate(() => {
        const grid = document.querySelector('.summary-grid')
        if (!grid) return []
        const gridRight = Math.round(grid.getBoundingClientRect().right)
        const byTop = new Map<number, number>()
        for (const card of grid.querySelectorAll('.summary-card')) {
          const r = card.getBoundingClientRect()
          byTop.set(Math.round(r.top), Math.max(byTop.get(Math.round(r.top)) ?? 0, Math.round(r.right)))
        }
        return [...byTop.values()].map((right) => gridRight - right)
      })
      for (const gap of rows) expect(gap, 'a summary row ends short of the grid edge').toBeLessThanOrEqual(2)
    })
  })
}

test.describe('at 360 (phone)', () => {
  test.use({ viewport: { width: 360, height: 780 } })

  test("the today cell keeps every line it is given", async ({ page }) => {
    await settle(page)
    const cell = page.locator('tbody tr td').nth(9)
    if (await cell.count()) {
      const box = await cell.boundingBox()
      expect(box?.height ?? 0, 'today cell collapsed').toBeGreaterThan(10)
    }
  })
})
