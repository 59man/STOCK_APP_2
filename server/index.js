import express from 'express'
import { readFileSync, writeFileSync, existsSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))
const DATA_FILE = join(__dirname, 'data.json')

function readData() {
  if (!existsSync(DATA_FILE)) return {}
  try { return JSON.parse(readFileSync(DATA_FILE, 'utf8')) } catch { return {} }
}

function writeData(data) {
  writeFileSync(DATA_FILE, JSON.stringify(data, null, 2))
}

const app = express()
app.use(express.json({ limit: '10mb' }))

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

const PORT = 3001
app.listen(PORT, () => console.log(`[persist] listening on http://localhost:${PORT}`))
