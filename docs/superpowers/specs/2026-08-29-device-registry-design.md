# Device registry: connected-devices panel (web) + disconnect button (Android)

## Problem

The persist server (`server/index.js`) is a single-shared-secret key-value store with
no concept of *which device* is talking to it — every client (the web app, the
Android app, any number of installs) authenticates with the same `X-API-Key` and is
otherwise indistinguishable. Two things are missing:

- No way to see, from the web app, what devices have synced with this server or when
  they last did.
- No way, from the Android app, to stop syncing and disassociate the device from the
  server (e.g. before wiping/selling the phone) short of clearing app data entirely.

## Goals

- A device registry on the server: every client that talks to it registers itself and
  is identifiable by a human-readable label, platform, and last-seen time.
- A **Devices** panel in the web app, next to the existing 🔑 API key button, listing
  registered devices with basic details, editable labels, and removal.
- A **Disconnect** button in the Android Settings screen that stops the app syncing
  and best-effort removes its own device row from the server.

## Non-goals

- No real-time/live "connected" status — this app has no persistent connection
  (plain HTTP, hit on-edit and on-foreground). "Connected" means "last successfully
  synced at T"; no websockets, no presence.
- No per-device authentication/authorization. All devices still share the single
  `PERSIST_API_KEY`. Device IDs are self-reported by the client, not cryptographically
  verified — sufficient for a personal single-user app, not a security boundary.
- No device-scoped data (e.g. per-device settings stored server-side). The registry
  is purely descriptive metadata about who's been syncing.
- No automated expiry/pruning of stale devices — removal is manual only (the × button
  / Android's Disconnect), matching the "last-seen based, no real-time" scope.

## Design

### Data model

New in-memory array on the server, alongside the existing generic `store` object,
persisted through the exact same debounced-flush + `.bak` + daily-backup machinery
`server/index.js` already has for `data.json` (`store.devices` is just another field
on the same object that already gets `JSON.stringify`'d to disk — no new file, no new
flush logic):

```ts
interface DeviceEntry {
  id: string          // client-generated, stable per install/browser; not verified
  label: string        // auto-guessed on first heartbeat, user-editable after
  platform: 'web' | 'android'
  firstSeen: string     // ISO timestamp, set once on creation
  lastSeen: string       // ISO timestamp, updated on every heartbeat
}
```

### Server endpoints (`server/index.js`)

All four routes sit under the existing `requireApiKey` guard (add `/api/devices` to
the `app.use(requireApiKey)` list alongside `/api/persist`).

- **`POST /api/devices/heartbeat`** — body `{ id: string, label?: string, platform: 'web' | 'android' }`.
  - If `id` is new: create `{ id, label: label ?? 'Unknown device', platform, firstSeen: now, lastSeen: now }`.
  - If `id` exists: update `lastSeen = now` only. **`label` in the request body is
    never applied to an existing row** — this is what preserves a user's rename
    against being clobbered by the client's own auto-guess on its next heartbeat.
  - Response: `{ ok: true, device: DeviceEntry }`.
- **`GET /api/devices`** — `{ devices: DeviceEntry[] }`, sorted by `lastSeen` descending.
- **`PATCH /api/devices/:id`** — body `{ label: string }`. 404 if `id` not found.
  Response: `{ ok: true, device: DeviceEntry }`.
- **`DELETE /api/devices/:id`** — removes the row. **Idempotent**: returns `200 { ok: true }`
  even if the id was already absent — this is what makes Android's best-effort
  unregister-on-disconnect safe to call without checking existence first.

Every mutating route calls the existing `scheduleFlush()`.

### Web (`src/`)

- **`src/hooks/useDeviceRegistry.ts`** (new) — on mount:
  1. Read `localStorage['stock_tracker_device_id']`; if absent, `crypto.randomUUID()`
     and store it.
  2. Guess a label from `navigator.userAgent` (small pure helper, unit-tested —
     see Testing): browser name + OS, e.g. `"Chrome on Linux"`, `"Safari on macOS"`,
     falling back to `"Web browser"` if nothing recognized.
  3. `POST /api/devices/heartbeat` with `{ id, label, platform: 'web' }`, using the
     same `X-API-Key`-attaching fetch pattern `src/utils/storage.ts` already uses.
  4. Re-heartbeat every 5 minutes on an interval while the tab stays mounted (keeps
     `lastSeen` fresh across a long session without needing a live connection).
- **`src/components/DeviceListModal.tsx`** (new) — opened via a new button placed
  next to the existing 🔑 button that opens `ApiKeyModal` (same header area). On
  open: `GET /api/devices`, render one row per device:
  - platform icon (web/Android),
  - label — click to edit inline, `PATCH` on blur/confirm,
  - first-seen date (absolute, e.g. `2026-08-20`),
  - last-seen (relative, e.g. `"2 min ago"`, `"3 days ago"` — small pure helper,
    unit-tested),
  - `×` to remove — confirms, then `DELETE`s and refetches the list.
- Wire the new button into the same header component that currently renders the 🔑
  button (per `CLAUDE.md`, `ApiKeyModal` is opened from a header button — add
  alongside it, not inside it).

### Android (`android/`)

- **Device ID + label**: stored in the existing Settings `DataStore`
  (`feature/settings`). ID generated once via `UUID.randomUUID()` if absent. Label
  guessed once as `"Android · ${Build.MANUFACTURER} ${Build.MODEL}"`.
- **`DeviceApi`** (new, `core/network`) — small Retrofit interface mirroring the
  existing `PersistApi`'s style, hitting the four `/api/devices/*` routes above.
- **Heartbeat**: piggybacks on the app's existing sync cycle — call
  `DeviceApi.heartbeat(...)` right after each successful push/pull the
  `SyncCoordinator` already performs, rather than adding new background scheduling.
  Failures are swallowed (heartbeat is best-effort, never blocks or surfaces an
  error for the user's actual data sync).
- **Disconnect button** (`feature/settings`, below the existing Test connection /
  Sync now buttons): only enabled when a server URL is currently configured.
  Tapping it shows a confirm dialog ("Disconnect from server? This stops syncing
  until you reconnect in Settings."). On confirm:
  1. Best-effort `DeviceApi.delete(deviceId)` — failures swallowed (matches the
     server route's idempotent-delete design; the row is simply left behind if the
     server is unreachable, same as it would be if the app were just uninstalled).
  2. Clear the stored Server URL + API key from Settings `DataStore` — this is what
     actually stops the app from syncing.
  3. Clear the stored device ID — a future reconnect registers as a fresh device
     rather than resurrecting the old row's `firstSeen`.

### Testing

- **Server**: `server/index.js` has no existing test suite today (nothing in it is
  tested — confirmed by reading the file and `package.json`'s `test` script, which
  only runs the Vitest money-math suite). This feature won't introduce one either;
  verification is manual (`curl`), consistent with the rest of the file. Flagged
  here explicitly as an accepted gap, not a silent omission.
- **Web**: unit tests (new test file, same Vitest setup as `money.test.ts`) for the
  two non-trivial pure functions: the user-agent → label guesser, and the
  last-seen → relative-time formatter. These are the only logic worth testing in
  the feature; the modal/hook themselves are thin API-calling glue.
- **Android**: same two pure functions ported natively (label guess is trivial
  `Build` field interpolation, so only the relative-time formatter needs a unit
  test, mirroring the web one) — added to `core:calc` or wherever is the natural
  home for a small formatting util, following existing module conventions.

### Docs

`CLAUDE.md`'s "Storage layer" section documents every `/api/persist/*` key; add a
short note there (or a new subsection) for `/api/devices/*` and `store.devices`,
matching the file's existing thoroughness. Done as part of implementation, not this
spec.

## Open risk accepted

Two devices heartbeating in the exact same instant is not a race here — each
heartbeat is a single server-side upsert against `store.devices`, not a
client-side read-modify-write, so there is no lost-update window. This was the
specific trade-off the "dedicated endpoints" approach (chosen over reusing the
generic persist key-value store) was picked to avoid.
