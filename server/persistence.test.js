// Runs the real server as a child process against throwaway files: what matters here is what
// ends up on disk across a start and a stop, which an in-process test cannot observe.
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, rmSync, statSync, writeFileSync } from 'node:fs'
import { networkInterfaces, tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

import { request } from 'node:http'
import { isLocalHostHeader, isPrivateAddress } from './privateAddress.js'

const SERVER = join(dirname(fileURLToPath(import.meta.url)), 'index.js')
const API_KEY = 'test-key'
const GOOD = JSON.stringify({ stock_tracker_portfolios: '[{"id":"p1","name":"Real"}]', devices: [] })

let dir, dataFile, bakFile, port, child
let nextPort = 34100 + Math.floor(Math.random() * 500)

function start() {
  port = nextPort++
  // cwd is the temp dir so dotenv cannot pull a developer's real .env into the test.
  child = spawn(process.execPath, [SERVER], {
    cwd: dir,
    env: { PATH: process.env.PATH, DATA_FILE: dataFile, PORT: String(port), PERSIST_API_KEY: API_KEY },
    stdio: 'ignore',
  })
  return new Promise((resolve) => child.once('exit', (code, signal) => resolve({ code, signal })))
}

async function waitUntilListening() {
  for (let i = 0; i < 100; i++) {
    try {
      const res = await fetch(`http://127.0.0.1:${port}/api/health`)
      if (res.ok) return
    } catch { /* not up yet */ }
    await new Promise((r) => setTimeout(r, 50))
  }
  throw new Error('server did not start listening')
}

const headers = { 'X-API-Key': API_KEY, 'Content-Type': 'application/json' }

async function getValue(key) {
  const res = await fetch(`http://127.0.0.1:${port}/api/persist/${key}`, { headers })
  return (await res.json()).value
}

beforeEach(() => {
  dir = mkdtempSync(join(tmpdir(), 'stock-persist-'))
  dataFile = join(dir, 'data.json')
  bakFile = `${dataFile}.bak`
})

afterEach(() => {
  child?.kill('SIGKILL')
  rmSync(dir, { recursive: true, force: true })
})

describe('server startup with an unreadable data file', () => {
  it.each([
    ['truncated JSON', '{"stock_tracker_portfolios": "[{'],
    ['an empty file', ''],
    ['JSON that is not an object', 'null'],
  ])('refuses to start on %s with no backup, leaving the file untouched', async (_name, content) => {
    writeFileSync(dataFile, content)

    const { code } = await start()

    expect(code).not.toBe(0)
    expect(readFileSync(dataFile, 'utf8')).toBe(content)
    expect(existsSync(bakFile)).toBe(false)
  })

  it('refuses to start when the backup is unreadable too', async () => {
    writeFileSync(dataFile, '{broken')
    writeFileSync(bakFile, '{also broken')

    const { code } = await start()

    expect(code).not.toBe(0)
    expect(readFileSync(dataFile, 'utf8')).toBe('{broken')
    expect(readFileSync(bakFile, 'utf8')).toBe('{also broken')
  })

  it('recovers from the backup and keeps the unreadable file as evidence', async () => {
    writeFileSync(dataFile, '{broken')
    writeFileSync(bakFile, GOOD)

    start()
    await waitUntilListening()

    expect(await getValue('stock_tracker_portfolios')).toBe('[{"id":"p1","name":"Real"}]')
    expect(JSON.parse(readFileSync(dataFile, 'utf8')).stock_tracker_portfolios).toContain('Real')
    const evidence = readdirSync(dir).filter((f) => f.startsWith('data.json.corrupt-'))
    expect(evidence).toHaveLength(1)
    expect(readFileSync(join(dir, evidence[0]), 'utf8')).toBe('{broken')
  })
})

describe('backups of a DATA_FILE override', () => {
  it('stay out of a backups folder next to it, which may be the real one', async () => {
    // The UI tests run with DATA_FILE=server/demo-data.json, right beside server/backups.
    const realBackup = join(dir, 'backups', 'data-2000-01-01.json')
    mkdirSync(join(dir, 'backups'))
    writeFileSync(realBackup, 'real')
    for (let day = 2; day <= 8; day++) writeFileSync(join(dir, 'backups', `data-2000-01-0${day}.json`), 'real')
    writeFileSync(dataFile, GOOD)

    const exited = start()
    await waitUntilListening()
    await fetch(`http://127.0.0.1:${port}/api/persist/some_key`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ value: 'saved' }),
    })
    child.kill('SIGTERM')
    await exited

    expect(readdirSync(join(dir, 'backups'))).toHaveLength(8)
    expect(readdirSync(`${dataFile}.backups`)).toHaveLength(1)
  })
})

describe('server shutdown', () => {
  it('does not rewrite anything when no write is pending', async () => {
    writeFileSync(dataFile, GOOD)
    writeFileSync(bakFile, '{"older":"rollback copy"}')

    const exited = start()
    await waitUntilListening()
    child.kill('SIGTERM')
    await exited

    expect(readFileSync(dataFile, 'utf8')).toBe(GOOD)
    expect(readFileSync(bakFile, 'utf8')).toBe('{"older":"rollback copy"}')
  })

  it('flushes a pending write before exiting', async () => {
    writeFileSync(dataFile, GOOD)

    const exited = start()
    await waitUntilListening()
    await fetch(`http://127.0.0.1:${port}/api/persist/some_key`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ value: 'saved' }),
    })
    child.kill('SIGTERM')
    await exited

    expect(JSON.parse(readFileSync(dataFile, 'utf8')).some_key).toBe('saved')
    expect(readFileSync(bakFile, 'utf8')).toBe(GOOD) // previous version kept as the rollback copy
  })
})

describe('saving', () => {
  it('confirms a write only after it is on disk', async () => {
    writeFileSync(dataFile, GOOD)
    start()
    await waitUntilListening()

    const res = await fetch(`http://127.0.0.1:${port}/api/persist/some_key`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ value: 'saved' }),
    })

    expect(res.status).toBe(200)
    expect(JSON.parse(readFileSync(dataFile, 'utf8')).some_key).toBe('saved')
  })

  it('reports a write it could not put on disk as a failure', async () => {
    // A directory where the backup copy should go makes every flush fail, on any filesystem and as any user.
    writeFileSync(dataFile, GOOD)
    mkdirSync(bakFile)
    start()
    await waitUntilListening()

    const res = await fetch(`http://127.0.0.1:${port}/api/persist/some_key`, {
      method: 'POST',
      headers,
      body: JSON.stringify({ value: 'saved' }),
    })

    expect(res.status).toBe(507)
    expect(JSON.parse(readFileSync(dataFile, 'utf8')).some_key).toBeUndefined()
  })
})

describe('with no API key', () => {
  const lanAddress = () => Object.values(networkInterfaces()).flat().find((a) => a && a.family === 'IPv4' && !a.internal)?.address

  it('in dev answers on this machine only', async () => {
    writeFileSync(dataFile, GOOD)
    port = nextPort++
    child = spawn(process.execPath, [SERVER], {
      cwd: dir,
      env: { PATH: process.env.PATH, DATA_FILE: dataFile, PORT: String(port) },
      stdio: 'ignore',
    })
    await waitUntilListening()
    const lan = lanAddress()
    if (!lan) return // no network interface to test from
    await expect(fetch(`http://${lan}:${port}/api/health`)).rejects.toThrow()
  })
})

describe('the generated API key', () => {
  const prodEnv = () => ({ PATH: process.env.PATH, DATA_FILE: dataFile, PORT: String(port), NODE_ENV: 'production' })
  const keyFile = () => join(`${dataFile}.backups`, '.api-key')
  const statusWith = async (key) => (await fetch(`http://127.0.0.1:${port}/api/persist/k`, { headers: { 'X-API-Key': key } })).status
  const cli = (command, env = prodEnv()) => spawnSync(process.execPath, [SERVER, command], { cwd: dir, env, encoding: 'utf8', timeout: 5000 })

  function startProd() {
    port = nextPort++
    child = spawn(process.execPath, [SERVER], { cwd: dir, env: prodEnv(), stdio: ['ignore', 'pipe', 'pipe'] })
    let output = ''
    child.stdout.on('data', (d) => { output += d })
    child.stderr.on('data', (d) => { output += d })
    const exited = new Promise((resolve) => child.once('exit', resolve))
    return { exited, output: () => output }
  }

  beforeEach(() => writeFileSync(dataFile, GOOD))

  it('is created on first start, readable only by its owner, and is the only key accepted', async () => {
    startProd()
    await waitUntilListening()

    const key = cli('show-key').stdout.trim()
    expect(key).toMatch(/^[0-9a-f]{64}$/)
    expect(statSync(keyFile()).mode & 0o777).toBe(0o600)
    expect(await statusWith(key)).toBe(200)
    expect(await statusWith('wrong')).toBe(401)
    expect(await statusWith('')).toBe(401)
  })

  it('never reaches the logs', async () => {
    const { output } = startProd()
    await waitUntilListening()
    expect(output()).toContain('show-key')
    expect(output()).not.toContain(cli('show-key').stdout.trim())
  })

  it('survives a restart', async () => {
    const first = startProd()
    await waitUntilListening()
    const key = cli('show-key').stdout.trim()
    child.kill('SIGTERM')
    await first.exited

    startProd()
    await waitUntilListening()
    expect(await statusWith(key)).toBe(200)
  })

  it('rotates on a running server: the new key works at once and the old one stops', async () => {
    startProd()
    await waitUntilListening()
    const oldKey = cli('show-key').stdout.trim()

    const newKey = cli('rotate-key').stdout.trim()

    expect(newKey).toMatch(/^[0-9a-f]{64}$/)
    expect(newKey).not.toBe(oldKey)
    expect(await statusWith(newKey)).toBe(200)
    expect(await statusWith(oldKey)).toBe(401)
    expect(statSync(keyFile()).mode & 0o777).toBe(0o600)
  })

  it('fails closed if the key file disappears while running', async () => {
    startProd()
    await waitUntilListening()
    const key = cli('show-key').stdout.trim()
    rmSync(keyFile())

    expect(await statusWith(key)).toBe(401)
    expect(await statusWith('')).toBe(401)
  })

  it('refuses to start in production when it cannot be saved', async () => {
    writeFileSync(`${dataFile}.backups`, 'a file where the folder should be')
    const { exited } = startProd()
    expect(await exited).toBe(1)
  })

  it('is not used when PERSIST_API_KEY is set, and rotating then says why not', () => {
    const result = cli('rotate-key', { ...prodEnv(), PERSIST_API_KEY: API_KEY })
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('PERSIST_API_KEY')
    expect(existsSync(keyFile())).toBe(false)
  })

  it('does not start a second server on an unknown command', () => {
    const result = cli('rotate-kye')
    expect(result.status).toBe(1)
    expect(result.stderr).toContain('rotate-key')
  })
})

describe('the page the server serves', () => {
  let dist
  const pageEnv = () => ({ PATH: process.env.PATH, DATA_FILE: dataFile, PORT: String(port), NODE_ENV: 'production', DIST_DIR: dist })
  const startPage = () => {
    port = nextPort++
    child = spawn(process.execPath, [SERVER], { cwd: dir, env: pageEnv(), stdio: 'ignore' })
  }
  const currentKey = () => spawnSync(process.execPath, [SERVER, 'show-key'], { cwd: dir, env: pageEnv(), encoding: 'utf8', timeout: 5000 }).stdout.trim()
  const servedKey = (html) => /__STOCK_TRACKER_API_KEY__="([^"]*)"/.exec(html)?.[1]

  beforeEach(() => {
    writeFileSync(dataFile, GOOD)
    dist = join(dir, 'dist')
    mkdirSync(dist)
    writeFileSync(join(dist, 'index.html'), '<html><head><title>t</title></head><body></body></html>')
    writeFileSync(join(dist, 'app.js'), 'console.log(1)')
  })

  it.each(['/', '/index.html', '/any/spa/route'])('carries the current key at %s, uncached', async (path) => {
    startPage()
    await waitUntilListening()

    const res = await fetch(`http://127.0.0.1:${port}${path}`)

    expect(servedKey(await res.text())).toBe(currentKey())
    expect(res.headers.get('cache-control')).toBe('no-store')
    expect(res.headers.get('content-type')).toContain('text/html')
  })

  it('carries the new key after a rotation', async () => {
    startPage()
    await waitUntilListening()
    const rotated = spawnSync(process.execPath, [SERVER, 'rotate-key'], { cwd: dir, env: pageEnv(), encoding: 'utf8', timeout: 5000 }).stdout.trim()

    expect(servedKey(await (await fetch(`http://127.0.0.1:${port}/`)).text())).toBe(rotated)
  })

  it('still serves other static files untouched', async () => {
    startPage()
    await waitUntilListening()
    expect(await (await fetch(`http://127.0.0.1:${port}/app.js`)).text()).toBe('console.log(1)')
  })
})

describe('the key in the page and the Host header', () => {
  it('stays out of a page requested under a public name, as DNS rebinding would', async () => {
    const dist = join(dir, 'dist')
    mkdirSync(dist)
    writeFileSync(join(dist, 'index.html'), '<html><head></head><body></body></html>')
    writeFileSync(dataFile, GOOD)
    port = nextPort++
    child = spawn(process.execPath, [SERVER], {
      cwd: dir,
      env: { PATH: process.env.PATH, DATA_FILE: dataFile, PORT: String(port), NODE_ENV: 'production', DIST_DIR: dist },
      stdio: 'ignore',
    })
    await waitUntilListening()

    // fetch() will not send a custom Host header.
    const pageFor = (host, headers = {}) => new Promise((resolve, reject) => {
      request({ host: '127.0.0.1', port, path: '/', headers: { Host: host, ...headers } }, (res) => {
        let body = ''
        res.on('data', (d) => { body += d })
        res.on('end', () => resolve(body))
      }).on('error', reject).end()
    })

    expect(await pageFor('evil.example:4000')).not.toContain('__STOCK_TRACKER_API_KEY__')
    expect(await pageFor('192.168.0.103:4000')).toContain('__STOCK_TRACKER_API_KEY__')
    // Tailscale Funnel: a public visitor arrives under a .ts.net name from a local proxy.
    expect(await pageFor('casa.tail1234.ts.net', { 'Tailscale-Funnel-Request': '?1' })).not.toContain('__STOCK_TRACKER_API_KEY__')
    expect(await pageFor('192.168.0.103:4000', { 'X-Forwarded-For': '8.8.8.8' })).not.toContain('__STOCK_TRACKER_API_KEY__')
  })
})

describe('isLocalHostHeader', () => {
  it.each([
    '192.168.0.103:4000', '100.64.0.1:4000', 'localhost:4000', '127.0.0.1', '[fd7a:115c:a1e0::1]:4000',
    'casaos:4000', 'CasaOS.local:4000', 'casa.tail1234.ts.net:4000',
  ])('accepts %s', (host) => expect(isLocalHostHeader(host)).toBe(true))

  it.each([
    'evil.example:4000', '8.8.8.8:4000', '[2001:4860:4860::8888]:4000', 'casaos.local.evil.example',
    'ts.net.evil.example', 'evil.example/@192.168.0.103', '', undefined,
  ])('refuses %s', (host) => expect(isLocalHostHeader(host)).toBe(false))
})

describe('isPrivateAddress', () => {
  it.each([
    '127.0.0.1', '10.1.2.3', '172.16.0.1', '172.31.255.255', '172.17.0.1', '192.168.0.50',
    '100.64.0.1', '100.127.255.255', '::1', 'fd7a:115c:a1e0::1', 'fe80::1',
    '::ffff:192.168.0.50', '::ffff:100.64.0.1',
  ])('accepts %s', (ip) => expect(isPrivateAddress(ip)).toBe(true))

  it.each([
    '8.8.8.8', '172.32.0.1', '192.169.0.1', '100.128.0.1', '100.63.255.255', '2001:4860:4860::8888',
    '::ffff:8.8.8.8', 'evil.example', '', undefined,
  ])('refuses %s', (ip) => expect(isPrivateAddress(ip)).toBe(false))
})

describe('a lost store is not a first run', () => {
  const backup = (name, content) => {
    mkdirSync(`${dataFile}.backups`, { recursive: true })
    writeFileSync(join(`${dataFile}.backups`, name), content)
  }

  it('restores a missing file from the newest dated backup', async () => {
    backup('data-2026-01-01.json', '{"stock_tracker_portfolios":"[{\\"id\\":\\"old\\"}]"}')
    backup('data-2026-01-02.json', GOOD)
    start()
    await waitUntilListening()

    expect(await getValue('stock_tracker_portfolios')).toContain('Real')
    expect(JSON.parse(readFileSync(dataFile, 'utf8')).stock_tracker_portfolios).toContain('Real')
  })

  it('restores a file that lost its portfolio list, keeping that file', async () => {
    writeFileSync(dataFile, '{"devices":[]}')
    backup('data-2026-01-02.json', GOOD)
    start()
    await waitUntilListening()

    expect(await getValue('stock_tracker_portfolios')).toContain('Real')
    expect(readdirSync(dir).filter((f) => f.startsWith('data.json.corrupt-'))).toHaveLength(1)
  })

  it('falls back to a dated backup when the file and .bak are both unreadable', async () => {
    writeFileSync(dataFile, '{broken')
    writeFileSync(bakFile, '{also broken')
    backup('data-2026-01-02.json', GOOD)
    start()
    await waitUntilListening()

    expect(await getValue('stock_tracker_portfolios')).toContain('Real')
  })

  it('still treats no file and no backups as a first run', async () => {
    start()
    await waitUntilListening()
    expect(await getValue('stock_tracker_portfolios')).toBeNull()
  })

  it('does not restore over a new install whose backups hold no portfolios either', async () => {
    writeFileSync(dataFile, '{"devices":[]}')
    backup('data-2026-01-02.json', '{"devices":[]}')
    start()
    await waitUntilListening()
    expect(await getValue('stock_tracker_portfolios')).toBeNull()
    expect(readdirSync(dir).filter((f) => f.startsWith('data.json.corrupt-'))).toHaveLength(0)
  })
})

describe('versions', () => {
  const read = async (key) => (await fetch(`http://127.0.0.1:${port}/api/persist/${key}`, { headers })).json()
  const write = (key, body) => fetch(`http://127.0.0.1:${port}/api/persist/${key}`, {
    method: 'POST', headers, body: JSON.stringify(body),
  })

  beforeEach(async () => {
    writeFileSync(dataFile, GOOD)
    start()
    await waitUntilListening()
  })

  it('refuses a write based on an older version, and leaves the value alone', async () => {
    const { version } = await read('k')
    expect((await write('k', { value: 'first', baseVersion: version })).status).toBe(200)
    expect((await write('k', { value: 'second', baseVersion: version })).status).toBe(409)
    expect((await read('k')).value).toBe('first')
  })

  it('accepts a write based on the current version', async () => {
    await write('k', { value: 'first' })
    const { version } = await read('k')
    expect((await write('k', { value: 'second', baseVersion: version })).status).toBe(200)
  })

  it('still accepts a client that sends no version', async () => {
    await write('k', { value: 'first' })
    expect((await write('k', { value: 'second' })).status).toBe(200)
  })
})
