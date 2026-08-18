# Mobile Sync Blueprint

**STOCK_APP_2 → Android Companion**

- Status: Planning
- Sheet: 2026-08-17
- Rev.: 1
- Scope: Full CRUD, offline-first, one repo

> This is a snapshot of the planning document written before the Android companion app was built. It records the agreed design and rationale at the time; it does not track what has since actually been implemented. Cross-check against the current code (and root `CLAUDE.md`) before treating any detail here as current state.

## Context — Why this sheet exists

STOCK_APP_2 is currently a browser-only portfolio tracker: a React SPA talking to a small Express "persist server" that stores everything as JSON blobs on disk. The ask is a native Android companion that reads and writes the *same* portfolio data, staying in sync with whatever the browser last saved. This sheet is the agreed build plan before any code is touched — four scope decisions locked in up front, then six phases in dependency order, each naming exactly the files it touches.

| # | Decision | Why |
|---|---|---|
| 01 | Repo layout — `/android` subfolder, same repo | One source of truth. No cross-repo drift as the server API evolves; the Gradle build sits beside the npm one without either noticing. |
| 02 | V1 scope — full CRUD parity | Add, edit, sell and delete positions from the phone, not just a read-only mirror of the web app. |
| 03 | Calculations — ported natively to Kotlin | P&L, IRR and dividend tax are computed on-device, so the app works with no connectivity — not fetched pre-computed from the server. |
| 04 | Server access — API-key auth added | The server currently has zero authentication. A phone reaching it — especially off the LAN — needs a lock on the door first. |

## Phase 0 · Prerequisite — Lock the door before handing out a second key

Today, `server/index.js` has no authentication or CORS layer at all — anything that can reach the port can read and overwrite every portfolio key. That was an acceptable posture when the only client was a browser on the same machine. It stops being acceptable the moment a phone talks to it, so this phase happens first and touches both clients.

### 01 — Middleware placement

A single `requireApiKey` middleware compares a request header against `process.env.PERSIST_API_KEY`. It's applied path-scoped in `server/index.js`, not blanket — inserted right before the existing Yahoo/Stooq proxy block:

```js
// server/index.js
app.use('/api/persist', requireApiKey)
app.use('/api/yahoo',   requireApiKey)
app.use('/api/stooq',   requireApiKey)

// static + SPA fallback stay unauthenticated —
// gating index.html is pointless once the key ships inside its own JS bundle
```

### 02 — Health check carve-out

The Docker `HEALTHCHECK` currently hits `GET /api/persist/stock_tracker_portfolios`, which is about to require a key. Rather than teach the healthcheck a secret, add an unauthenticated `GET /api/health → { ok: true }` ahead of the auth middleware, and repoint the healthcheck at it.

### 03 — Dev fails open, prod fails closed

If `PERSIST_API_KEY` is unset and the server isn't in production, the middleware becomes a no-op with a one-time console warning — so plain `npm run dev` keeps working with zero setup. If it's unset *and* `NODE_ENV=production`, the server logs an error and exits at startup rather than silently serving an unauthenticated instance reachable from a phone.

### 04 — Key distribution

`.env` (new, gitignored) + `.env.example` (committed):

| Variable | Read by | Purpose |
|---|---|---|
| `PERSIST_API_KEY` | Express, at runtime | Value the middleware checks incoming requests against |
| `VITE_PERSIST_API_KEY` | Vite, at build time | Same value, inlined into the browser bundle so `storage.ts` can send it |

`src/utils/storage.ts`'s `getItem`/`setItem` both add an `X-API-Key` header read from `import.meta.env.VITE_PERSIST_API_KEY`. No changes needed in `vite.config.ts` — its dev proxy forwards all incoming headers by default.

> **Gotcha · build-time vs runtime**
> `VITE_*` vars are baked into the static JS at `npm run build` time — inside the Dockerfile's *builder* stage. `docker-compose.yml`'s `environment:` block only sets *runtime* vars for the already-built container; it can't reach back into bytes already written to `dist/assets/*.js`.
>
> So the Dockerfile's builder stage needs `ARG VITE_PERSIST_API_KEY` + `ENV VITE_PERSIST_API_KEY=$VITE_PERSIST_API_KEY` before `RUN npm run build`, and `docker-compose.yml` needs a `build.args` block forwarding it — in addition to the existing runtime `PERSIST_API_KEY`. A plain `docker build` needs `--build-arg VITE_PERSIST_API_KEY=…` added to the documented command.

> **Accepted tradeoff**
> The key is visible in browser devtools once the page loads — this is a personal, single-user app, so the point of the key is keeping anonymous internet scanners off the bare API port, not withstanding an attacker who already has page access. If this is ever exposed beyond a LAN or Tailscale tailnet, put HTTPS in front of it so the header isn't sent in cleartext — that's infra outside this repo.

## Phase 1 — A right-sized module tree

The reference NowInAndroid pattern — convention plugins, `feature:api`/`impl` splits, a module per concern — is built for large multi-team apps. This is one developer's personal-finance app. The layering principles carry over; the ceremony doesn't.

```
/android
  app                     MainActivity, NavHost, DI root
  core/
    model                 pure Kotlin data classes — zero Android deps
    calc                  plain JVM module — ported xirr / fifo / dividends / row math
    database              Room entities, DAOs, TypeConverters
    network               Retrofit PersistApi (server) + direct Yahoo/Stooq clients (no server hop)
    import                on-device PDF/XLSX/CSV parsing, all five broker formats
    data                  repositories, settings DataStore, sync workers
    designsystem          Material3 theme + shared composables
  feature/
    portfolio             list, cards, add/edit/sell, ViewModels
    settings              server URL, API key, display currency
```

### Cut — Deliberately left out, and why

- **No `build-logic` convention plugins.** They pay for themselves once you're de-duplicating `android {}` blocks across dozens of modules maintained by a team. Copy-pasting a 15-line block into eight modules costs less than building plugin infrastructure for one.
- **No `feature:*:api`/`impl` split.** That split exists so unrelated features can't reach into each other's internals across team boundaries. With two features and one developer, `:app` wires navigation directly — nothing to protect against.
- **No standalone testing/common/ui/datastore/sync modules.** Settings prefs and the sync worker fold into `core:data`; test doubles live in each module's own `src/test` until something actually needs to share one.

`gradle/libs.versions.toml` — baseline (verify current stable before running):

| Library | Version | Library | Version |
|---|---|---|---|
| Kotlin | `1.9.22` | Compose BOM | `2024.02.00` |
| Hilt | `2.50` | Room | `2.6.1` |
| Retrofit | `2.9.0` | OkHttp | `4.12.0` |
| WorkManager | `2.9.0` | Coroutines | `1.7.3` |

Retrofit pairs with `kotlinx.serialization` rather than Gson — one JSON library across the whole stack, no reflection-based parsing.

> **Required, not optional**
> Add `android/` to root `.dockerignore`. The Dockerfile's builder stage does `COPY . .` before `npm run build` — without this line, every `docker build` ships the entire Gradle project (caches included) into a build context that never needs any of it. `.gitignore` alone doesn't affect Docker's build context, only `.dockerignore` does. Also append to `.gitignore`: `android/.gradle/`, `android/local.properties`, `android/**/build/`, `android/.idea/`, `android/*.apk`, `android/*.aab`.

## Phase 2 — Teaching Kotlin the same math, exactly

Four TypeScript files carry the financial logic today — `xirr.ts`, `fifoMatcher.ts`, `dividends.ts`, and the closed-position row derivation inline in `PortfolioContent.tsx`. Each gets a field-for-field, step-for-step Kotlin port in `core:calc`, verified against the same fixtures `money.test.ts` already uses. This phase also draws the line on what the app actually needs the server for — which turns out to be almost nothing.

### 00 — What actually needs the server

The server's only job is synchronization:

| Capability | Needs your server? | How it works standalone |
|---|---|---|
| View / add / edit / sell / delete positions | **No** | Room is local; always available |
| P&L, IRR, dividend-tax math | **No** | `core:calc`, this phase |
| Live quotes, FX rates, dividend history | **No** | Phone calls Yahoo/Stooq directly — see below |
| ISIN/ticker lookup (Add Position autofill, import enrichment) | **No** | Same direct Yahoo search call |
| Import (PDF/XLSX/CSV statement parsing) | **No** | Fully on-device, this phase — see §07 below |
| Syncing with the web app / other devices | **Yes** | The four keys from Phase 3, and nothing else |

Quotes, FX and dividends go straight to Yahoo/Stooq from the phone, the same endpoints your server's `proxyRequest()` already forwards to — just without the extra hop. The one wrinkle: Yahoo blocks requests that don't look like a browser, which is why the server sets a browser-like `User-Agent` today. The phone's OkHttp client needs the same interceptor. Not a blocker, just a detail that has to be there from the start or quotes silently 403.

> **Consequence for Phase 0**
> Since Android never calls `/api/yahoo` or `/api/stooq` at all, guarding those two routes is no longer something Android's client code needs to satisfy — it's purely general hardening for the server itself, worth keeping since the server is now reachable beyond localhost regardless, but not a dependency the app carries.

### 01 — Room schema

`core:database` — entities mirror `src/types/index.ts`:

| Entity | Key fields | Mirrors |
|---|---|---|
| `PortfolioEntity` | id (PK), name | `usePortfolios.ts` |
| `PositionEntity` | id (PK), portfolioId (indexed), ticker, name, type, quantity, buyPrice, buyDate, currency, broker?, isin?, sellPrice?, sellDate? | `Position` |
| `ManualPriceEntity` | portfolioId + ticker (composite PK), price, updatedAt | `useManualPrices.ts` |
| `DivTaxOverrideEntity` | portfolioId + ticker + date (composite PK), rate | `useManualDividendTaxes.ts` |

Dates stay as plain `yyyy-MM-dd` strings, not a parsed `Instant`. The FIFO matcher and row derivation both compare dates lexicographically (string comparison) — the Kotlin port only matches if it does the same.

### 02 — Retrofit contract — the double-encoding

`PersistApi.get(key)` returns a `value: String?`; `PersistApi.set(key, body)` sends `{ value: String }`. In both directions, that `value` is itself a JSON-encoded string — the array/object it represents needs a *second* decode.

> **Single most likely implementation bug**
> Write: `Json.encodeToString(positions)` first, then wrap the resulting string as `PersistBody(value = thatString)`. Read: `response.value` is a raw string — decode it *again* with `Json.decodeFromString<List<Position>>(response.value)` to get the actual array. Skipping the second decode is the one bug worth flagging in review before anything else.

Server URL and API key are user settings, not build constants — an OkHttp interceptor rewrites the request's host/scheme/port and adds `X-API-Key` from DataStore on every call, so changing either in Settings takes effect without restarting the app.

### 03 — XIRR solver

Newton–Raphson with bisection fallback, solving Σ CFᵢ / (1+r)^yearsᵢ = 0. Fewer than two cash flows returns `null`. `yearsᵢ` is each flow's distance from the first flow's date, in 365.25-day years. Newton starts at `r = 0.1`, up to 200 iterations, converging when `|f(r)| < 1e-8`; it falls through to bisection on a zero derivative, a non-finite step, or a step landing at `r ≤ -1`. Bisection brackets `[-0.999, 10]`, returns `null` if the interval doesn't bracket a root, and converges when the interval narrows below `1e-8`.

JUnit fixtures, ported from `money.test.ts`:

| Cash flows | Expected |
|---|---|
| −1000 @ 2020-01-01, +1100 @ 2021-01-01 | ≈ 0.10 |
| −1000 @ 2020-01-01, +900 @ 2021-01-01 | ≈ −0.10 |
| single cash flow | `null` |

### 04 — FIFO matcher

Buy/sell rows group by ticker; buys and sells each sort ascending by date. A ticker with no sells emits every buy unchanged. Otherwise, a mutable queue of buys (each carrying a `remaining` counter) is consumed oldest-first by each sell in date order — a sell that exceeds one lot's remaining quantity spills into the next lot, emitting one closed `Position` per lot touched. Leftover queue entries after all sells are open lots. The boundary epsilon is `1e-6` throughout — it must match exactly, since it governs partial-lot consumption.

JUnit fixtures:

| Input | Output |
|---|---|
| two buys, no sells | 2 open lots |
| buy qty10@100 + sell qty4@150 | closed (qty4, buy100, sell150) + open (qty≈6, buy100) |
| buys (qty5@100, qty5@200) + sell qty7@300 | closed #1 (qty5, buy100, full) + closed #2 (qty2, buy200, partial) + open (qty≈3, buy200) |

### 05 — Dividend withholding tax

`COUNTRY_WITHHOLDING_RATES` — port verbatim, default = CZ:

| Country | Rate | Country | Rate | Country | Rate | Country | Rate |
|---|---|---|---|---|---|---|---|
| CZ | 15% | AT | 27.5% | BE | 30% | DE | 26.37% |
| DK | 27% | ES | 19% | FI | 20% | FR | 12.8% |
| HU | 0% | IE | 0% | IT | 26% | LU | 0% |
| NL | 15% | NO | 15% | PL | 19% | PT | 25% |
| SE | 30% | SI | 15% | SK | 15% | CH | 35% |
| GB | 0% | US | 15% | JP | 15.315% | | |

`TICKER_COUNTRY` (port verbatim): `VIG.PR→AT, EXUS.DE→IE, 4GLD.DE→DE, UCG.MI→IT, DTE.DE→DE, 8306.T→JP, 8591.T→JP, CSG.AS→NL`.

`calcNetDividends` sums `shares × amount × (1 − rate)` per event, where `rate` is the per-event tax override if one exists, else the country lookup, and `shares` counts lots held on the ex-date — bought on or before it, and (if sold) sold *strictly after* it. A lot sold exactly on the ex-date does not receive that dividend.

JUnit fixtures:

| Scenario | Net |
|---|---|
| 10 shares, $10 div, unmapped ticker (15% default) | 85.00 |
| lot bought after the ex-date | 0 |
| lot sold before the ex-date | 0 |
| UCG.MI (Italy, 26%) | 74.00 |
| per-event override, rate = 0 | 100.00 |

### 06 — Row derivation — the complex one

Per ticker group: `openLots` are lots missing a truthy `sellPrice` or `sellDate`; `closedLots` are lots with both. `isClosed = openLots.isEmpty()`. All amounts convert into the row's native currency (the first lot's currency) before summing.

> **Quirk to replicate, not fix**
> The source's falsy check means a lot with `sellPrice = 0` lands in *both* groups simultaneously — an extreme, practically irrelevant edge case (selling at literally zero), but port it as-is for exact parity with the web app's numbers.

`currentPrice` falls back through `quote → manual price → average buy price`, never a bare zero. `realizedPnl + unrealizedPnl = pricePnl`; `pricePnl + dividendIncome = totalReturn`. Per-row IRR only computes when a usable price exists (closed, or an unloaded quote/manual price present) — cash flows are buy outflows, sell inflows, per-lot dividend inflows (same held-on-ex-date rule as above), and one terminal inflow at today's value if still open.

**Portfolio-level IRR is a separate aggregation**, not an average of per-row IRRs — XIRR isn't additive. It gathers every cash flow across every position, converted to the display currency, plus one terminal inflow of total current value, and solves once.

### 07 — Import — on-device, full coverage from day one

`src/utils/importParser.ts` and its dependents are five broker-specific parsers — Fio banka PDF, Revolut trading statements and its XAU special case, XTB cash-ops XLSX, Trading 212 CSV, Degiro CSV — plus a generic PDF heuristic and a column-mapping wizard for anything else, built on Czech number formats, multilingual keyword matching, and pdfjs-dist layout heuristics. Since the app has to work with zero server connectivity, all of it ports to Kotlin, in `core:import`. This is honestly the single largest chunk of engineering in the whole project — bigger than the calc-logic port — because unlike the calc math, most of the effort here is in extraction mechanics, not just translating algorithms.

`core:import` — what replaces what:

| Today (browser) | On Android | Effort |
|---|---|---|
| pdfjs-dist text + position extraction | **PdfBox-Android** (`com.tom-roush:pdfbox-android`), custom `PDFTextStripper` replicating the Y/X line-grouping (±3 unit threshold) | Largest single piece |
| `xlsx` (SheetJS) | A lightweight streaming reader (e.g. `fastexcel-reader`) rather than full Apache POI — POI runs on Android but costs 10+ MB and desugaring for a feature that only needs to read cell values | Moderate |
| hand-rolled CSV parsing | Trivial — RFC 4180-aware split, no library needed | Small |
| Fio banka / Revolut / XTB / T212 / Degiro heuristics | Mechanical 1:1 port — regex and date/keyword rules, same algorithmic content, no browser APIs involved | Large in volume, low in design risk |
| `batchIsins` / `batchTickers` (Yahoo search) | Direct Yahoo search call from `core:network`, per the table above | Small — shared with Add Position autofill |
| `ColumnMappingModal` | A Compose screen mirroring it 1:1: preview table, field dropdowns, skip-rows control, currency/broker defaults | Moderate, no algorithm risk |

Output is the same `ParseResult`/`NeedsMapping` shape the web app already produces — positions ready to insert into Room through the normal add pathway, then synced out via Phase 3 whenever connectivity exists. Nothing about import blocks on the network at any step.

## Phase 3 — Whole-array sync, honestly

The server's storage granularity is one whole JSON array per key — there's no per-record endpoint. Room is the offline-first source of truth for the UI; every screen observes DAO `Flow`s, and mutations write to Room synchronously before anything touches the network — the same "local first, server best-effort" shape `storage.ts` already uses.

### Push — Any local mutation, including while offline

Add/edit/sell/delete a position, rename a portfolio, set a manual price — each commits to Room *immediately*, with no network required, then marks that key dirty in a small sync-outbox table and enqueues a `WorkManager OneTimeWorkRequest` (unique name = the affected key, `REPLACE` policy, constrained to `NetworkType.CONNECTED`) that re-serializes the **entire current array for that one key** and posts it. Never a delta — that matches the server's model exactly. A request queued while offline sits blocked on its network constraint; WorkManager persists it to its own on-disk store, so it survives the app being closed or the phone rebooting, and fires on its own the moment connectivity returns — no need to reopen the app. On success, the worker clears that key's outbox entry.

### Pull — App foreground, manual refresh, periodic safety net

On `ON_START` and on pull-to-refresh, relevant keys refetch and overwrite Room — "server wins" once the fetch resolves, mirroring the two-phase init every existing hook (`usePortfolio`, `usePortfolios`, …) already uses. An optional 15-minute `PeriodicWorkRequest` catches edits made from the browser while the phone is closed; it's a cheap add-on, not core to v1.

### Race — Offline add, then reconnect

Reconnecting can trigger two syncs at once: the queued offline push, and a foreground pull if the app happens to be opened at that moment. Without a guard, the pull could fetch the server's still-stale data (it doesn't have the offline add yet) and overwrite Room *before* the queued push has a chance to send it — silently erasing the add. The outbox from the Push step above is what prevents this: a pull is required to **skip overwriting any key currently marked dirty**, deferring to its pending push instead. Once that push succeeds and clears the flag, pulls resume normally for that key. The offline add always wins the race against a stale pull, regardless of exact timing.

### Merge — Two different additions from two different devices

The race guard above only protects a device from *its own* stale pull. It does nothing for the actual collision: add Position A on the web while the phone is offline, add Position B on the phone, reconnect. Without more than the outbox, the phone pushes the only array it knows — `[…existing, B]` — and that whole-array POST silently deletes A, which the phone never saw. Whichever device pushes last wins outright, and the other device's addition simply vanishes with no error.

The fix is a three-way merge at push time, keyed by each record's stable `id` (positions and portfolios have one; manual prices key by ticker; dividend-tax overrides key by ticker+date). Room keeps a small **last-synced snapshot** per key — the array as it stood after the last successful pull or push, i.e. the common ancestor. Right before pushing, the worker:

1. Fetches the server's *current* array for that key — the **remote** side.
2. Diffs its own Room state against the snapshot to isolate exactly what it changed locally — the **local** diff (added B).
3. Diffs the freshly fetched remote against the same snapshot to see what changed elsewhere — here, added A.
4. Replays both diffs onto the snapshot: additions and edits to *different* ids from each side both survive — result `[…existing, A, B]`. Pushes that.

Both positions survive. There's exactly one case this can't arbitrate on its own: the *same* id edited (or edited on one side and deleted on the other) independently by both devices while apart. With no per-record modification timestamp in the data model today, there's no principled way to know which edit is "newer." The difference from the old whole-array behavior is blast radius — a genuine same-record conflict touches *one row*, not the entire list — but naively auto-resolving even that one row is worth avoiding, for a concrete reason below.

**Concretely: a closed position edited on both devices.** Say a lot closed at sellPrice 100 before either device went offline. Web edits it to 105. Phone, offline, independently edits it to 110. Web reconnects first and pushes — no conflict yet, since from web's view the server still matches where it started, so 105 goes through clean. Phone reconnects later and pushes — *now* there's a real conflict: the server moved to 105 since phone's last snapshot, and phone also moved it, to 110. A naive fallback (last pusher wins) would silently overwrite the server back to 110, discarding web's edit even though web had already seen its own push succeed. Sell price and sell date drive realized P&L and portfolio IRR directly — losing an edit here isn't cosmetic, it silently changes numbers you'd otherwise trust without any indication anything happened.

That's specific enough to be worth solving properly rather than accepting: on a detected same-id conflict, the push pauses and surfaces a one-tap resolution instead of picking a winner — *"Sell price: yours 110.00 vs. server's 105.00 — keep yours / keep server's"* — before anything is written. Rare in practice for one user, but cheap to build given the merge already computes exactly this diff, and it turns a silent, invisible loss into a visible, deliberate choice.

**Manual prices are the one key where this resolves without a prompt at all.** `ManualPriceEntity` already carries `updatedAt` per ticker (Phase 2). Unlike a position edit, comparing `local.updatedAt` against `remote.updatedAt` on a same-ticker conflict is a *genuine* last-write-wins — it reflects when each edit actually happened, not an accident of which device happened to reconnect first. So the general rule: **a key with a real modification timestamp auto-resolves by recency, silently; a key without one surfaces the prompt.** Positions and portfolios have no `updatedAt` today, so they get the prompt. Dividend-tax overrides don't carry one either (`useManualDividendTaxes.ts` stores a bare `Record<string, number>`) — the same fix would work there, but it's a shape change to a key the web app also writes, not something to decide as a side effect of this plan. Left on the prompt fallback for now.

The merge is strictly ID-keyed, on purpose — never by name. A portfolio created "Retirement" on the phone and one already called "Retirement" on the server have different, independently generated IDs, so the merge treats them as two unrelated records: both survive, each keeping its own lots under its own `stock_tracker_positions_<id>` key. There's no merge step that ever touches two portfolios' positions at once, so they structurally can't get combined — not "the logic is careful not to," there's no path by which it could. You'd just see two tabs with the same label until you notice; consolidating a genuine mistake is a manual rename-and-move, never an automatic guess.

> **What's genuinely out of scope**
> A same-id conflict is now **surfaced**, not silently resolved — that's a required part of the push flow, not an optional extra. What's still out of scope is automatic *field-level* reconciliation (e.g. auto-accepting a broker-name edit while flagging only the sell price) — the whole record round-trips through one prompt. Real per-record modification timestamps and fine-grained merging aren't worth building for one user hitting this rarely.

- **"Last synced" timestamp** visible in the UI, plus a visible sync-failed state, so staleness is seen rather than silent.
- **Pull-before-edit** — refresh immediately before opening any Add/Edit/Sell form, shrinking the window for a same-record conflict to one network round trip in the first place.
- **Conflict-resolution prompt on push** (required, not optional) — the three-way merge already computes the exact diff needed; on a same-id conflict, pause and ask instead of overriding. Precedent already in the codebase: `usePortfolios.mutatePortfolios` re-fetches the portfolio list immediately before mutating for exactly this reason.
- **Duplicate-name hint** — if a merge ever results in two portfolios sharing a display name (different IDs, same label), flag it in the portfolio switcher so it's noticed immediately rather than discovered later. Purely a notice; consolidating is still a manual step.

## Phase 4 — Cards, not columns

The web table's 15 columns don't fit a phone. Position rows (`PortfolioRow`, not raw lots) become scrollable cards — ticker, type badge, current value with color-coded daily change, price P&L and total return, avg buy price, lot count, a dimmed SOLD badge with a show/hide-closed toggle. Tapping a card expands to lot detail and the dividend-event panel.

- **Portfolio switcher** — add/rename/delete (blocked below one remaining portfolio, same guard as the web), tap to switch active.
- **Add Position** — field-for-field mirror of `AddPositionModal`: ticker with Yahoo-search autofill, per-share/total price toggle, currency, broker suggestion chips, a "closed position" toggle for entering historical closed lots directly.
- **Import** — pick a file (system picker, or a share-sheet intent, see below), parsed entirely on-device by `core:import` from Phase 2 — no network required. Then the same portfolio-target and currency-override choices as the web's `ImportModal`. A `NeedsMapping` result opens a column-mapping screen mirroring `ColumnMappingModal`. Confirmed positions land in Room through the normal add pathway, then sync out like anything else, whenever connectivity exists.
- **Sell** — mirrors `SellPositionModal`: live P&L preview, same "stamp sellPrice/sellDate" semantics. No partial-lot-split support — an existing web limitation, not a new mobile gap.
- **Manual price entry** — for no-feed tickers, shows `updatedAt`, has a clear action.
- **Settings** — server URL, API key, display currency, "Test connection" against `/api/health`, last-synced timestamp, manual sync-now.

### Ideas — Import upgrades worth considering later

Not scoped into v1 — flagged here because they surfaced while designing the import screen, not because they're committed.

- **Share-sheet intent filter** — register for PDF/XLSX/CSV mime types so a statement received by email or another app can be sent straight to the "Import" screen, skipping the manual file picker.
- **On-device OCR for paper statements** — ML Kit text recognition on a photographed statement, feeding the same generic heuristic parser that already looks for an ISIN plus a buy/sell keyword plus a date plus numbers. A capability the browser can't offer at all.
- **Dedupe on re-import** — detect transactions already present (broker + ticker + date + qty + price) before appending, so re-uploading the same monthly statement doesn't double-count. Worth doing regardless of platform — it's a gap in the existing web import too, not something introduced here.
- **Direct broker API integration** (e.g. IBKR Flex Queries) — a longer-term replacement for statement parsing entirely. Meaningfully bigger scope, speculative, and worth noting it cuts against the offline-first principle above — a live broker API is one more thing that needs connectivity to work.

### Deferred — Not a v1 blocker

- Chart equivalents of `PriceChart` / `PortfolioPnLChart` / `PortfolioPieCharts` — needs a Compose charting library; summary cards already carry the core numbers without them.
- Column customization — not applicable to a card layout.

## Phase 5 — Prove the round trip

1. `./gradlew :core:calc:test` — plain-JVM JUnit tests against the Phase 2 fixtures, no emulator, same speed class as `vitest run`.
2. `docker compose up -d --build` with the Phase 0 build-arg/env wiring on host port 8080 — first confirm the *existing* web client and `/api/health` still work, before touching Android at all.
3. Emulator: server URL `http://10.0.2.2:8080` (the standard host alias), API key matching the running container.
4. Physical device: the host's LAN IP or Tailscale hostname, phone sharing the same network.
5. Round trip: add on phone → confirm in web app; edit on web → pull-to-refresh on phone → confirm match; sell on phone → confirm SOLD + realized P&L on web; set a manual price for a no-feed ticker on phone → confirm on web; restart the container after a phone-originated edit → confirm `server/data.json` retained it.
6. Re-check `docker build` context size is unaffected by `/android` once the Gradle project has real bulk — catches a missed `.dockerignore` entry.

## Reference — Critical files

- `server/index.js`
- `src/utils/storage.ts`
- `src/types/index.ts`
- `src/components/PortfolioContent.tsx`
- `src/utils/xirr.ts`
- `src/utils/fifoMatcher.ts`
- `src/utils/dividends.ts`
- `src/utils/money.test.ts`
- `docker-compose.yml`
- `Dockerfile`
- `.dockerignore`
- `.gitignore`
- `android/settings.gradle.kts` (new)
- `android/gradle/libs.versions.toml` (new)

---

*Mobile Sync Blueprint · STOCK_APP_2 — Six phases, dependency-ordered, written before any code existed.*
