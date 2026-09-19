import { defineConfig } from '@playwright/test'

/**
 * Layout checks against a demo dataset.
 *
 * DATA_FILE points the persist server at a throwaway file, so these never read or write the
 * real portfolio — and the run is deterministic, which a live portfolio would not be.
 */
export default defineConfig({
  testDir: './tests/ui',
  timeout: 60_000,
  expect: { timeout: 15_000 },
  use: { baseURL: 'http://localhost:5173' },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: true,
    timeout: 120_000,
    env: { DATA_FILE: process.env.DEMO_DATA_FILE ?? 'server/demo-data.json' },
  },
})
