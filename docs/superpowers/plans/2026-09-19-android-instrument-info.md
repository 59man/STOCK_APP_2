# Android Instrument Info Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add XTB-style instrument information to the position detail screen — About, Market hours in the user's own time zone, Essentials, and Rates of return — together with the time zone setting that the anchored-today's-change work later depends on.

**Architecture:** A new `InstrumentProfileClient` composes two Yahoo calls: the chart-meta call (always works) and a cookie/crumb-gated `quoteSummary` call (best-effort, every field nullable). Results cache in a new Room table for seven days. Market-hours and return math are pure functions in `core:calc`, so they are tested without Android. The UI is four collapsible cards in a new file, fed by their own ViewModel so the profile fetch stays off `PortfolioListViewModel`.

**Tech Stack:** Kotlin, OkHttp, kotlinx.serialization, Room, Hilt, Jetpack Compose, Robolectric, MockWebServer, Roborazzi.

**Spec:** `docs/superpowers/specs/2026-09-19-android-instrument-info-sort-logos-design.md` (Parts 1–3)

## Global Constraints

- All Gradle commands run from `android/`.
- Unit tests run on plain JVM: `./gradlew test`. No emulator.
- Tickers are stored raw and `URLEncoder.encode`d exactly once at the call site (CLAUDE.md `FX_CONVERTED_TICKERS` gotcha).
- Compose layout tests need `@GraphicsMode(GraphicsMode.Mode.NATIVE)`; `guardFonts` in `LayoutAssertions.kt` fails loudly without it.
- A glyph inside a fixed-size box pins both `fontSize` and `lineHeight` in dp-derived sp.
- Network clients go phone→Yahoo directly, never through the user's server (Mobile Sync Blueprint Phase 2 §00), and send the browser `User-Agent` already defined in `QuoteClient`/`HistoryClient`.
- Everything `quoteSummary` supplies is optional. A failure hides those rows; it never shows an error or blanks the screen.
- Commit after every task, Conventional Commits subject.

---

### Task 1: Profile model and chart-meta fetch

**Files:**
- Create: `core/model/src/main/kotlin/com/stocktracker/core/model/InstrumentProfile.kt`
- Create: `core/network/src/main/kotlin/com/stocktracker/core/network/InstrumentProfileClient.kt`
- Test: `core/network/src/test/kotlin/com/stocktracker/core/network/InstrumentProfileClientTest.kt`

**Interfaces:**
- Produces: `InstrumentProfile`, `TradingPeriod`, `TradingPeriods`, and `suspend fun InstrumentProfileClient.fetch(ticker: String): InstrumentProfile`.

**Background:** verified against the live API on 2026-09-19, `chart/<t>?interval=1d&range=1d` returns in `meta`: `fullExchangeName`, `instrumentType`, `currency`, `exchangeTimezoneName`, `gmtoffset`, `currentTradingPeriod.{pre,regular,post}.{start,end}`, `fiftyTwoWeekHigh/Low`, `regularMarketDayHigh/Low`, `regularMarketVolume`, `firstTradeDate`, `longName`.

- [ ] **Step 1: Write the model**

```kotlin
package com.stocktracker.core.model

data class TradingPeriod(val startEpoch: Long, val endEpoch: Long)

data class TradingPeriods(
    val pre: TradingPeriod? = null,
    val regular: TradingPeriod? = null,
    val post: TradingPeriod? = null,
)

/**
 * Everything the app knows about an instrument beyond its price.
 *
 * Fields above the divider come from the chart-meta call, which is the same request the quote
 * fetch already makes and always works. Fields below come from Yahoo's quoteSummary, which is
 * gated behind an undocumented cookie/crumb handshake — they are null whenever that fails, and
 * every consumer must hide the row rather than show a placeholder.
 */
data class InstrumentProfile(
    val ticker: String,
    val longName: String? = null,
    val exchange: String? = null,
    val instrumentType: String? = null,
    val currency: String? = null,
    val exchangeTimeZone: String? = null,
    val tradingPeriods: TradingPeriods? = null,
    val fiftyTwoWeekHigh: Double? = null,
    val fiftyTwoWeekLow: Double? = null,
    val dayHigh: Double? = null,
    val dayLow: Double? = null,
    val volume: Long? = null,
    val firstTradeDate: Long? = null,
    // ---- best-effort, quoteSummary ----
    val description: String? = null,
    val sector: String? = null,
    val industry: String? = null,
    val website: String? = null,
    val country: String? = null,
    val fundFamily: String? = null,
    val legalType: String? = null,
    val expenseRatio: Double? = null,
    val totalNetAssets: Double? = null,
    val category: String? = null,
    val fetchedAt: Long = 0L,
)
```

- [ ] **Step 2: Write the failing test**

Follow `PersistApiTest.kt`'s MockWebServer pattern. Assert: a full meta payload maps every field; a payload missing `currentTradingPeriod` yields `tradingPeriods == null` rather than throwing; a non-2xx throws; the request path contains the encoded ticker exactly once (`8306.T` stays `8306.T`, and a ticker with `=` encodes once).

- [ ] **Step 3: Implement the chart-meta half**, mirroring `HistoryClient.fetchRaw`'s shape and error handling.

- [ ] **Step 4: Run** `./gradlew :core:network:test --tests '*InstrumentProfileClientTest*'`

- [ ] **Step 5: Commit** — `feat(android): instrument profile model and chart-meta fetch`

---

### Task 2: quoteSummary behind the crumb handshake

**Files:**
- Create: `core/network/src/main/kotlin/com/stocktracker/core/network/CrumbProvider.kt`
- Modify: `InstrumentProfileClient.kt`
- Test: `core/network/src/test/kotlin/com/stocktracker/core/network/CrumbProviderTest.kt`, plus cases in `InstrumentProfileClientTest`

**Interfaces:**
- Produces: `class CrumbProvider(baseUrl: String, client: OkHttpClient)` with `suspend fun crumb(): String?` and `fun invalidate()`.

**Background:** `quoteSummary` returns `401 {"error":{"code":"Unauthorized","description":"Invalid Crumb"}}` when called plainly. It succeeds after GETting `https://fc.yahoo.com/` for a cookie, then `v1/test/getcrumb` with that cookie. Verified 2026-09-19. The cookie step itself answers 404 while still setting the cookie, so **do not** treat its status as success or failure.

- [ ] **Step 1: Write the failing CrumbProvider test** — MockWebServer serving the cookie and crumb endpoints. Assert: a crumb is returned; two concurrent callers trigger exactly one handshake (the `Mutex`); `invalidate()` forces a new one; a failed handshake returns null rather than throwing.

- [ ] **Step 2: Implement `CrumbProvider`** — in-memory cookie+crumb, one-hour expiry, `Mutex` so concurrent callers share one handshake.

- [ ] **Step 3: Write the failing quoteSummary tests** — assert `{raw, fmt}` unwrapping uses `raw`; a 401 followed by a success on retry with a fresh crumb fills the fields; a second 401 leaves them null **and leaves the chart-meta fields intact**; a response missing whole modules parses.

- [ ] **Step 4: Implement Call B and compose it into `fetch`** — wrap in `runCatching`, log, and return the meta-only profile on any failure.

- [ ] **Step 5: Run** `./gradlew :core:network:test`

- [ ] **Step 6: Commit** — `feat(android): fetch Yahoo instrument profile behind the crumb handshake`

---

### Task 3: Room table and migration

**Files:**
- Create: `core/database/src/main/kotlin/com/stocktracker/core/database/InstrumentProfileEntity.kt` (entity + DAO)
- Modify: `core/database/src/main/kotlin/com/stocktracker/core/database/StockTrackerDatabase.kt`
- Modify: `core/data/src/main/kotlin/com/stocktracker/core/data/di/DataModule.kt`
- Test: `core/database/src/test/kotlin/com/stocktracker/core/database/MigrationTest.kt`

**Interfaces:**
- Produces: `InstrumentProfileEntity` (PK `ticker`), `InstrumentProfileDao` with `suspend fun get(ticker: String)`, `fun observe(ticker: String): Flow<InstrumentProfileEntity?>`, `suspend fun upsert(entity: InstrumentProfileEntity)`; `val MIGRATION_1_2: Migration`.

Trading periods are stored as six nullable epoch columns rather than a serialized blob, so no TypeConverter is needed.

- [ ] **Step 1: Write the failing migration test** — open v1 with `MigrationTestHelper`, insert a portfolio and a position, migrate to v2, assert both rows survive and the new table exists and is writable.

Add `androidx.room:room-testing` to `core/database`'s test dependencies if it is not already present, and confirm `exportSchema = true` has produced `core/database/schemas/…/1.json` — the migration test needs it.

- [ ] **Step 2: Write the entity, DAO, bump the database to version 2, and write `MIGRATION_1_2`** as a single `CREATE TABLE IF NOT EXISTS`.

- [ ] **Step 3: Register it** — `.addMigrations(MIGRATION_1_2)` in `DataModule`. Do **not** add `fallbackToDestructiveMigration`: the database holds positions and unresolved sync conflicts.

- [ ] **Step 4: Run** `./gradlew :core:database:test`

- [ ] **Step 5: Commit** — `feat(android): instrument profile table with migration 1 to 2`

---

### Task 4: Repository with a seven-day cache

**Files:**
- Create: `core/data/src/main/kotlin/com/stocktracker/core/data/InstrumentProfileRepository.kt`
- Test: `core/data/src/test/kotlin/com/stocktracker/core/data/InstrumentProfileRepositoryTest.kt`

**Interfaces:**
- Produces: `fun observe(ticker: String): Flow<InstrumentProfile?>`, `suspend fun refreshIfStale(ticker: String)`.

- [ ] **Step 1: Write the failing test** with a fake client and an in-memory Room database. Assert: a cold ticker fetches and stores; a ticker cached one day ago does **not** refetch; a ticker cached eight days ago does; a fetch failure leaves the cached row intact and does not throw.

- [ ] **Step 2: Implement it.** Mapping between entity and model lives here, alongside the other mappers in `core:data`.

- [ ] **Step 3: Run** `./gradlew :core:data:test`

- [ ] **Step 4: Commit** — `feat(android): cache instrument profiles for seven days`

---

### Task 5: Time zone setting

**Files:**
- Modify: `core/data/src/main/kotlin/com/stocktracker/core/data/SettingsRepository.kt`
- Modify: `feature/settings/src/main/kotlin/com/stocktracker/feature/settings/SettingsScreen.kt`, `SettingsUiState.kt`, `SettingsViewModel.kt`
- Test: `feature/settings/src/test/kotlin/com/stocktracker/feature/settings/TimeZonePickerTest.kt`

**Interfaces:**
- Produces: `AppSettings.timeZoneId: String` (empty means follow the device), `SettingsRepository.setTimeZoneId(id: String)`, `fun AppSettings.effectiveZoneId(): ZoneId`.

```kotlin
/**
 * Empty means "follow the device", which is the default: correct when travelling, and no
 * setup for a user who never leaves their own zone. runCatching guards a stored id that a
 * later tzdb update removed — that would otherwise throw on every read.
 */
fun AppSettings.effectiveZoneId(): ZoneId =
    timeZoneId.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
```

- [ ] **Step 1: Write the failing test** — blank id resolves to the system default; a valid id resolves to it; a nonsense id falls back to the system default rather than throwing.

- [ ] **Step 2: Implement the setting** following the `themeMode` precedent exactly.

- [ ] **Step 3: Add the Settings row** — shows the effective zone and whether it follows the device; opens a dialog with a search field over `ZoneId.getAvailableZoneIds()` (sorted) plus a pinned "Use device time zone (<id>)" entry that clears the override.

- [ ] **Step 4: Run** `./gradlew :feature:settings:test :core:data:test`

- [ ] **Step 5: Commit** — `feat(android): time zone setting defaulting to the device zone`

---

### Task 6: Market hours math

**Files:**
- Create: `core/calc/src/main/kotlin/com/stocktracker/core/calc/MarketHours.kt`
- Test: `core/calc/src/test/kotlin/com/stocktracker/core/calc/MarketHoursTest.kt`

**Interfaces:**
- Produces:

```kotlin
enum class MarketPhase { PRE_OPEN, OPEN, POST, CLOSED }

data class MarketWindow(val phase: MarketPhase, val start: LocalTime, val end: LocalTime)

data class MarketDay(
    val date: LocalDate,
    val windows: List<MarketWindow>,
    /** True when the day was extrapolated from today's pattern rather than reported by Yahoo. */
    val estimated: Boolean,
)

fun marketDay(periods: TradingPeriods, zone: ZoneId, nowEpoch: Long): MarketDay
fun nextMarketDays(today: MarketDay, count: Int): List<MarketDay>
fun phaseAt(day: MarketDay, time: LocalTime): MarketPhase
```

- [ ] **Step 1: Write the failing tests.** Cover: a European session rendered in `Europe/Prague`; the same session rendered in `Asia/Tokyo`, where it lands on a different calendar day; a session with no pre/post; `phaseAt` at each boundary minute; `nextMarketDays` skipping Saturday and Sunday and marking every result `estimated = true`.

**The cross-zone case is the point of the feature** — assert the wall-clock times actually shift, not just that the call returns something.

- [ ] **Step 2: Implement it.** Pure `java.time`, no Android imports; `core:calc` is a plain JVM module.

- [ ] **Step 3: Run** `./gradlew :core:calc:test --tests '*MarketHoursTest*'`

- [ ] **Step 4: Commit** — `feat(android): market hours converted to the user's time zone`

---

### Task 7: Rates of return

**Files:**
- Create: `core/calc/src/main/kotlin/com/stocktracker/core/calc/RatesOfReturn.kt`
- Test: `core/calc/src/test/kotlin/com/stocktracker/core/calc/RatesOfReturnTest.kt`

**Interfaces:**
- Produces: `enum class ReturnPeriod { D1, W1, M1, M3, Y1 }` and `fun ratesOfReturn(history: PriceHistory, asOf: LocalDate): Map<ReturnPeriod, Double?>`.

Each period takes the latest close against the close on or immediately before the period's start date — the same step-lookup semantics as `ChartMath.priceAt`, so a weekend or holiday start resolves to the prior trading day instead of producing a gap. A period older than the available history yields null and its chip is omitted.

- [ ] **Step 1: Write the failing tests** — a known series with hand-computed percentages; a gap at the period boundary resolving backwards; history shorter than 1Y yielding null for `Y1` only; a weekend `asOf`; an empty history yielding all nulls; a zero earlier price yielding null rather than infinity.

- [ ] **Step 2: Implement it**, reusing `priceAt` rather than writing a second lookup.

- [ ] **Step 3: Run** `./gradlew :core:calc:test --tests '*RatesOfReturnTest*'`

- [ ] **Step 4: Commit** — `feat(android): 1D/1W/1M/3M/1Y return calculation`

---

### Task 8: The four cards

**Files:**
- Create: `feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/InstrumentInfoSection.kt`
- Create: `feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/InstrumentInfoViewModel.kt`
- Modify: `PortfolioListScreen.kt` — one call site inside `PositionDetailRoute`, below `PriceChartCard`
- Test: `feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/InstrumentInfoSectionTest.kt`, and new cases in `LayoutMatrixTest` / `GoldenScreenshotTest`

Cards in XTB's order — About, Market hours, Essentials, Rates of return — each collapsible, each omitted entirely when it has no data.

- **About**: fund family or sector/industry line, then the description clamped to three lines with a "Show more" toggle.
- **Market hours**: `Today DD.MM`, a 00:00–24:00 bar with amber pre-open, green open, grey closed and a marker at the current time, then labelled blocks; a footer names the zone being displayed; "Show next days" expands four weekdays **labelled as estimates** (Yahoo exposes no holiday calendar).
- **Essentials**: two-column `DetailMetricGrid` — asset class, category, currency, exchange, ISIN (from the row's lots), 52-week range, day range, volume, net assets, expense ratio, distribution type. Missing values are omitted, not rendered as a dash.
- **Rates of return**: 1D/1W/1M/3M/1Y chips, green/red tinted.

Distribution type is derived, not fetched: "Distributing" when dividend events exist for the ticker, "Accumulating" when the dividend fetch succeeded and returned none, and the row is omitted when no dividend data has loaded. Yahoo does not expose the flag directly.

- [ ] **Step 1: Write the failing tests** — each card renders its data; a card with no data does not render at all; market-hours times are the *user-zone* times for a fixed clock and zone; the estimate label is present on the next-days rows; chips show the right signs and colours.

- [ ] **Step 2: Write `InstrumentInfoViewModel`** — Hilt, ticker as a `SavedStateHandle` or parameter, exposing profile + returns, triggering `refreshIfStale` on first collection.

- [ ] **Step 3: Write the cards.**

- [ ] **Step 4: Add the call site** in `PositionDetailRoute`.

- [ ] **Step 5: Extend the layout matrix and goldens** — the Essentials grid and the market-hours bar are the densest new UI, so add both to `LayoutMatrixTest` at all nine combinations and record one golden each.

- [ ] **Step 6: Run** `./gradlew test` then `./gradlew :app:assembleDebug`

- [ ] **Step 7: Verify on the emulator** — open a position, confirm the market-hours bar matches the exchange's real session in your zone, and confirm the About card disappears rather than erroring if the crumb handshake fails (test by pointing the client at a bad crumb URL temporarily, or by turning off networking after the chart-meta call).

- [ ] **Step 8: Commit** — `feat(android): instrument info cards on the position detail screen`

---

### Task 9: Documentation

- [ ] **Step 1:** Record in `CLAUDE.md`: the two-call profile fetch and that quoteSummary is best-effort behind a crumb (with the "cookie step answers 404 but still sets the cookie" gotcha), the Room version bump, the time zone setting, and where market-hours/return math lives.
- [ ] **Step 2: Commit** — `docs: record the instrument profile fetch and time zone setting`

---

## Self-Review

**Spec coverage:** Part 1 → Tasks 1–4. Part 2 → Task 5. Part 3 → Tasks 6–8.

**Placeholders:** the tests in Tasks 1–5 are specified by their assertions rather than transcribed in full, because each follows a pattern already in the repository (`PersistApiTest` for MockWebServer, `MigrationTestHelper` for Room). Every task names the exact cases to cover. Tasks 6–8, which have no existing precedent, carry full interfaces.

**Type consistency:** `InstrumentProfile`/`TradingPeriods` defined in Task 1 and used unchanged in 3, 4, 6, 8. `effectiveZoneId` defined in Task 5 and consumed in 8. `MarketDay`/`ReturnPeriod` defined in 6/7 and consumed in 8.

**Known unknowns:** whether the crumb handshake still works at implementation time (Task 2 fails loudly if not, and every dependent field is already optional); whether `core/database/schemas/1.json` exists for the migration test (Task 3 Step 1 checks).
