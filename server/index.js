import express from 'express'
import { readFileSync, writeFileSync, existsSync, renameSync, copyFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DATA_FILE = join(__dirname, 'data.json')
const DATA_TMP  = join(__dirname, 'data.json.tmp')
const DATA_BAK  = join(__dirname, 'data.json.bak')
const DIST_DIR  = join(__dirname, '../dist')
const IS_PROD   = process.env.NODE_ENV === 'production'

// ── In-memory store ──────────────────────────────────────────────────────────
// Load once at startup; all reads/writes go through this object.
// Flushed to disk asynchronously with a debounced atomic write.
let store = {}

function loadStore() {
  if (!existsSync(DATA_FILE)) return
  try {
    store = JSON.parse(readFileSync(DATA_FILE, 'utf8'))
  } catch (err) {
    console.warn('[stock-tracker] data.json unreadable — starting with empty store:', err.message)
  }
}

loadStore()

let flushTimer = null

function scheduleFlush() {
  clearTimeout(flushTimer)
  flushTimer = setTimeout(flushToDisk, 500)
}

function flushToDisk() {
  try {
    if (existsSync(DATA_FILE)) copyFileSync(DATA_FILE, DATA_BAK)
    writeFileSync(DATA_TMP, JSON.stringify(store, null, 2))
    renameSync(DATA_TMP, DATA_FILE)
  } catch (err) {
    console.error('[stock-tracker] failed to flush data.json:', err.message)
  }
}

// Flush before the process exits so no writes are dropped
process.on('exit', () => { clearTimeout(flushTimer); flushToDisk() })
process.on('SIGINT', () => { clearTimeout(flushTimer); flushToDisk(); process.exit() })
process.on('SIGTERM', () => { clearTimeout(flushTimer); flushToDisk(); process.exit() })

// ── App ───────────────────────────────────────────────────────────────────────
const app = express()
app.use(express.json({ limit: '10mb' }))

// In production the Vite dev-server proxy is absent, so Express forwards Yahoo requests.
// Replicates the vite.config.ts proxy: /api/yahoo/* → https://query1.finance.yahoo.com/*
if (IS_PROD) {
  app.get('/api/yahoo/*', async (req, res, next) => {
    const upstreamPath = req.path.replace('/api/yahoo', '')
    const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
    const url = `https://query1.finance.yahoo.com${upstreamPath}${qs}`
    const ac = new AbortController()
    const timer = setTimeout(() => ac.abort(), 15_000)
    try {
      const upstream = await fetch(url, {
        signal: ac.signal,
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
          'Accept': 'application/json, text/plain, */*',
        },
      })
      clearTimeout(timer)
      const body = await upstream.arrayBuffer()
      res
        .status(upstream.status)
        .set('content-type', upstream.headers.get('content-type') ?? 'application/json')
        .send(Buffer.from(body))
    } catch (err) {
      clearTimeout(timer)
      const isTimeout = err.name === 'AbortError'
      res.status(isTimeout ? 504 : 502).json({ error: isTimeout ? 'Upstream timeout' : 'Upstream request failed' })
    }
  })
}

app.get('/api/persist/:key', (req, res) => {
  const value = store[req.params.key] ?? null
  res.json({ value })
})

app.post('/api/persist/:key', (req, res, next) => {
  if (typeof req.body?.value !== 'string') {
    return res.status(400).json({ error: 'body.value must be a string' })
  }
  store[req.params.key] = req.body.value
  scheduleFlush()
  res.json({ ok: true })
})

// ── Error middleware (must be last, 4-arg signature required by Express) ──────
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  console.error('[stock-tracker] unhandled error:', err)
  res.status(500).json({ error: 'Internal server error' })
})

// Serve the built frontend and fall back to index.html for the SPA in production.
if (IS_PROD) {
  app.use(express.static(DIST_DIR))
  app.get('*', (_req, res) => res.sendFile(join(DIST_DIR, 'index.html')))
}

const PORT = Number(process.env.PORT ?? 3001)
app.listen(PORT, () => console.log(`[stock-tracker] listening on http://localhost:${PORT}`))
