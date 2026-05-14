import express from 'express'
import { readFileSync, writeFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DATA_FILE = join(__dirname, 'data.json')
const DIST_DIR  = join(__dirname, '../dist')
const IS_PROD   = process.env.NODE_ENV === 'production'

function readData() {
  if (!existsSync(DATA_FILE)) return {}
  try { return JSON.parse(readFileSync(DATA_FILE, 'utf8')) } catch { return {} }
}

function writeData(data) {
  writeFileSync(DATA_FILE, JSON.stringify(data, null, 2))
}

const app = express()
app.use(express.json({ limit: '10mb' }))

// In production the Vite dev-server proxy is absent, so Express forwards Yahoo requests.
// Replicates the vite.config.ts proxy: /api/yahoo/* → https://query1.finance.yahoo.com/*
if (IS_PROD) {
  app.get('/api/yahoo/*', async (req, res) => {
    const upstreamPath = req.path.replace('/api/yahoo', '')
    const qs = req.url.includes('?') ? req.url.slice(req.url.indexOf('?')) : ''
    const url = `https://query1.finance.yahoo.com${upstreamPath}${qs}`
    try {
      const upstream = await fetch(url, {
        headers: {
          'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36',
          'Accept': 'application/json, text/plain, */*',
        },
      })
      const body = await upstream.arrayBuffer()
      res
        .status(upstream.status)
        .set('content-type', upstream.headers.get('content-type') ?? 'application/json')
        .send(Buffer.from(body))
    } catch (err) {
      res.status(502).json({ error: String(err) })
    }
  })
}

app.get('/api/persist/:key', (req, res) => {
  const data = readData()
  const value = data[req.params.key] ?? null
  res.json({ value })
})

app.post('/api/persist/:key', (req, res) => {
  const data = readData()
  data[req.params.key] = req.body.value
  writeData(data)
  res.json({ ok: true })
})

// Serve the built frontend and fall back to index.html for the SPA in production.
if (IS_PROD) {
  app.use(express.static(DIST_DIR))
  app.get('*', (_req, res) => res.sendFile(join(DIST_DIR, 'index.html')))
}

const PORT = Number(process.env.PORT ?? 3001)
app.listen(PORT, () => console.log(`[stock-tracker] listening on http://localhost:${PORT}`))
