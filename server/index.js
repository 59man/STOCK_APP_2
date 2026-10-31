import 'dotenv/config'
import express from 'express'
import { readFileSync, writeFileSync, existsSync, copyFileSync, mkdirSync, readdirSync, unlinkSync } from 'fs'
import { createHash, randomBytes } from 'crypto'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
// DATA_FILE can point somewhere else so a throwaway dataset can be served without touching
// the real portfolio — used by the UI tests and by the README screenshot capture. Everything
// derived from it moves with it, so a demo run never writes a backup next to real data. The
// backup folder is named after the file, not "backups" beside it: the demo file lives in server/,
// where that would be the real folder, and its pruning would delete real backups.
const DATA_FILE = process.env.DATA_FILE ?? join(__dirname, 'data.json')
const DATA_BAK  = `${DATA_FILE}.bak`
const BACKUP_DIR = process.env.DATA_FILE
  ? `${DATA_FILE}.backups`
  : join(__dirname, 'backups')
const BACKUP_KEEP = 7
const DIST_DIR  = join(__dirname, '../dist')
const IS_PROD   = process.env.NODE_ENV === 'production'

// Timestamped stdout/stderr logging — visible via `docker logs stock-tracker`
const log    = (...args) => console.log(`[${new Date().toISOString()}]`, ...args)
const logErr = (...args) => console.error(`[${new Date().toISOString()}]`, ...args)

// ── API key ───────────────────────────────────────────────────────────────────
// PERSIST_API_KEY wins if set. Otherwise production generates a key on first start, never at image
// build, where it would ship inside the public image. It lives with the backups because that folder
// is mounted and outlives the container, while data.json is a single-file mount; keeping it out of
// data.json also means restoring a backup cannot bring back a rotated key.
const KEY_FILE = join(BACKUP_DIR, '.api-key')
const ENV_KEY = process.env.PERSIST_API_KEY

function readKeyFile() {
  try {
    return readFileSync(KEY_FILE, 'utf8').trim()
  } catch {
    return ''
  }
}

function writeNewKey() {
  mkdirSync(BACKUP_DIR, { recursive: true })
  const key = randomBytes(32).toString('hex')
  writeFileSync(KEY_FILE, `${key}\n`, { mode: 0o600 })
  return key
}

const currentKey = () => ENV_KEY || (IS_PROD ? readKeyFile() : '')

// `docker exec stock-tracker node server/index.js show-key|rotate-key`. Handled here, before the
// store loads, so a command never starts a second server or restores anything.
const command = process.argv[2]
if (command) {
  if (command === 'show-key' && currentKey()) {
    console.log(currentKey())
  } else if (command === 'rotate-key' && IS_PROD && !ENV_KEY) {
    console.log(writeNewKey())
  } else if (command === 'rotate-key') {
    console.error('Cannot rotate: PERSIST_API_KEY is set, or NODE_ENV is not production. Change that value and restart instead.')
    process.exit(1)
  } else {
    console.error('Usage: node server/index.js [show-key|rotate-key] (show-key needs a key to exist)')
    process.exit(1)
  }
  process.exit(0)
}

// ── In-memory store ──────────────────────────────────────────────────────────
// Load once at startup; all reads/writes go through this object.
// Flushed to disk asynchronously with a debounced write.
let store = {}
const PORTFOLIOS_KEY = 'stock_tracker_portfolios'

function readStoreFile(path) {
  const parsed = JSON.parse(readFileSync(path, 'utf8'))
  if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('not a JSON object')
  return parsed
}

// Everything that could restore the store, best first.
function recoverySources() {
  const dated = existsSync(BACKUP_DIR)
    ? readdirSync(BACKUP_DIR)
        .filter((f) => /^data-\d{4}-\d{2}-\d{2}\.json$/.test(f))
        .sort()
        .reverse()
        .map((f) => join(BACKUP_DIR, f))
    : []
  return [DATA_BAK, ...dated]
}

function firstUsable(sources, accept = () => true) {
  for (const path of sources) {
    try {
      const parsed = readStoreFile(path)
      if (accept(parsed)) return { path, parsed }
    } catch { /* missing or unreadable: try the next */ }
  }
  return null
}

function restore(from, reason) {
  if (existsSync(DATA_FILE)) {
    const evidence = `${DATA_FILE}.corrupt-${Date.now()}`
    copyFileSync(DATA_FILE, evidence)
    logErr(`previous file kept as ${evidence}`)
  }
  store = from.parsed
  copyFileSync(from.path, DATA_FILE)
  logErr(`RECOVERED from ${from.path} (${reason}) — the most recent writes may be missing`)
}

// Losing the store must never look like a first run: clients read a missing key as "nothing here",
// and the next flush would destroy whatever could still be recovered. No client ever removes a key,
// so a file without a portfolio list, or no file, is a loss whenever a backup says otherwise. The
// exit handlers that flush are only registered after this has run, so exiting here writes nothing.
function loadStore() {
  const hasPortfolios = (s) => typeof s[PORTFOLIOS_KEY] === 'string'

  if (!existsSync(DATA_FILE)) {
    const from = firstUsable(recoverySources(), hasPortfolios)
    if (from) restore(from, 'data file missing')
    return // no file and no backup with data: a genuine first run
  }

  try {
    store = readStoreFile(DATA_FILE)
  } catch (err) {
    logErr('data file unreadable:', err.message)
    const from = firstUsable(recoverySources())
    if (from) return restore(from, 'data file unreadable')
    logErr(`no usable backup — refusing to start. Repair ${DATA_FILE} or restore one from ${BACKUP_DIR}.`)
    process.exit(1)
  }

  if (!hasPortfolios(store)) {
    const from = firstUsable(recoverySources(), hasPortfolios)
    if (from) restore(from, 'data file has no portfolio list')
  }
}

loadStore()
// store.devices is structured data the server itself reads/writes (not an opaque
// client-authored blob like the /api/persist/:key values) — see the device
// registry endpoints below. Same flush/backup machinery, just a typed array.
if (!Array.isArray(store.devices)) store.devices = []

let flushTimer = null
let flushWaiters = []
const FLUSH_RETRY_MS = 30_000

function scheduleFlush() {
  clearTimeout(flushTimer)
  flushTimer = setTimeout(flushPending, 500)
}

// Only writes when something changed: an unconditional flush on shutdown would overwrite .bak
// with the current version, leaving no rollback copy after every restart.
function flushPending() {
  if (flushTimer === null) return
  clearTimeout(flushTimer)
  flushTimer = null
  const waiters = flushWaiters
  flushWaiters = []
  const saved = flushToDisk()
  waiters.forEach((notify) => notify(saved))
  // The store still holds this data; keep trying, so it reaches disk once the fault clears.
  if (!saved) flushTimer = setTimeout(flushPending, FLUSH_RETRY_MS)
}

function flushToDisk() {
  try {
    if (existsSync(DATA_FILE)) copyFileSync(DATA_FILE, DATA_BAK)
    // Write in place rather than write-tmp+rename: DATA_FILE is commonly bind-mounted
    // as a single file (see docker run examples in CLAUDE.md), which makes it a mount
    // point inside the container — renaming another file on top of a mount point fails
    // with EBUSY. DATA_BAK above still protects against a write landing mid-corruption.
    writeFileSync(DATA_FILE, JSON.stringify(store, null, 2))
    dailyBackup()
    return true
  } catch (err) {
    logErr('failed to flush data.json:', err.message)
    return false
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
process.on('exit', flushPending)
process.on('SIGINT', () => process.exit())
process.on('SIGTERM', () => process.exit())

// ── App ───────────────────────────────────────────────────────────────────────
const app = express()
app.use(express.json({ limit: '10mb' }))

// Unauthenticated — the Docker HEALTHCHECK and any pre-auth uptime check hit this.
app.get('/api/health', (_req, res) => res.json({ ok: true }))

// ── Auth ──────────────────────────────────────────────────────────────────────
// Shared-secret guard for /api/persist (and, in prod, /api/yahoo + /api/stooq).
// Dev fails open with a warning so `npm run dev` needs no setup. Prod always requires a key, and
// refuses to start rather than serve an unauthenticated instance reachable from a phone.
const AUTH_REQUIRED = IS_PROD || !!ENV_KEY
if (!AUTH_REQUIRED) {
  log('PERSIST_API_KEY not set — running without auth (dev only).')
} else if (!currentKey()) {
  try {
    writeNewKey()
    log('New API key generated. Read it with: docker exec stock-tracker node server/index.js show-key')
  } catch (err) {
    logErr(`cannot save a generated API key to ${KEY_FILE}: ${err.message} — refusing to start.`)
    process.exit(1)
  }
}

function requireApiKey(req, res, next) {
  if (!AUTH_REQUIRED) return next()
  // Read per request so rotate-key takes effect without a restart. A key file gone missing while
  // running leaves no key, which must refuse everyone, including an empty header.
  const key = currentKey()
  if (!key || req.header('X-API-Key') !== key) {
    return res.status(401).json({ error: 'unauthorized' })
  }
  next()
}

app.use('/api/persist', requireApiKey)
app.use('/api/yahoo', requireApiKey)
app.use('/api/stooq', requireApiKey)
app.use('/api/devices', requireApiKey)
app.use('/api/onemarkets', requireApiKey)
app.use('/api/fio-fund', requireApiKey)

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

  // Stateless passthrough to onemarkets' public (undocumented) NAV chart-data
  // endpoint — used to auto-fetch the 3 UniCredit onemarkets fund prices that
  // have no Yahoo/Stooq listing (see src/data/fundProviderTickers.ts).
  app.get('/api/onemarkets/*', (req, res) => {
    const upstreamPath = req.path.replace('/api/onemarkets', '')
    const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
    proxyRequest(res, `https://www.onemarkets.cz${upstreamPath}${qs}`)
  })
}

// ── Fio fund auto-pricing ────────────────────────────────────────────────────
// fiofondy.cz's NAV chart data is a Nette "signal" endpoint gated by a session
// cookie (not CORS-enabled either way, so this can't be a raw client fetch).
// Registered unconditionally (not IS_PROD-only) so it works the same in dev
// (persist server on :3001, proxied by Vite — see vite.config.ts) and prod.
// Generic over any fund's URL slug (?slug=<slug> → fiofondy.cz/cs/podilove-fondy/<slug>)
// so adding a new Fio fund is a data-only change in fundProviderTickers.ts —
// the session cookie is shared site-wide across every fund page (verified),
// so one cache serves all slugs.
const FIO_BASE_URL = 'https://www.fiofondy.cz/cs/podilove-fondy'
const FIO_SLUG_RE = /^[a-z0-9-]+$/
const BROWSER_UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36'
let fioCookieCache = { cookie: null, expiresAt: 0 }

async function getFioSessionCookie() {
  if (fioCookieCache.cookie && Date.now() < fioCookieCache.expiresAt) return fioCookieCache.cookie
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 15_000)
  try {
    // Any fund page bootstraps a valid session — this one is just a stable default.
    const res = await fetch(`${FIO_BASE_URL}/globalni-akciovy-fond`, { signal: ac.signal, headers: { 'User-Agent': BROWSER_UA } })
    clearTimeout(timer)
    const cookies = typeof res.headers.getSetCookie === 'function' ? res.headers.getSetCookie() : []
    const cookie = cookies.map((c) => c.split(';')[0]).join('; ')
    if (!cookie) throw new Error('no session cookie in response')
    fioCookieCache = { cookie, expiresAt: Date.now() + 30 * 60_000 }
    return cookie
  } catch (err) {
    clearTimeout(timer)
    throw err
  }
}

app.get('/api/fio-fund/quote', async (req, res) => {
  const slug = req.query.slug
  if (typeof slug !== 'string' || !FIO_SLUG_RE.test(slug)) {
    return res.status(400).json({ error: 'invalid or missing slug' })
  }
  const fundUrl = `${FIO_BASE_URL}/${slug}`
  const ac = new AbortController()
  const timer = setTimeout(() => ac.abort(), 15_000)
  try {
    const cookie = await getFioSessionCookie()
    const upstream = await fetch(`${fundUrl}?do=getFundChartData`, {
      signal: ac.signal,
      headers: {
        'User-Agent': BROWSER_UA,
        'X-Requested-With': 'XMLHttpRequest',
        'Referer': fundUrl,
        'Cookie': cookie,
      },
    })
    clearTimeout(timer)
    const body = await upstream.text()
    let json
    try { json = JSON.parse(body) } catch { json = null }
    if (!upstream.ok || !Array.isArray(json)) {
      // session cookie likely stale (signal returned the {"redirect":...} shape) — drop it so the next request re-fetches one
      fioCookieCache = { cookie: null, expiresAt: 0 }
      return res.status(502).json({ error: 'Upstream returned no data' })
    }
    res.json(json)
  } catch (err) {
    clearTimeout(timer)
    const isTimeout = err.name === 'AbortError'
    logErr(`fio-fund proxy ${isTimeout ? 'timeout' : 'failure'}:`, isTimeout ? '' : err.message)
    res.status(isTimeout ? 504 : 502).json({ error: isTimeout ? 'Upstream timeout' : 'Upstream request failed' })
  }
})

// A value's version is its hash, so nothing extra is stored and it survives a restart.
const versionOf = (value) => (value == null ? '' : createHash('sha1').update(value).digest('hex'))

app.get('/api/persist/:key', (req, res) => {
  const value = store[req.params.key] ?? null
  res.json({ value, version: versionOf(value) })
})

app.post('/api/persist/:key', (req, res, next) => {
  if (typeof req.body?.value !== 'string') {
    return res.status(400).json({ error: 'body.value must be a string' })
  }
  // Only a client sending the version it read gets this check; older ones still save as before.
  const { baseVersion } = req.body
  if (typeof baseVersion === 'string' && baseVersion !== versionOf(store[req.params.key])) {
    return res.status(409).json({ error: 'changed since it was read' })
  }
  if (req.params.key === PORTFOLIOS_KEY) {
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
  // Answer only once this is on disk: Android clears its dirty flag on {ok:true}, so a success
  // reported early for a write that then fails (full disk) would lose the data for good.
  flushWaiters.push((saved) => {
    if (saved) res.json({ ok: true })
    else res.status(507).json({ error: 'could not save to disk' })
  })
  scheduleFlush()
})

// ── Device registry ─────────────────────────────────────────────────────────
// Descriptive metadata about which clients have synced with this server — not an
// auth boundary (every device still shares the one X-API-Key; ids are self-reported).
// See docs/superpowers/specs/2026-08-29-device-registry-design.md.

app.post('/api/devices/heartbeat', (req, res) => {
  const { id, label, platform } = req.body ?? {}
  if (typeof id !== 'string' || !id) return res.status(400).json({ error: 'body.id must be a non-empty string' })
  if (platform !== 'web' && platform !== 'android') return res.status(400).json({ error: "body.platform must be 'web' or 'android'" })

  const now = new Date().toISOString()
  let device = store.devices.find((d) => d.id === id)
  if (device) {
    // Never apply `label` here — an existing row keeps whatever label it has
    // (possibly user-renamed via PATCH), so a client's own auto-guess on its next
    // heartbeat can't clobber a rename.
    device.lastSeen = now
  } else {
    device = { id, label: typeof label === 'string' && label ? label : 'Unknown device', platform, firstSeen: now, lastSeen: now }
    store.devices.push(device)
    log(`device registered: "${device.label}" (${platform}, ${id})`)
  }
  scheduleFlush()
  res.json({ ok: true, device })
})

app.get('/api/devices', (_req, res) => {
  const devices = [...store.devices].sort((a, b) => b.lastSeen.localeCompare(a.lastSeen))
  res.json({ devices })
})

app.patch('/api/devices/:id', (req, res) => {
  const label = req.body?.label
  if (typeof label !== 'string' || !label) return res.status(400).json({ error: 'body.label must be a non-empty string' })
  const device = store.devices.find((d) => d.id === req.params.id)
  if (!device) return res.status(404).json({ error: 'device not found' })
  device.label = label
  scheduleFlush()
  res.json({ ok: true, device })
})

app.delete('/api/devices/:id', (req, res) => {
  const before = store.devices.length
  store.devices = store.devices.filter((d) => d.id !== req.params.id)
  if (store.devices.length !== before) {
    log(`device removed: ${req.params.id}`)
    scheduleFlush()
  }
  // Idempotent by design — Android's best-effort unregister-on-disconnect must be
  // safe to call without checking existence first.
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
// With no key there is nothing protecting the data, so nothing past this machine may reach it.
const HOST = AUTH_REQUIRED ? undefined : '127.0.0.1'
app.listen(PORT, HOST, () => log(`listening on http://${HOST ?? 'localhost'}:${PORT}`))
