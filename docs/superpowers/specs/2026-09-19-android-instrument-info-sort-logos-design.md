# Android: Instrument Info Sections, Portfolio Sort, Logo Chain Rework, and README Screenshots

Date: 2026-09-19
Status: Design approved, ready for implementation planning

## Summary

Five related pieces of work, covered by Parts 1–7 below:

1. **Instrument info sections** on the position detail screen (Parts 1–3), modelled on XTB's
   instrument page: About, Market hours (rendered in the user's time zone),
   Essentials, and Rates of return.
2. **Portfolio sort** (Part 4) — a chip row above the position list offering Name, Type,
   Value, Today's change, and Total return, in either direction, persisted.
3. **Logo chain rework** (Part 5) — the current second logo source, `logo.clearbit.com`,
   no longer resolves at all, so every ticker the primary source misses falls
   straight through to an initials avatar. Replace the chain with one that
   actually covers non-US holdings.
4. **Labelled Import and Export actions** (Part 6) — the two icon-only top-bar
   actions gain visible captions.
5. **README rewrite with screenshots** (Part 7) of both the web and Android apps,
   done last so the screenshots show the finished features.

Parts 1–6 are Android-only. Part 7 touches the README and adds a `DATA_FILE`
environment override to `server/index.js` so screenshots can be captured
against a demo dataset instead of the real portfolio.

## Background and findings

All findings below were verified against the live endpoints on 2026-09-19.

### Yahoo chart meta already carries most of what XTB shows

The `v8/finance/chart` response the app already fetches for every quote carries,
in `chart.result[0].meta`, fields the app currently ignores:

```
fullExchangeName, exchangeName, instrumentType, currency,
exchangeTimezoneName, timezone, gmtoffset,
currentTradingPeriod: { pre|regular|post: { start, end, gmtoffset, timezone } },
fiftyTwoWeekHigh, fiftyTwoWeekLow, regularMarketDayHigh, regularMarketDayLow,
regularMarketVolume, firstTradeDate, longName, shortName
```

`currentTradingPeriod` is exactly the data behind XTB's market-hours bar:
pre-open, open, and (by subtraction) closed windows as epoch seconds, plus the
exchange's IANA zone to interpret them. This needs no new endpoint.

### quoteSummary is reachable, but only behind a cookie/crumb handshake

`v10/finance/quoteSummary` returns `401 Invalid Crumb` when called plainly. It
succeeds after a two-step handshake: a request to `https://fc.yahoo.com/` to
pick up a session cookie, then `v1/test/getcrumb` with that cookie to obtain a
crumb, which is appended to the quoteSummary URL. Verified working, returning
`assetProfile` (business summary, sector, industry, website, country,
employees) and `fundProfile` (family, legal type, expense ratio, total net
assets), plus `summaryDetail`, `defaultKeyStatistics`, and `quoteType`.

This is undocumented and can break without notice — the same category of
dependency as the existing Fio fund cookie flow in `server/index.js`. Every
field it supplies is therefore treated as optional: if the handshake fails, the
rows it would have filled are omitted and the rest of the screen is unaffected.

### The Clearbit logo fallback is dead

`logo.clearbit.com` fails to connect (HubSpot retired the free logo API after
acquiring Clearbit). The primary source, `financialmodelingprep.com/image-stock/<base>.png`,
returns 200 for `AAPL` and 404 for `CZG`, so it covers US and large-cap names
only. The practical result today is that Czech and other non-US holdings always
render as a colored initial letter.

Free, keyless alternatives tested against the domains in the existing
`TICKER_LOGO_DOMAINS` map:

| Domain | DuckDuckGo `ip3` | Google `s2?sz=128` |
|---|---|---|
| coltcz.com | 200 | 200 |
| fio.cz | 200 | 200 |
| onemarkets.cz | 200 | 200 |
| telekom.com | 200 | 200 |
| ishares.com | 200 | 200 |
| vig.com | 404 | 200 |
| csgroup.cz | 404 | 404 |
| mufg.jp | 404 | 404 |
| orix.co.jp | 404 | 404 |

Both services return a genuine non-2xx for a miss, so Coil treats them as
errors and moves to the next candidate — no risk of a generic globe icon
silently replacing a real logo. `img.logo.dev` requires an API token (401) and
is not used.

Favicons are 16–64 px and look soft scaled to 40 dp, so they belong at the end
of the chain, not the front. Four of the user's current holdings are covered by
no remote source at all and need local files.

## Part 1 — Instrument profile data layer

### Model

New `core/model/src/main/kotlin/com/stocktracker/core/model/InstrumentProfile.kt`:

```kotlin
data class TradingPeriod(val startEpoch: Long, val endEpoch: Long)

data class TradingPeriods(
    val pre: TradingPeriod?,
    val regular: TradingPeriod?,
    val post: TradingPeriod?,
)

data class InstrumentProfile(
    val ticker: String,
    // Always present — from chart meta
    val longName: String?,
    val exchange: String?,          // meta.fullExchangeName
    val instrumentType: String?,    // meta.instrumentType
    val currency: String?,
    val exchangeTimeZone: String?,  // meta.exchangeTimezoneName, IANA id
    val tradingPeriods: TradingPeriods?,
    val fiftyTwoWeekHigh: Double?,
    val fiftyTwoWeekLow: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Long?,
    val firstTradeDate: Long?,
    // Best-effort — from quoteSummary, null when the crumb handshake fails
    val description: String?,
    val sector: String?,
    val industry: String?,
    val website: String?,
    val country: String?,
    val fundFamily: String?,
    val legalType: String?,
    val expenseRatio: Double?,
    val totalNetAssets: Double?,
    val category: String?,
    val fetchedAt: Long,
)
```

`InstrumentProfile` carries no distribution-type field of its own: Yahoo does
not expose accumulating vs. distributing directly. It is derived at render time
as "Distributing" when the app holds dividend events for the ticker and
"Accumulating" when the dividend fetch succeeded and returned none; when no
dividend data has loaded, the row is omitted rather than guessed.

### Network client

New `core/network/src/main/kotlin/com/stocktracker/core/network/InstrumentProfileClient.kt`.

Two calls, composed into one `InstrumentProfile`:

- **Call A (required)** — `v8/finance/chart/<ticker>?interval=1d&range=1d`.
  Reuses the existing browser `User-Agent` header convention. Parses the meta
  fields listed above. A failure here fails the whole profile fetch.
- **Call B (best-effort)** — `v10/finance/quoteSummary/<ticker>?modules=assetProfile,fundProfile,summaryDetail,defaultKeyStatistics,quoteType&crumb=<crumb>`.
  Any failure yields null for every field it would have filled; the error is
  logged, not surfaced as a screen-level error state.

New `CrumbProvider` in the same module:

- Holds cookie and crumb in memory with a one-hour expiry.
- A `Mutex` makes concurrent callers share one handshake instead of racing.
- On a 401 from quoteSummary, invalidates the cached crumb and retries once
  with a fresh one; a second 401 gives up for that fetch.

Yahoo wraps numbers as `{"raw": 0.0015, "fmt": "0.15%"}`. A small
`@Serializable data class YahooNum(val raw: Double? = null, val fmt: String? = null)`
handles this; the app uses `raw` and ignores `fmt` so formatting stays under the
app's own control.

Ticker encoding follows the existing rule in `QuoteClient`/`HistoryClient`:
`URLEncoder.encode` is applied exactly once, and raw (unencoded) tickers are
what gets stored in any map — see the `FX_CONVERTED_TICKERS` gotcha in
CLAUDE.md.

### Persistence

New `core/database/.../InstrumentProfileEntity.kt`, primary key `ticker`, flat
nullable columns mirroring `InstrumentProfile`, plus `fetchedAt` as epoch
millis. Trading periods are stored as four nullable epoch columns rather than a
serialized blob, so the table stays queryable and needs no TypeConverter.

`StockTrackerDatabase` goes from version 1 to 2 with an explicit
`Migration(1, 2)` executing a single `CREATE TABLE IF NOT EXISTS` and registered
via `addMigrations` in `DataModule`. Destructive fallback is deliberately not
used: the database holds positions and unresolved sync conflicts, and wiping it
to add a derived cache table would be a real data loss.

New `InstrumentProfileRepository` in `core/data`:

- `observe(ticker): Flow<InstrumentProfile?>` emits the cached row immediately.
- `refreshIfStale(ticker)` fetches when there is no row or `fetchedAt` is older
  than seven days, then upserts.
- The table is device-local and is never pushed to the persist server — it is a
  derived cache, in the same category as quotes and FX rates.

## Part 2 — Time zone setting

`AppSettings` gains `timeZoneId: String = ""`, stored under a new
`time_zone_id` preferences key. An empty value means "follow the device", which
is the default; the user's device is already on Central European time, so the
out-of-the-box behaviour matches what they asked for while staying correct when
they travel.

`SettingsRepository` gains `setTimeZoneId(id: String)` and a helper that
resolves the effective zone:

```kotlin
fun effectiveZoneId(settings: AppSettings): ZoneId =
    settings.timeZoneId.takeIf { it.isNotBlank() }
        ?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        ?: ZoneId.systemDefault()
```

The `runCatching` guard matters because a stored zone id can become invalid
after a tzdb update.

The Settings screen gains a **Time zone** row showing the effective zone and
whether it is following the device. Tapping opens a dialog with a search field
over `ZoneId.getAvailableZoneIds()`, sorted, plus a pinned
"Use device time zone (<current>)" entry that clears the override.

## Part 3 — Detail screen sections

The sections are appended to the existing single-scroll detail screen, below
`PriceChartCard`, in XTB's order: About, Market hours, Essentials, Rates of
return. No tab row, no navigation changes.

`PortfolioListScreen.kt` is already 987 lines, so the new UI lives in its own
file, `feature/portfolio/.../InstrumentInfoSection.kt`, and
`PositionDetailRoute` gains a single call site.

Each section is a collapsible card. A section whose data is entirely missing is
omitted rather than rendered empty.

- **About** — fund family or sector/industry line, then the description clamped
  to three lines with a "Show more" toggle. Hidden when there is no description.
- **Market hours** — heading `Today DD.MM`, then a horizontal bar spanning
  00:00–24:00 in the *user's* zone with an amber pre-open segment, a green open
  segment, grey closed regions, and a marker at the current time; then three
  labelled blocks (Pre-open, Open, Closed) with their time ranges. All times are
  converted from the exchange zone to the effective user zone, and a footer
  states which zone is being shown. A "Show next days" expander lists the next
  four weekdays using today's open and close times, skipping Saturday and
  Sunday; because Yahoo exposes no exchange holiday calendar, this expander is
  explicitly labelled as an estimate.
- **Essentials** — a two-column grid reusing the existing `DetailMetricGrid`:
  asset class, subclass/category, currency, exchange, ISIN (taken from the
  position's lots, which already carry it), 52-week range, day range, volume,
  net assets, expense ratio, distribution type. Absent values are omitted.
- **Rates of return** — chips for 1D, 1W, 1M, 3M, and 1Y with the tinted
  green/red backgrounds from the reference screenshots. Computed from the daily
  history `PriceChartCard` already fetches; no additional network call.

New `InstrumentInfoViewModel`, keyed by ticker, owns the profile fetch so it
stays off the shared `PortfolioListViewModel`.

### Return calculation

New pure function in `core/calc`:

```kotlin
fun ratesOfReturn(history: List<PricePoint>, asOf: LocalDate): Map<ReturnPeriod, Double?>
```

For each period it takes the latest close and the close on or immediately before
the period's start date — the same step-lookup semantics as `ChartMath.priceAt`,
so a weekend or holiday start date resolves to the prior trading day rather than
producing a gap. A period whose start predates the available history yields
null, and the chip is omitted.

## Part 4 — Portfolio sort

New in `core/model`:

```kotlin
enum class SortField { NAME, TYPE, VALUE, TODAY, TOTAL_RETURN }
data class SortOrder(val field: SortField = SortField.VALUE, val ascending: Boolean = false)
```

Persisted globally (not per portfolio) in the settings DataStore as `sort_field`
and `sort_ascending`, and surfaced through `PortfolioListUiState`.

Sorting is applied in `visibleRows`, after the existing closed-position filter,
so the "Show closed" toggle and the sort compose rather than conflict.

Monetary fields are compared **after conversion to the display currency**. A
portfolio holding both CZK and USD positions would otherwise sort by raw native
numbers, which is meaningless. The comparator therefore takes the same
`convert(amount, from, displayCurrency, rates)` the rest of the screen uses.

Name sorts case-insensitively on the display name; type sorts by the enum's
declaration order so related asset classes group together.

UI: a horizontally scrollable chip row directly under `CurrencyTabs`. Tapping an
inactive chip selects that field; tapping the active chip flips direction. Only
the active chip shows a direction arrow. New action
`data class SetSort(val field: SortField) : PortfolioListAction`.

## Part 5 — Logo chain rework

`PositionLogo` and `TICKER_LOGO_DOMAINS` move out of `PortfolioListScreen.kt`
into a new `feature/portfolio/.../TickerLogo.kt`. The current implementation
nests `SubcomposeAsyncImage` inside its own error slot, which does not extend
readably to four levels; the replacement builds an ordered
`List<LogoCandidate>` and walks it with a single composable that advances an
index on each load failure.

Candidate order, first success wins:

1. **Local file** — `filesDir/logos/<TICKER>.png`.
2. **Financial Modeling Prep** — `image-stock/<base symbol>.png`. Retained; it
   covers US and large-cap names at good quality for free.
3. **Domain favicon** — `https://icons.duckduckgo.com/ip3/<domain>.ico`, then
   `https://www.google.com/s2/favicons?domain=<domain>&sz=128`. The domain comes
   from the curated `TICKER_LOGO_DOMAINS` map, falling back to the host of
   `InstrumentProfile.website` when the profile fetch supplied one — so a newly
   added ticker resolves a logo without a map edit.
4. **Initials avatar** — unchanged.

`logo.clearbit.com` is removed entirely.

The local logo directory is populated two ways:

- **Bundled** — `app/src/main/assets/logos/<TICKER>.png` for the holdings no
  remote source covers (VIG.PR, CSG.AS, CSG.PR, 8306.T, 8591.T at minimum),
  sourced from each company's own website, downscaled to 128×128 PNG, and
  copied into `filesDir/logos/` on first run if not already present. These are
  trademarked images committed to a private personal repository; they are not
  redistributed.
- **User-set** — a "Set logo" action on the position detail screen opens the
  Android photo picker (`PickVisualMedia`, no storage permission required),
  downscales the chosen image to 128×128, and writes it to
  `filesDir/logos/<TICKER>.png`, overriding everything else. A "Reset logo"
  action deletes the file and returns the ticker to the remote chain.

Local logos live in app-private storage and are not synced to the server; a
reinstall falls back to the bundled assets and the remote chain.

Coil's `ImageLoader` is configured explicitly with a disk cache so a resolved
remote logo survives a restart and renders offline. It is currently constructed
with defaults.

## Part 6 — Labelled Import and Export actions

The portfolio list's top bar currently offers two icon-only actions
(`PortfolioListScreen.kt:154-159`), whose meaning is carried entirely by a
`contentDescription` that only a screen reader sees. Custom glyphs —
`ImportIcon` and `ExportIcon` are hand-drawn vectors defined at the bottom of
the same file — make this worse than a standard Material icon would.

Both become icon-over-label actions: the icon stays in place and a small caption
sits directly beneath it, inside the existing `TopAppBar` actions slot. The
"Stock Tracker" title is unaffected.

```
┌────────────────────────────────┐
│ Stock Tracker      ↓      ↑    │
│                 Import  Export │
└────────────────────────────────┘
```

A new private `LabelledAction` composable in `PortfolioListScreen.kt` replaces
both `IconButton`s: a `Column` with the icon above and a `labelSmall` caption
below, centred, wrapped in a clickable with a minimum 48 dp touch target so the
smaller visual footprint does not shrink the tap area. A 24 dp icon plus a
caption fits inside the `TopAppBar`'s 64 dp height without raising it.

Accessibility: the visible label now carries the meaning, so the `Icon` takes
`contentDescription = null` and the button's semantics come from the text,
avoiding a screen reader announcing "Import statement, Import".

The disabled state of Export (no active portfolio) carries through to both the
icon and the label.

## Part 7 — README rewrite with screenshots

Sequenced **after** Parts 1–5 ship, so the screenshots show the instrument info
sections, the sort chips, and working logos rather than needing to be retaken.

### Privacy constraint

`github.com/59man/STOCK_APP_2` is a public repository, and an image committed to
it stays in Git history even after the file is deleted. Screenshots therefore
use a **demo portfolio of invented positions in well-known tickers**, never the
real one. The real `server/data.json` is not opened, copied, or served during
the capture.

### Enabling a safe capture

`server/index.js` currently hardcodes `DATA_FILE` to `server/data.json`. Add an
environment override:

```js
const DATA_FILE = process.env.DATA_FILE ?? join(__dirname, 'data.json')
```

with `DATA_BAK`, the backups directory, and the dated-backup path derived from
it. This is a small change that is useful beyond screenshots — it makes a
throwaway dataset possible for any manual testing without touching real data —
and it means the capture never swaps files in and out of `server/`, which is
where a mistake would cost real portfolio data.

Capture procedure:

1. Write a demo dataset to a scratch file outside the repository.
2. Run the dev server against it: `DATA_FILE=<scratch>/demo.json npm run dev`.
3. Capture the web screenshots in a **fresh browser profile**, because the app
   also keeps state in `localStorage` and an existing profile would show real
   positions.
4. Point the `stocktracker_test` emulator's Settings at the same demo server
   (`http://10.0.2.2:3001`), sync, and capture with `adb exec-out screencap -p`.
5. Delete the scratch dataset.

### Screens captured

Web (desktop viewport, dark theme, the app's only theme):

- Portfolio table with one row expanded, showing the lot mini-table and
  dividend panel.
- Total Return chart.
- The three pie charts.

Android (`stocktracker_test` emulator, dark theme):

- Position list.
- Position detail, scrolled to show the price chart and the new instrument info
  sections.
- Insights tab.

### Storage and README structure

Images go in `docs/screenshots/` as PNGs, referenced by relative path. Android
shots are downscaled to roughly 360 px wide and laid out two or three per row in
an HTML table so the phone screenshots do not dominate the page.

The README keeps its current sections (Features, Quick start, Docker, Android,
Architecture) and gains:

- A hero image directly under the title — the web portfolio table.
- Inline images in Features, next to the capability each one demonstrates.
- A row of Android screenshots in the Android section, above the install
  instructions.
- Alt text on every image, since GitHub renders the README for screen readers
  and in contexts where images fail to load.

Prose changes stay minimal: the README was compacted deliberately in commit
`46cfb21`, so this adds images and adjusts surrounding wording, rather than
re-expanding the text.

## Testing

- `core:calc` — `ratesOfReturn` against a known series, including a gap at the
  period boundary, a history shorter than the longest period, and a weekend
  `asOf`; sort comparators including mixed-currency conversion, closed rows, and
  null IRR/return values.
- `core:network` — MockWebServer tests for `InstrumentProfileClient`: chart-meta
  parse, quoteSummary parse with `{raw, fmt}` unwrapping, a 401-then-retry crumb
  path, and a response missing whole modules. Following the `DeviceApiTest`
  precedent in CLAUDE.md, assertions check actual wire bytes rather than types
  alone.
- `core:database` — migration 1→2 test asserting the new table exists and the
  existing tables and rows survive.
- `feature:portfolio` — a test asserting the Import and Export actions expose
  their visible labels and that Export's disabled state covers icon and label;
  Compose tests for the four info cards (following the
  existing `PositionCardTest` pattern), with the market-hours bar driven by a
  fixed clock and a fixed zone; a sort-chip test asserting order and direction
  flip; a logo test asserting fallthrough reaches the initials avatar when every
  candidate fails.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| Yahoo's crumb handshake stops working | Every field it feeds is nullable and hidden when null. Market hours, Essentials core rows, return chips, and 52-week range all come from chart meta and keep working. |
| Exchange holidays make "next days" wrong | The expander is labelled as an estimate. No holiday source is available. |
| Favicon services drop a domain | They return proper 404s, so the chain advances to the initials avatar exactly as it does today. |
| Room migration mistake loses positions | Migration only creates a new table; a migration test asserts existing rows survive; destructive fallback is not enabled. |
| Bundled logos are trademarked images | Private personal repository, not redistributed. The in-app picker means no new logo requires a repo change. |

## Out of scope

- Any web-app equivalent of the instrument info sections, sort, or logo chain.
- News, financials, ESG, and Risk & Return tabs from XTB's instrument page.
- Syncing logos or the profile cache between devices.
- Holiday-aware market calendars.
