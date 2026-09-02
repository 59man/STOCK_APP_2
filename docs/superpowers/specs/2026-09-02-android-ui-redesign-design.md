# Android UI Redesign — Design Spec

## Problem

The Android app's UI (backend/sync logic is fine, out of scope here) has systemic
readability problems across every screen:

- Numeric values wrap across multiple lines (e.g. a price like "2,718.48 CZK" wraps
  to 5 lines) because they're squeezed into fixed-width grid table columns
  (`LotTableHeader`/`LotRow`, `DividendTableHeader`/`DividendRow` in
  `PortfolioListScreen.kt`).
- Attributes are crammed two-per-row (e.g. P&L and Total Return side by side in
  `PositionDetailRoute`), fighting for horizontal space on a phone width.
- Long names aren't truncated — they wrap instead of ellipsizing (`PositionCard`'s
  ticker/name `Text` composables have no `maxLines`/`overflow`).
- The current dark purple theme (`StockTrackerTheme.kt`) is visually generic; wanted:
  a more restrained finance-app look where color is reserved for gain/loss and
  actions, not decoration.
- Dialogs (`SellPositionDialog.kt` etc.) are stock `AlertDialog` styling; one
  (`SellPositionDialog`) hardcodes light-theme-only colors
  (`0xFFD32F2F`/`0xFF2E7D32`) instead of theme-aware gain/loss, so it visually
  clashes in dark mode.
- Insights and Portfolio are not equally reachable — no bottom nav.

## Goals

1. Fix text overflow/wrapping at its root (layout, not font-size hacks).
2. One value per row everywhere a metric is shown — never two monetary values
   sharing a horizontal `Row`.
3. New color identity: near-black neutral base, one accent color reserved for
   actions/active nav state, gain/loss carry the color weight elsewhere.
4. Bottom navigation: **Insights, Portfolio, Settings** (in that order).
5. Consistent component vocabulary (pill badges, cards) applied to every screen,
   including dialogs — not just the position list.

Out of scope: backend/server, sync engine, calc logic (`core:calc`), web app,
iOS/KMP, tablet/foldable/landscape layouts — **phone portrait only**, matching
today's app scope. Verification (see Testing) targets phone screen widths only.

## Design tokens (dark — primary target this round)

| Token | Value | Use |
| --- | --- | --- |
| `background` | `#0A0A0D` | Scaffold background |
| `surface` (card) | `#17171D` | `AppCard`, dialogs, chart cards |
| `surfaceSubtle` | `#1E1E24` | Neutral pill fill, dividers |
| `accent` | `#1560F0` | Primary buttons, active nav tab, chart line, FAB |
| `onAccent` | `#FFFFFF` | Text/icon on accent-filled surfaces |
| `textPrimary` | `#F2F2F5` | Headline/body text |
| `textSecondary` | `#8A8A96` | Labels, captions |
| `gain` | `#22C55E` | Positive P&L, up % |
| `loss` | `#EF4444` | Negative P&L, down % |

Type badge colors (`typeBadgeColor` in `PortfolioListScreen.kt`) and currency badge
colors (`currencyBadgeColor`) are kept as-is — already distinct and already matched
to the web app's palette; only their pill shape/fill treatment changes (see
Components below), not the hues.

**Light theme**: `StockTrackerLightScheme` (already WCAG AA-checked) stays the
structural base — this round is not a pixel-identical light mockup pass. Apply the
same *structural* rules (card surfaces, pill badges, one-metric-per-row, bottom nav,
card-per-lot) mapped onto the existing light tokens, so Settings' light/dark/system
switch keeps working without a regression. `SellPositionDialog`'s hardcoded colors
get fixed to use `StockTrackerColors.gain`/`.loss` regardless of theme, closing the
dark-mode clash bug directly.

## Text & layout rules (apply everywhere)

- Every ticker/name `Text` gets `maxLines = 1, overflow = TextOverflow.Ellipsis`.
  No exceptions — this is the direct fix for the "text on 5 rows" complaint.
- Every metric (P&L, Total Return, IRR, daily change, etc.) renders as its own
  full-width block: label (small, `textSecondary`) above, value (large, weighted)
  below. Never two metrics sharing one `Row`.
- Tables with a fixed-width money column (`LotTableHeader`/`Row`,
  `DividendTableHeader`/`Row`) are replaced with **card-per-item** lists: one
  `AppCard` per lot / per dividend event, each field as its own label/value pair
  (unconstrained width — nothing to overflow). This directly fixes the price-column
  wrap bug since there's no fixed column to overflow.

## Components (new/changed in `core:designsystem`)

- **Pill badge**: `RoundedCornerShape(20.dp)`, solid or `alpha`-tinted fill
  (tinted for %-change-positive/neutral badges, solid for negative/status), replaces
  the existing `Badge.kt` shape. Used for: position type, currency, SOLD status,
  daily %, absolute change, dividend Open/Sold status, pie-chart legend entries.
- **Metric block**: label + value stacked, full width — new small composable
  replacing the ad-hoc `Row`/`Text` pairs in `PositionDetailRoute`.
- **Lot card / Dividend card**: replaces `LotTableHeader`+`LotRow` and
  `DividendTableHeader`+`DividendRow`.
- **Bottom nav bar**: new `Scaffold(bottomBar = ...)` at the nav-host level (today's
  per-screen `TopAppBar`-only structure), 3 items — Insights / Portfolio / Settings,
  active tab in `accent` color.
- Dialogs (`SellPositionDialog`, `EditLotDialog`, `EditTickerDialog`,
  `ManualPriceDialog`, `AddPositionDialog`'s confirm step) restyled onto the same
  card/pill/metric-block vocabulary instead of stock `AlertDialog` chrome.

Reference the newly installed `material-3-skill` for M3-idiomatic implementations
of pill shapes, tonal surfaces, and bottom navigation during the implementation
pass.

## Screen changes

- **PortfolioListScreen** (`PositionCard`): confirmed row layout — 40dp logo, name
  (ellipsized) + pill badges (type/currency) on one line below it, right side:
  current value (bold) then stacked pill row (% pill tinted, absolute-change pill
  solid). `SummaryHeader`/`SummaryCard` restyled to new tokens, same structure.
- **PositionDetailRoute**: P&L / Total Return / IRR become 3 stacked metric blocks.
  Lot table → lot cards. Dividend table → dividend cards. Buttons restyled to pill/
  accent treatment (`AppButton` variants already exist — just re-themed).
- **InsightsScreen**: confirmed layout — summary card, line chart (accent-colored),
  pie chart with pill-chip legend instead of a plain Recharts-style key. Structural
  change only: becomes reachable via bottom nav's first tab instead of buried in
  today's flow.
- **Navigation**: bottom nav bar added at the app/nav-host level, order Insights →
  Portfolio → Settings. `PortfolioTabs`/`CurrencyTabs` (multi-portfolio, currency
  switcher) stay exactly where they are today (top of the Portfolio/Insights
  screens), just recolored.
- **Settings**: no structural change, re-themed to new tokens (it's already a plain
  form/list screen).

## Testing

- `core:calc` and other logic modules are untouched — no new unit tests needed
  there.
- **New: automated Compose UI tests** (new `androidTest`/Robolectric infra —
  `feature:portfolio` has none today). Cover the exact failure modes this redesign
  fixes, so a future change can't silently reintroduce them:
  - Long name (e.g. a 50+ char ticker/fund name) never renders its full string —
    asserts ellipsis truncation kicked in, not line-wrap.
  - Large value (7-figure, e.g. `1,000,000.00`) renders as a single text node,
    single line.
  - 4-decimal price (crypto/JPY-style) renders without wrapping in both the
    position row and the new lot/dividend cards.
  - Applies to: `PositionCard`, `PositionDetailRoute`'s metric blocks, lot card,
    dividend card — the four places that broke before.
  - Test file(s) live under `feature/portfolio/src/test/kotlin/...` (Robolectric,
    JVM-only — no emulator required, runs under the existing
    `./gradlew test` / `./gradlew :core:calc:test`-style workflow) unless a real
    Compose semantics check turns out to need `androidTest` instead; decide during
    implementation based on what Robolectric's Compose support handles.
- Manual verification on the emulator (`stocktracker_test` AVD) for layout across
  screen sizes, then a real-device pass (per prior experience, real-device testing
  has caught issues emulator testing missed) — specifically: long ticker names,
  long fund names, 4+ decimal JPY/crypto prices, narrow-width devices.
- No golden-path regression: all existing actions (sell, edit lot, set manual price,
  delete, import) must still work — only their presentation changes.

## Open items resolved during this design pass

- Light theme scope (pixel pass vs. structural-only): resolved above — structural
  only, values inherited from `StockTrackerLightScheme`.
- Whether `PortfolioTabs`/`CurrencyTabs` move with the nav restructure: resolved —
  they stay put, only bottom nav is new.
