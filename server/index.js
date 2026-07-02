import express from 'express'
import { readFileSync, writeFileSync, existsSync, renameSync, copyFileSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DATA_FILE = join(__dirname, 'data.json')
const DATA_TMP  = join(__dirname, 'data.json.tmp')
const DATA_BAK  = join(__dirname, 'data.json.bak')
const BACKUP_DIR = join(__dirname, 'backups')
const BACKUP_KEEP = 7
const DIST_DIR  = join(__dirname, '../dist')
const IS_PROD   = process.env.NODE_ENV === 'production'

// Timestamped stdout/stderr logging — visible via `docker logs stock-tracker`
const log    = (...args) => console.log(`[${new Date().toISOString()}]`, ...args)
const logErr = (...args) => console.error(`[${new Date().toISOString()}]`, ...args)

// ── In-memory store ──────────────────────────────────────────────────────────
// Load once at startup; all reads/writes go through this object.
// Flushed to disk asynchronously with a debounced atomic write.
let store = {}

function loadStore() {
  if (!existsSync(DATA_FILE)) return
  try {
    store = JSON.parse(readFileSync(DATA_FILE, 'utf8'))
  } catch (err) {
    logErr('data.json unreadable — starting with empty store:', err.message)
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
    dailyBackup()
  } catch (err) {
    logErr('failed to flush data.json:', err.message)
  }
}

// One dated copy per day on the first flush of that day, keeping the last
// BACKUP_KEEP days — the single .bak alone can't recover from a bad write
// that itself gets flushed again.
function dailyBackup() {
  const target = join(BACKUP_DIR, `data-${new Date().toISOString().slice(0, 10)}.json`)
  if (existsSync(target)) return
  try {
    mkdirSync(BACKUP_DIR, { recursive: true })
    copyFileSync(DATA_FILE, target)
    readdirSync(BACKUP_DIR)
      .filter((f) => /^data-\d{4}-\d{2}-\d{2}\.json$/.test(f))
      .sort()
      .slice(0, -BACKUP_KEEP)
      .forEach((f) => unlinkSync(join(BACKUP_DIR, f)))
    log(`daily backup written: backups/${target.split('/').pop()}`)
  } catch (err) {
    logErr('daily backup failed:', err.message)
  }
}

// Flush before the process exits so no writes are dropped
process.on('exit', () => { clearTimeout(flushTimer); flushToDisk() })
process.on('SIGINT', () => { clearTimeout(flushTimer); flushToDisk(); process.exit() })
process.on('SIGTERM', () => { clearTimeout(flushTimer); flushToDisk(); process.exit() })

// ── App ───────────────────────────────────────────────────────────────────────
const app = express()
app.use(express.json({ limit: '10mb' }))

// In production the Vite dev-server proxy is absent, so Express forwards external requests.
async function proxyRequest(res, url, extraHeaders = {}) {
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 15_000)
  try {
    const upstream = await fetch(url, {
      signal: ac.signal,
      headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36', 'Accept': 'application/json, text/plain, */*', ...extraHeaders },
    })
    clearTimeout(timer)
    const body = await upstream.arrayBuffer()
    res.status(upstream.status).set('content-type', upstream.headers.get('content-type') ?? 'application/json').send(Buffer.from(body))
  } catch (err) {
    clearTimeout(timer)
    const isTimeout = err.name === 'AbortError'
    logErr(`proxy ${isTimeout ? 'timeout' : 'failure'}: ${url}`, isTimeout ? '' : err.message)
    res.status(isTimeout ? 504 : 502).json({ error: isTimeout ? 'Upstream timeout' : 'Upstream request failed' })
  }
}

if (IS_PROD) {
  app.get('/api/yahoo/*', (req, res) => {
    const upstreamPath = req.path.replace('/api/yahoo', '')
    const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
    proxyRequest(res, `https://query1.finance.yahoo.com${upstreamPath}${qs}`)
  })

  app.get('/api/stooq/*', (req, res) => {
    const upstreamPath = req.path.replace('/api/stooq', '')
    const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
    proxyRequest(res, `https://stooq.com${upstreamPath}${qs}`)
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
  if (req.params.key === 'stock_tracker_portfolios') {
    try {
      const before = new Map(JSON.parse(store[req.params.key] ?? '[]').map((p) => [p.id, p.name]))
      const after  = new Map(JSON.parse(req.body.value).map((p) => [p.id, p.name]))
      for (const [id, name] of after) {
        if (!before.has(id)) log(`portfolio created: "${name}" (${id})`)
        else if (before.get(id) !== name) log(`portfolio renamed: "${before.get(id)}" → "${name}" (${id})`)
      }
      for (const [id, name] of before) {
        if (!after.has(id)) log(`portfolio deleted: "${name}" (${id})`)
      }
    } catch { /* malformed JSON — persist anyway, just skip the diff logging */ }
  }
  store[req.params.key] = req.body.value
  scheduleFlush()
  res.json({ ok: true })
})

// ── Error middleware (must be last, 4-arg signature required by Express) ──────
// eslint-disable-next-line no-unused-vars
app.use((err, _req, res, _next) => {
  logErr('unhandled error:', err)
  res.status(500).json({ error: 'Internal server error' })
})

// Serve the built frontend and fall back to index.html for the SPA in production.
if (IS_PROD) {
  app.use(express.static(DIST_DIR))
  app.get('*', (_req, res) => res.sendFile(join(DIST_DIR, 'index.html')))
}

const PORT = Number(process.env.PORT ?? 3001)
app.listen(PORT, () => log(`listening on http://localhost:${PORT}`))
