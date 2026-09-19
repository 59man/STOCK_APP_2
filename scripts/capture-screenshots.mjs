import { chromium } from 'playwright'
import { mkdirSync } from 'fs'

/**
 * README screenshots, captured against the invented demo dataset.
 *
 * The repository is public, so these must never show real holdings. Start the dev server with
 * DATA_FILE=server/demo-data.json first, and note the browser context here is a fresh one:
 * the app also keeps state in localStorage, so an existing profile would show the real
 * portfolio regardless of what the server serves.
 *
 *     DATA_FILE=server/demo-data.json npm run dev
 *     node scripts/capture-screenshots.mjs
 *
 * Known limitation: Playwright's bundled headless Chromium cannot capture in some sandboxed
 * environments — `page.screenshot` hangs after "fonts loaded" even on a trivial page. If that
 * happens, capture through a browser that does work rather than debugging this script; the
 * committed screenshots were taken that way.
 */
const OUT = 'docs/screenshots'
mkdirSync(OUT, { recursive: true })

const shots = [
  { name: 'web-portfolio', width: 1440, height: 900, prepare: async () => {} },
  {
    name: 'web-charts',
    width: 1440,
    height: 1000,
    prepare: async (page) => {
      await page.evaluate(() => {
        document.querySelector('.chart-section, .pnl-chart, canvas, svg')
          ?.scrollIntoView({ block: 'center' })
      })
      await page.waitForTimeout(1500)
    },
  },
]

const browser = await chromium.launch()
try {
  for (const shot of shots) {
    const context = await browser.newContext({ viewport: { width: shot.width, height: shot.height } })
    const page = await context.newPage()
    await page.goto('http://localhost:5173/')
    await page.waitForSelector('.app')
    // Quotes, dividends and chart history all arrive asynchronously; capturing earlier
    // produces a screenshot full of loading dots.
    await page.waitForTimeout(9000)
    await shot.prepare(page)
    // The default 5s screenshot timeout expires "waiting for fonts to load" when a Google
    // Fonts request is slow; the page itself is already rendered by then.
    await page.evaluate(() => document.fonts.ready.catch(() => {}))
    // animations: 'disabled' matters — the app's loading-dot animation never settles, so the
    // default screenshot wait for a stable frame expires even once everything has rendered.
    await page.screenshot({ path: `${OUT}/${shot.name}.png`, timeout: 60_000, animations: 'disabled' })
    await context.close()
    console.log(`captured ${shot.name}`)
  }
} finally {
  await browser.close()
}
