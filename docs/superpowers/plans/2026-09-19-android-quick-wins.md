# Android Quick Wins Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the three self-contained Android changes — labelled Import/Export actions, a working logo fallback chain, and a persisted portfolio sort — together with the layout-robustness test helper the rest of the roadmap depends on.

**Architecture:** All changes live in `feature/portfolio` and `core/data`, except the sort enum which goes in `core/model`. No network client changes, no database migration, no new runtime dependency — the only dependency added is Roborazzi, which is test-only.

**Tech Stack:** Kotlin, Jetpack Compose (BOM 2024.06.00 → Compose 1.6.x), Material3, Hilt, DataStore Preferences, Coil3, Robolectric + Compose UI test, Roborazzi.

**Spec:** `docs/superpowers/specs/2026-09-19-android-instrument-info-sort-logos-design.md` (Parts 4, 5, 6 and the Testing section's "Layout robustness")

## Global Constraints

- Android project root is `android/`; all Gradle commands run from there.
- Unit tests must run on plain JVM via `./gradlew test` — no emulator. Robolectric is already configured in `feature/portfolio`.
- Tickers stored in any map are **raw, never pre-encoded** — `URLEncoder.encode` is applied exactly once at the call site. See the `FX_CONVERTED_TICKERS` gotcha in CLAUDE.md.
- Retrofit/`@Body` data classes must not rely on Kotlin default values: `PersistJson` does not set `encodeDefaults`, so a field left at its default is omitted from the wire body. Not triggered by this plan, but do not introduce it.
- Existing visual language: selected state = `primaryContainer` fill, unselected = transparent with a 1.dp `outlineVariant` border, 16.dp rounded corners (see `CurrencyTabs`, `PortfolioListScreen.kt:360`).
- Spacing comes from `core.designsystem.Spacing` (xs 4, sm 8, md 12, lg 16, xl 24, xxl 32). Do not hardcode equivalents.
- Commit after every task with a Conventional Commits subject.

---

### Task 1: Layout assertion helper

The rest of this plan's tests depend on it, so it lands first.

**Files:**
- Create: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LayoutAssertions.kt`
- Test: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LayoutAssertionsTest.kt`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `fun SemanticsNodeInteractionsProvider.assertNoClippedText()` — fails if any text node whose overflow mode is `TextOverflow.Clip` has `hasVisualOverflow == true`.
  - `fun SemanticsNodeInteractionsProvider.assertTextNotTruncated(text: String)` — fails if the node with that exact text is visually overflowing at all, ellipsis included.
  - `fun SemanticsNodeInteractionsProvider.assertMinTouchTarget(text: String, minDp: Dp = 48.dp)`

**Why it is shaped this way:** `hasVisualOverflow` is `true` for *intended* ellipsis too — `PositionCard` deliberately ellipsises long instrument names. A blanket "nothing overflows" assertion would therefore fail on correct code. The distinction that actually marks a bug is the overflow *mode*: `TextOverflow.Clip` means the text is silently cut off with no visual cue, which is never intended here. `assertTextNotTruncated` is the opt-in stricter check, for values and labels that must always be whole.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutAssertionsTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun clippedText_fails() {
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text("a very long string that cannot possibly fit", maxLines = 1, overflow = TextOverflow.Clip)
            }
        }
        assertThrows(AssertionError::class.java) { composeTestRule.assertNoClippedText() }
    }

    @Test
    fun ellipsisedText_passes() {
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text("a very long string that cannot possibly fit", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        composeTestRule.assertNoClippedText()
    }

    @Test
    fun fittingText_passesBothChecks() {
        composeTestRule.setContent { Text("Import") }
        composeTestRule.assertNoClippedText()
        composeTestRule.assertTextNotTruncated("Import")
    }

    @Test
    fun truncatedText_failsStrictCheck() {
        composeTestRule.setContent {
            Box(Modifier.width(20.dp)) {
                Text("a very long string", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        assertThrows(AssertionError::class.java) {
            composeTestRule.assertTextNotTruncated("a very long string")
        }
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*LayoutAssertionsTest*'`
Expected: FAIL — unresolved reference `assertNoClippedText`.

- [ ] **Step 3: Write the helper**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteractionsProvider
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private fun androidx.compose.ui.semantics.SemanticsNode.textLayout(): TextLayoutResult? {
    val action = config.getOrNull(SemanticsActions.GetTextLayoutResult) ?: return null
    val results = mutableListOf<TextLayoutResult>()
    action.action?.invoke(results)
    return results.firstOrNull()
}

private val hasTextLayout = SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult)

/**
 * Fails when any text is silently cut off. Ellipsised text passes: `PositionCard` and the
 * instrument cards truncate long names on purpose, and the ellipsis is the visual cue that
 * says so. Text laid out with [TextOverflow.Clip] has no such cue, so an overflow there is
 * always a layout bug.
 */
fun SemanticsNodeInteractionsProvider.assertNoClippedText() {
    onAllNodes(hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes().forEach { node ->
        val layout = node.textLayout() ?: return@forEach
        if (layout.layoutInput.overflow == TextOverflow.Clip && layout.hasVisualOverflow) {
            throw AssertionError("Text is clipped with no ellipsis: \"${layout.layoutInput.text}\"")
        }
    }
}

/** Stricter check for text that must always be readable in full — values, chips, action labels. */
fun SemanticsNodeInteractionsProvider.assertTextNotTruncated(text: String) {
    val nodes = onAllNodes(hasText(text) and hasTextLayout, useUnmergedTree = true).fetchSemanticsNodes()
    if (nodes.isEmpty()) throw AssertionError("No text node found with text \"$text\"")
    nodes.forEach { node ->
        val layout = node.textLayout() ?: return@forEach
        if (layout.hasVisualOverflow) throw AssertionError("Text is truncated: \"$text\"")
    }
}

/** Guards against a visually smaller control shrinking its tap area below the Material minimum. */
fun SemanticsNodeInteractionsProvider.assertMinTouchTarget(text: String, minDp: Dp = 48.dp) {
    val nodes = onAllNodes(hasText(text), useUnmergedTree = false).fetchSemanticsNodes()
    if (nodes.isEmpty()) throw AssertionError("No node found with text \"$text\"")
    nodes.forEach { node ->
        val size = node.touchBoundsInRoot
        val density = node.layoutInfo.density
        val widthDp = with(density) { size.width.toDp() }
        val heightDp = with(density) { size.height.toDp() }
        if (widthDp < minDp || heightDp < minDp) {
            throw AssertionError("Touch target for \"$text\" is ${widthDp}x${heightDp}, below $minDp")
        }
    }
}
```

- [ ] **Step 4: Run the tests and confirm they pass**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*LayoutAssertionsTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Commit**

```bash
git add android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LayoutAssertions.kt \
        android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LayoutAssertionsTest.kt
git commit -m "test(android): add layout assertion helpers for clipping and touch targets"
```

---

### Task 2: Labelled Import and Export actions

Implements Part 6 of the spec.

**Files:**
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt:151-161`
- Test: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/TopBarActionsTest.kt`

**Interfaces:**
- Consumes: `assertTextNotTruncated`, `assertMinTouchTarget` from Task 1.
- Produces: `@Composable private fun LabelledAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit)` — private to `PortfolioListScreen.kt`.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TopBarActionsTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun setScreen(activePortfolioId: String? = "p1") {
        composeTestRule.setContent {
            StockTrackerTheme {
                PortfolioListScreen(
                    uiState = PortfolioListUiState(
                        portfolios = emptyList(),
                        activePortfolioId = activePortfolioId,
                        rows = listOf(fakeRow()),
                        isLoading = false,
                    ),
                    onAction = {},
                    onAddPosition = {},
                    onOpenImport = {},
                    onOpenConflicts = {},
                    onExport = {},
                    onOpenPositionDetail = {},
                )
            }
        }
    }

    @Test
    fun importAndExport_showVisibleLabels() {
        setScreen()
        composeTestRule.onNodeWithText("Import").assertIsDisplayed()
        composeTestRule.onNodeWithText("Export").assertIsDisplayed()
    }

    @Test
    fun actionLabels_areNotTruncated() {
        setScreen()
        composeTestRule.assertTextNotTruncated("Import")
        composeTestRule.assertTextNotTruncated("Export")
    }

    @Test
    fun actionLabels_keepFullTouchTarget() {
        setScreen()
        composeTestRule.assertMinTouchTarget("Import")
        composeTestRule.assertMinTouchTarget("Export")
    }

    @Test
    fun topBar_hasNoClippedText() {
        setScreen()
        composeTestRule.assertNoClippedText()
    }
}
```

Before writing it, open `PortfolioListScreen.kt:140-150` and copy the *actual* parameter list of `PortfolioListScreen` into `setScreen` — the list above is from the current source and must match exactly.

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*TopBarActionsTest*'`
Expected: FAIL — no node with text "Import".

- [ ] **Step 3: Replace the two IconButtons**

In `PortfolioListScreen.kt`, replace the `actions = { ... }` block:

```kotlin
                actions = {
                    LabelledAction(
                        icon = ImportIcon,
                        label = "Import",
                        onClick = { onOpenImport(uiState.activePortfolioId) },
                    )
                    LabelledAction(
                        icon = ExportIcon,
                        label = "Export",
                        enabled = uiState.activePortfolioId != null,
                        onClick = onExport,
                    )
                },
```

and add, next to the other private composables in the same file:

```kotlin
/**
 * Top-bar action with the icon above a caption. The glyphs here are hand-drawn
 * (see [ImportIcon]/[ExportIcon] at the bottom of this file), so an icon alone carried no
 * meaning for anyone who had not already learned it. The visible label now carries it, which
 * is why the Icon takes a null contentDescription — otherwise a screen reader announces the
 * action twice.
 */
@Composable
private fun LabelledAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val tint = if (enabled) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier
            .padding(horizontal = Spacing.xs)
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Icon(icon, contentDescription = null, tint = tint)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = tint,
            maxLines = 1,
        )
    }
}
```

Add any missing imports: `androidx.compose.foundation.clickable`, `androidx.compose.foundation.layout.sizeIn`, `androidx.compose.foundation.shape.RoundedCornerShape`, `androidx.compose.ui.draw.clip`, `androidx.compose.ui.graphics.vector.ImageVector`, `com.stocktracker.core.designsystem.Radius`. Check which are already imported before adding.

If `Radius.sm` does not exist, read `core/designsystem/Dimens.kt` and use the nearest existing member rather than inventing one.

- [ ] **Step 4: Run tests and the build**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*TopBarActionsTest*' && ./gradlew :app:assembleDebug`
Expected: 4 tests PASS, build succeeds.

- [ ] **Step 5: Commit**

```bash
git add android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt \
        android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/TopBarActionsTest.kt
git commit -m "feat(android): label the Import and Export top-bar actions"
```

---

### Task 3: Logo candidate chain

Implements the remote half of Part 5. Local files come in Task 4.

**Files:**
- Create: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/TickerLogo.kt`
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt` — delete `TICKER_LOGO_DOMAINS`, `PositionLogo`, `InitialAvatar` (currently at `:605-668`) and import them from the new file instead
- Test: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/TickerLogoTest.kt`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces:
  - `internal fun logoCandidates(ticker: String, website: String? = null): List<String>` — ordered URL list, pure, no I/O.
  - `@Composable internal fun TickerLogo(ticker: String, type: PositionType, website: String? = null, modifier: Modifier = Modifier)`
  - `internal val TICKER_LOGO_DOMAINS: Map<String, String>` — moved verbatim from `PortfolioListScreen.kt`.

**Background:** `logo.clearbit.com` no longer resolves at all (verified 2026-09-19 — connection failure, not a 404), so the current second link is dead and every non-US ticker falls straight to the initials avatar. DuckDuckGo `ip3` and Google `s2` both return proper non-2xx on a miss, so Coil advances cleanly.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TickerLogoTest {
    @Test
    fun knownDomain_yieldsFmpThenBothFavicons() {
        val candidates = logoCandidates("COLT.PR")
        assertEquals(
            listOf(
                "https://financialmodelingprep.com/image-stock/COLT.png",
                "https://icons.duckduckgo.com/ip3/coltcz.com.ico",
                "https://www.google.com/s2/favicons?domain=coltcz.com&sz=128",
            ),
            candidates,
        )
    }

    @Test
    fun unknownTicker_yieldsFmpOnly() {
        assertEquals(
            listOf("https://financialmodelingprep.com/image-stock/ZZZZ.png"),
            logoCandidates("ZZZZ.XX"),
        )
    }

    @Test
    fun websiteFallback_isUsedWhenTickerIsNotInTheMap() {
        val candidates = logoCandidates("ZZZZ.XX", website = "https://www.example.co.uk/about?x=1")
        assertTrue(candidates.contains("https://icons.duckduckgo.com/ip3/example.co.uk.ico"))
    }

    @Test
    fun curatedDomain_winsOverWebsite() {
        val candidates = logoCandidates("COLT.PR", website = "https://wrong.example.com")
        assertTrue(candidates.none { it.contains("wrong.example.com") })
    }

    @Test
    fun clearbitIsNeverUsed() {
        val all = TICKER_LOGO_DOMAINS.keys.flatMap { logoCandidates(it) }
        assertTrue(all.none { it.contains("clearbit") })
    }

    @Test
    fun noCandidatesForCommodities() {
        // XAU and 4GLD.DE are deliberately absent from the domain map: a metal ETC has no
        // company logo, so it should fall through to the initials avatar rather than show a
        // fund house's favicon.
        assertEquals(
            listOf("https://financialmodelingprep.com/image-stock/XAU.png"),
            logoCandidates("XAU"),
        )
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*TickerLogoTest*'`
Expected: FAIL — unresolved reference `logoCandidates`.

- [ ] **Step 3: Create `TickerLogo.kt`**

Move `TICKER_LOGO_DOMAINS` across unchanged (it is correct; only the consumer was broken), changing its visibility from `private` to `internal`, then:

```kotlin
/**
 * Ordered logo sources for a ticker, best quality first. Coil walks this list, advancing on
 * each load failure, and [TickerLogo] falls back to an initials avatar when every candidate
 * fails.
 *
 * Clearbit used to sit at position 2 and is gone: `logo.clearbit.com` stopped resolving
 * entirely after HubSpot retired the free API, which is why every non-US holding rendered as
 * a bare initial. The two favicon services that replace it both answer a miss with a real
 * non-2xx, so a failure advances the chain instead of pinning a generic globe in place.
 *
 * Favicons are 16-64px and look soft at 40dp, so they rank below FMP's proper logo art and
 * below any local file (see [localLogoFile]).
 */
internal fun logoCandidates(ticker: String, website: String? = null): List<String> {
    val base = ticker.substringBefore(".").uppercase()
    val domain = TICKER_LOGO_DOMAINS[ticker.uppercase()] ?: website?.let(::hostOf)
    return buildList {
        add("https://financialmodelingprep.com/image-stock/$base.png")
        if (domain != null) {
            add("https://icons.duckduckgo.com/ip3/$domain.ico")
            add("https://www.google.com/s2/favicons?domain=$domain&sz=128")
        }
    }
}

private fun hostOf(website: String): String? =
    runCatching { java.net.URI(website).host }.getOrNull()?.removePrefix("www.")?.takeIf { it.isNotBlank() }
```

and the composable, replacing the nested-`SubcomposeAsyncImage` version:

```kotlin
/**
 * 40dp circular avatar walking [logoCandidates] in order, falling back to a colored initial.
 * The previous version nested SubcomposeAsyncImage inside its own error slot, which does not
 * extend past two sources; this keeps an index into a flat list instead.
 */
@Composable
internal fun TickerLogo(
    ticker: String,
    type: PositionType,
    website: String? = null,
    modifier: Modifier = Modifier,
) {
    val candidates = remember(ticker, website) { logoCandidates(ticker, website) }
    var index by remember(ticker, website) { mutableStateOf(0) }

    if (index >= candidates.size) {
        InitialAvatar(ticker, type, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = candidates[index],
        contentDescription = null,
        modifier = modifier.size(40.dp).clip(CircleShape),
        loading = { InitialAvatar(ticker, type, modifier) },
        error = { InitialAvatar(ticker, type, modifier) },
        onError = { index += 1 },
    )
}
```

Move `InitialAvatar` across too, adding a `modifier: Modifier = Modifier` parameter, and update its single call site inside `PositionCard` to `TickerLogo(row.ticker, row.type)`.

- [ ] **Step 4: Run tests and build**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest && ./gradlew :app:assembleDebug`
Expected: all existing tests still PASS, plus 6 new ones.

- [ ] **Step 5: Commit**

```bash
git add android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/TickerLogo.kt \
        android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt \
        android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/TickerLogoTest.kt
git commit -m "fix(android): replace the dead Clearbit logo source with a four-step chain"
```

---

### Task 4: Local logo files

The other half of Part 5: bundled assets, first-run copy, and the in-app picker.

**Files:**
- Create: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/LogoStore.kt`
- Create: `android/app/src/main/assets/logos/README.md` plus the PNGs
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/TickerLogo.kt` — local file first in the chain
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt` — "Set logo" / "Reset logo" in `PositionDetailRoute`
- Test: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LogoStoreTest.kt`

**Interfaces:**
- Consumes: `logoCandidates` from Task 3.
- Produces:
  - `class LogoStore(private val filesDir: File)` with `fun fileFor(ticker: String): File`, `fun has(ticker: String): Boolean`, `suspend fun save(ticker: String, source: InputStream)`, `fun clear(ticker: String)`, `suspend fun seedFromAssets(assets: AssetManager)`.
  - `TickerLogo` gains a `localFile: File?` parameter, placed first in the candidate walk.

**Bundled PNGs:** `VIG.PR`, `CSG.AS`, `CSG.PR`, `8306.T`, `8591.T` — the holdings where both favicon services and FMP return 404 (verified 2026-09-19). Source each from the company's own website, downscale to 128x128 PNG. These are trademarked images in a private personal repository, not redistributed.

Saving downscales to 128x128 before writing, so a 4 MB phone photo does not become a 4 MB file read on every list render.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LogoStoreTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun fileFor_normalisesTickerCaseAndKeepsSuffix() {
        val store = LogoStore(temp.root)
        assertEquals("VIG.PR.png", store.fileFor("vig.pr").name)
    }

    @Test
    fun has_isFalseUntilSaved_thenTrue_thenFalseAfterClear() {
        val store = LogoStore(temp.root)
        assertFalse(store.has("VIG.PR"))
        store.fileFor("VIG.PR").also { it.parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.has("VIG.PR"))
        store.clear("VIG.PR")
        assertFalse(store.has("VIG.PR"))
    }

    @Test
    fun fileFor_rejectsPathTraversal() {
        val store = LogoStore(temp.root)
        val file = store.fileFor("../../etc/passwd")
        assertTrue(file.canonicalPath.startsWith(temp.root.canonicalPath))
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*LogoStoreTest*'`
Expected: FAIL — unresolved reference `LogoStore`.

- [ ] **Step 3: Implement `LogoStore`**

```kotlin
package com.stocktracker.feature.portfolio

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream

private const val LOGO_SIZE_PX = 128

/**
 * Per-install logo overrides in app-private storage. A file here beats every remote source,
 * so it is how a ticker that no logo service covers (VIG.PR, CSG.*, the Japanese banks) gets
 * an image at all, and how any ticker gets corrected by hand.
 *
 * Not synced: these are binary blobs, and the persist server stores one JSON array per key.
 */
class LogoStore(private val filesDir: File) {
    private val dir: File get() = File(filesDir, "logos")

    /**
     * Ticker goes into a filename, so it is sanitised rather than trusted: a ticker arrives
     * from an imported broker statement, and `../` in a filename would escape app storage.
     */
    fun fileFor(ticker: String): File {
        val safe = ticker.uppercase().replace(Regex("[^A-Z0-9.\\-]"), "_")
        return File(dir, "$safe.png")
    }

    fun has(ticker: String): Boolean = fileFor(ticker).exists()

    fun clear(ticker: String) { fileFor(ticker).delete() }

    suspend fun save(ticker: String, source: InputStream): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val decoded = source.use { BitmapFactory.decodeStream(it) }
            ?: throw IllegalArgumentException("Not a decodable image")
        val scaled = Bitmap.createScaledBitmap(decoded, LOGO_SIZE_PX, LOGO_SIZE_PX, true)
        fileFor(ticker).outputStream().use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Copies bundled assets in on first run; never overwrites a user-set file. */
    suspend fun seedFromAssets(assets: AssetManager): Unit = withContext(Dispatchers.IO) {
        dir.mkdirs()
        val names = runCatching { assets.list("logos").orEmpty() }.getOrDefault(emptyArray())
        names.filter { it.endsWith(".png") }.forEach { name ->
            val target = File(dir, name)
            if (!target.exists()) {
                assets.open("logos/$name").use { input ->
                    target.outputStream().use { input.copyTo(it) }
                }
            }
        }
    }
}
```

- [ ] **Step 4: Run the test**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*LogoStoreTest*'`
Expected: PASS, 3 tests.

- [ ] **Step 5: Wire local files into the chain**

In `TickerLogo.kt`, add a `localFile: File?` parameter and put it first:

```kotlin
    val model = when {
        localFile != null && index == 0 -> localFile
        else -> candidates.getOrNull(index - if (localFile != null) 1 else 0)
    }
```

Keep this readable: compute `val sources: List<Any> = listOfNotNull(localFile) + candidates` once, and index into that single list rather than juggling offsets.

- [ ] **Step 6: Add the picker to the detail screen**

In `PositionDetailRoute`, next to the existing "Set price" / "Sell" / "Delete" row:

```kotlin
    val context = LocalContext.current
    val logoStore = remember { LogoStore(context.filesDir) }
    var logoVersion by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                context.contentResolver.openInputStream(uri)?.let { logoStore.save(row.ticker, it) }
                logoVersion += 1
            }
        }
    }
```

with buttons `AppButton(text = "Set logo", onClick = { pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })` and, when `logoStore.has(row.ticker)`, `AppButton(text = "Reset logo", onClick = { logoStore.clear(row.ticker); logoVersion += 1 })`.

`logoVersion` is keyed into `TickerLogo`'s `remember` so a newly saved file actually re-renders — Coil caches by model, and the `File` object is equal across saves.

- [ ] **Step 7: Seed assets on first run**

In `StockTrackerApp` (`app/src/main/java/com/stocktracker/app/StockTrackerApp.kt`), inside the existing application-scope coroutine, call `LogoStore(filesDir).seedFromAssets(assets)`. If no such scope exists, create one with `CoroutineScope(Dispatchers.IO + SupervisorJob())` and note why in a comment.

- [ ] **Step 8: Add the PNGs**

Fetch each from the company's own site, downscale to 128x128, save as `android/app/src/main/assets/logos/<TICKER>.png`. Write `assets/logos/README.md` naming the exact filenames and where each came from.

- [ ] **Step 9: Build, install, verify on the emulator**

Run: `cd android && ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk`
Confirm the previously-blank tickers now show artwork, and that "Set logo" then "Reset logo" round-trips.

- [ ] **Step 10: Commit**

```bash
git add android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/LogoStore.kt \
        android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/TickerLogo.kt \
        android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt \
        android/app/src/main/java/com/stocktracker/app/StockTrackerApp.kt \
        android/app/src/main/assets/logos \
        android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LogoStoreTest.kt
git commit -m "feat(android): local logo overrides with bundled assets and a photo picker"
```

---

### Task 5: Sort model and persistence

Implements the data half of Part 4.

**Files:**
- Create: `android/core/model/src/main/kotlin/com/stocktracker/core/model/SortOrder.kt`
- Create: `android/core/calc/src/main/kotlin/com/stocktracker/core/calc/RowSorting.kt`
- Modify: `android/core/data/src/main/kotlin/com/stocktracker/core/data/SettingsRepository.kt`
- Test: `android/core/calc/src/test/kotlin/com/stocktracker/core/calc/RowSortingTest.kt`

**Interfaces:**
- Consumes: `convert(amount, from, to, rates)` from `core:calc`.
- Produces:
  - `enum class SortField { NAME, TYPE, VALUE, TODAY, TOTAL_RETURN }`
  - `data class SortOrder(val field: SortField = SortField.VALUE, val ascending: Boolean = false)`
  - `fun List<PortfolioRow>.sortedBy(order: SortOrder, displayCurrency: String, rates: Map<String, Double>): List<PortfolioRow>`
  - `AppSettings.sortField: String`, `AppSettings.sortAscending: Boolean`, `SettingsRepository.setSortOrder(order: SortOrder)`

**Why conversion matters:** a portfolio holding CZK and USD positions sorted on raw `currentValue` orders by numbers in different units — 100 USD would rank below 200 CZK. Every monetary comparison converts first.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.core.calc

import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Test

class RowSortingTest {
    private val rates = mapOf("CZK" to 1.0, "USD" to 23.0)

    @Test
    fun value_sortsOnConvertedAmounts_notRawNumbers() {
        val czk = row(ticker = "CEZ.PR", currency = "CZK", currentValue = 200.0)
        val usd = row(ticker = "AAPL", currency = "USD", currentValue = 100.0)
        val sorted = listOf(czk, usd).sortedBy(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(listOf("AAPL", "CEZ.PR"), sorted.map { it.ticker })
    }

    @Test
    fun name_isCaseInsensitive() {
        val sorted = listOf(row(name = "zeta"), row(name = "Alpha"))
            .sortedBy(SortOrder(SortField.NAME, ascending = true), "CZK", rates)
        assertEquals(listOf("Alpha", "zeta"), sorted.map { it.name })
    }

    @Test
    fun descending_isTheReverseOfAscending() {
        val rows = listOf(row(ticker = "A", currentValue = 1.0), row(ticker = "B", currentValue = 2.0))
        val asc = rows.sortedBy(SortOrder(SortField.VALUE, ascending = true), "CZK", rates)
        val desc = rows.sortedBy(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(asc.map { it.ticker }.reversed(), desc.map { it.ticker })
    }

    @Test
    fun equalKeys_fallBackToTicker_soOrderIsStable() {
        val rows = listOf(row(ticker = "B", currentValue = 5.0), row(ticker = "A", currentValue = 5.0))
        val sorted = rows.sortedBy(SortOrder(SortField.VALUE, ascending = false), "CZK", rates)
        assertEquals(listOf("A", "B"), sorted.map { it.ticker })
    }
}
```

Add a local `row(...)` helper in the test file mirroring `PortfolioTestFixtures.fakeRow` — `core:calc` has no dependency on `feature:portfolio`, so it cannot reuse that one. Copy only the fields these tests need and give the rest fixed values.

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :core:calc:test --tests '*RowSortingTest*'`
Expected: FAIL — unresolved reference `SortOrder`.

- [ ] **Step 3: Implement the model and comparator**

```kotlin
// core/model/SortOrder.kt
package com.stocktracker.core.model

enum class SortField { NAME, TYPE, VALUE, TODAY, TOTAL_RETURN }

data class SortOrder(
    val field: SortField = SortField.VALUE,
    val ascending: Boolean = false,
)
```

```kotlin
// core/calc/RowSorting.kt
package com.stocktracker.core.calc

import com.stocktracker.core.model.PortfolioRow
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder

/**
 * Monetary fields are converted to [displayCurrency] before comparison: sorting a mixed
 * CZK/USD portfolio on raw values compares numbers in different units, which silently
 * mis-orders the list.
 *
 * Ties break on ticker so the order is total and the list does not shuffle between renders.
 */
fun List<PortfolioRow>.sortedBy(
    order: SortOrder,
    displayCurrency: String,
    rates: Map<String, Double>,
): List<PortfolioRow> {
    fun money(amount: Double, from: String) = convert(amount, from, displayCurrency, rates)
    val comparator: Comparator<PortfolioRow> = when (order.field) {
        SortField.NAME -> compareBy { it.name.lowercase() }
        SortField.TYPE -> compareBy { it.type.ordinal }
        SortField.VALUE -> compareBy { money(it.currentValue, it.currency) }
        SortField.TODAY -> compareBy { money(it.dailyChange, it.currency) }
        SortField.TOTAL_RETURN -> compareBy { money(it.totalReturn, it.currency) }
    }
    val directed = if (order.ascending) comparator else comparator.reversed()
    return sortedWith(directed.thenBy { it.ticker })
}
```

- [ ] **Step 4: Run the tests**

Run: `cd android && ./gradlew :core:calc:test --tests '*RowSortingTest*'`
Expected: PASS, 4 tests.

- [ ] **Step 5: Persist the choice**

In `SettingsRepository.kt`, add to `AppSettings`:

```kotlin
    /** Serialized [com.stocktracker.core.model.SortField] name; "" means the default. */
    val sortField: String = "VALUE",
    val sortAscending: Boolean = false,
```

add the keys:

```kotlin
        val SORT_FIELD = stringPreferencesKey("sort_field")
        val SORT_ASCENDING = booleanPreferencesKey("sort_ascending")
```

read them in the `map`, and add:

```kotlin
    suspend fun setSortOrder(order: SortOrder): Unit = dataStore.edit {
        it[Keys.SORT_FIELD] = order.field.name
        it[Keys.SORT_ASCENDING] = order.ascending
    }
```

Import `androidx.datastore.preferences.core.booleanPreferencesKey`. A stored field name that no longer parses falls back to the default rather than throwing — use `runCatching { SortField.valueOf(raw) }.getOrDefault(SortField.VALUE)` at the read site.

- [ ] **Step 6: Build and run the full suite**

Run: `cd android && ./gradlew test`
Expected: all PASS.

- [ ] **Step 7: Commit**

```bash
git add android/core/model/src/main/kotlin/com/stocktracker/core/model/SortOrder.kt \
        android/core/calc/src/main/kotlin/com/stocktracker/core/calc/RowSorting.kt \
        android/core/calc/src/test/kotlin/com/stocktracker/core/calc/RowSortingTest.kt \
        android/core/data/src/main/kotlin/com/stocktracker/core/data/SettingsRepository.kt
git commit -m "feat(android): FX-aware portfolio row sorting with persisted order"
```

---

### Task 6: Sort chip row

The UI half of Part 4.

**Files:**
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListUiState.kt`
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListViewModel.kt`
- Modify: `android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/PortfolioListScreen.kt`
- Test: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/SortChipsTest.kt`

**Interfaces:**
- Consumes: `SortOrder`, `SortField`, `List<PortfolioRow>.sortedBy` from Task 5; layout helpers from Task 1.
- Produces: `PortfolioListUiState.sortOrder: SortOrder`, `PortfolioListAction.SetSort(field: SortField)`, `@Composable internal fun SortChips(uiState, onAction)`.

`visibleRows` applies the sort **after** the closed filter, so "Show closed" and the sort compose instead of fighting.

- [ ] **Step 1: Write the failing test**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.stocktracker.core.designsystem.StockTrackerTheme
import com.stocktracker.core.model.SortField
import com.stocktracker.core.model.SortOrder
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SortChipsTest {
    @get:Rule val composeTestRule = createComposeRule()

    private fun setChips(order: SortOrder, onAction: (PortfolioListAction) -> Unit = {}) {
        composeTestRule.setContent {
            StockTrackerTheme {
                SortChips(PortfolioListUiState(sortOrder = order, isLoading = false), onAction)
            }
        }
    }

    @Test
    fun allFiveFields_areShown() {
        setChips(SortOrder())
        listOf("Name", "Type", "Value", "Today", "Return").forEach {
            composeTestRule.onNodeWithText(it, substring = true).assertIsDisplayed()
        }
    }

    @Test
    fun tappingInactiveChip_selectsThatField() {
        var received: PortfolioListAction? = null
        setChips(SortOrder(SortField.VALUE)) { received = it }
        composeTestRule.onNodeWithText("Name", substring = true).performClick()
        assertEquals(PortfolioListAction.SetSort(SortField.NAME), received)
    }

    @Test
    fun tappingActiveChip_flipsDirection() {
        var received: PortfolioListAction? = null
        setChips(SortOrder(SortField.VALUE, ascending = false)) { received = it }
        composeTestRule.onNodeWithText("Value", substring = true).performClick()
        assertEquals(PortfolioListAction.SetSort(SortField.VALUE), received)
    }

    @Test
    fun chipLabels_areNotTruncated() {
        setChips(SortOrder())
        listOf("Name", "Type", "Value", "Today", "Return").forEach {
            composeTestRule.assertTextNotTruncated(it)
        }
    }

    @Test
    fun chipRow_hasNoClippedText() {
        setChips(SortOrder())
        composeTestRule.assertNoClippedText()
    }
}
```

Note: the direction flip is decided in the ViewModel, not the chip — `SetSort(field)` for the already-active field means "flip". The chip stays dumb, which is what makes it testable without a ViewModel.

- [ ] **Step 2: Run it and confirm it fails**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*SortChipsTest*'`
Expected: FAIL — unresolved reference `SortChips`.

- [ ] **Step 3: Add state and action**

In `PortfolioListUiState.kt`, add `val sortOrder: SortOrder = SortOrder()` and change `visibleRows`:

```kotlin
    val visibleRows: List<PortfolioRow>
        get() = (if (showClosed) rows else rows.filterNot { it.isClosed })
            .sortedBy(sortOrder, displayCurrency, rates)
```

and add `data class SetSort(val field: SortField) : PortfolioListAction`.

- [ ] **Step 4: Handle the action in the ViewModel**

```kotlin
            is PortfolioListAction.SetSort -> viewModelScope.launch {
                val current = uiState.value.sortOrder
                // Tapping the chip that is already active means "flip direction" — a second
                // control just for direction would cost a row of screen for one bit of state.
                val next = if (current.field == action.field) {
                    current.copy(ascending = !current.ascending)
                } else {
                    SortOrder(action.field, ascending = action.field == SortField.NAME)
                }
                settingsRepository.setSortOrder(next)
            }
```

Newly selecting Name defaults to ascending (A-Z reads naturally); every other field defaults to descending (largest first). Feed `settings.sortField`/`sortAscending` into the ui state in whatever `combine` already supplies `displayCurrency`.

- [ ] **Step 5: Add the chip row**

```kotlin
private val SORT_LABELS = listOf(
    SortField.NAME to "Name",
    SortField.TYPE to "Type",
    SortField.VALUE to "Value",
    SortField.TODAY to "Today",
    SortField.TOTAL_RETURN to "Return",
)

/** Same selected/unselected language as [CurrencyTabs]; scrolls because five chips do not fit 320dp. */
@Composable
internal fun SortChips(uiState: PortfolioListUiState, onAction: (PortfolioListAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SORT_LABELS.forEach { (field, label) ->
            val active = uiState.sortOrder.field == field
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                border = if (active) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Text(
                    text = if (active) "$label ${if (uiState.sortOrder.ascending) "▲" else "▼"}" else label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier
                        .clickable { onAction(PortfolioListAction.SetSort(field)) }
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                )
            }
        }
    }
}
```

Place `SortChips(uiState, onAction)` directly under `CurrencyTabs(...)` in `PortfolioListScreen`.

- [ ] **Step 6: Run everything**

Run: `cd android && ./gradlew test && ./gradlew :app:assembleDebug`
Expected: all PASS.

- [ ] **Step 7: Verify on the emulator**

Install, tap each chip, confirm order changes and the arrow flips, then kill and relaunch the app and confirm the choice survived.

- [ ] **Step 8: Commit**

```bash
git add android/feature/portfolio/src/main/kotlin/com/stocktracker/feature/portfolio/ \
        android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/SortChipsTest.kt
git commit -m "feat(android): sort chips for name, type, value, today and total return"
```

---

### Task 7: Layout matrix and golden screenshots

**Files:**
- Modify: `android/gradle/libs.versions.toml`, `android/build.gradle.kts`, `android/feature/portfolio/build.gradle.kts`
- Create: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/LayoutMatrixTest.kt`
- Create: `android/feature/portfolio/src/test/kotlin/com/stocktracker/feature/portfolio/GoldenScreenshotTest.kt`

**Interfaces:**
- Consumes: everything above.
- Produces: nothing other tasks depend on.

- [ ] **Step 1: Write the matrix test**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.FontScale
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.then
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LayoutMatrixTest {
    @get:Rule val composeTestRule = createComposeRule()

    private val widths = listOf(320.dp, 360.dp, 411.dp)
    private val fontScales = listOf(1.0f, 1.3f, 2.0f)

    /** The real portfolio holds all of this: a long name, a big number, and diacritics. */
    private val hostileRow = fakeRow(
        ticker = "EXUS.DE",
        name = "Xtrackers MSCI World ex USA UCITS ETF 1C USD",
        currentValue = 12_345_678.90,
        totalReturn = -98_765.43,
        dailyChange = -1_234.56,
    )

    @Test
    fun positionCard_survivesEveryWidthAndFontScale() {
        widths.forEach { width ->
            fontScales.forEach { scale ->
                composeTestRule.setContent {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.ForcedSize(DpSize(width, 800.dp)) then
                            DeviceConfigurationOverride.FontScale(scale),
                    ) {
                        StockTrackerTheme {
                            PositionCard(
                                row = hostileRow,
                                displayCurrency = "CZK",
                                rates = mapOf("CZK" to 1.0, "USD" to 23.0),
                                onOpenDetail = {},
                            )
                        }
                    }
                }
                composeTestRule.assertNoClippedText()
            }
        }
    }

    @Test
    fun sortChips_surviveEveryWidthAndFontScale() {
        widths.forEach { width ->
            fontScales.forEach { scale ->
                composeTestRule.setContent {
                    DeviceConfigurationOverride(
                        DeviceConfigurationOverride.ForcedSize(DpSize(width, 800.dp)) then
                            DeviceConfigurationOverride.FontScale(scale),
                    ) {
                        StockTrackerTheme { SortChips(PortfolioListUiState(isLoading = false), {}) }
                    }
                }
                composeTestRule.assertNoClippedText()
                composeTestRule.assertTextNotTruncated("Value")
            }
        }
    }
}
```

`createComposeRule` allows `setContent` only once per test, so if the loop above throws "Content has already been set", restructure each combination into its own `@Test` via a parameterised runner, or give each combination its own rule instance. Resolve it whichever way compiles — do not delete the matrix.

If `DeviceConfigurationOverride` is not resolvable at Compose 1.6.8, fall back to wrapping content in `CompositionLocalProvider(LocalDensity provides Density(density = 2f, fontScale = scale))` inside a `Box(Modifier.size(width, 800.dp))`, which is what the override does internally.

- [ ] **Step 2: Run it**

Run: `cd android && ./gradlew :feature:portfolio:testDebugUnitTest --tests '*LayoutMatrixTest*'`
Expected: PASS. A failure here is a real finding — fix the layout, not the test.

- [ ] **Step 3: Add Roborazzi**

`gradle/libs.versions.toml`:

```toml
roborazzi = "1.26.0"
```
```toml
roborazzi = { group = "io.github.takahirom.roborazzi", name = "roborazzi", version.ref = "roborazzi" }
roborazzi-compose = { group = "io.github.takahirom.roborazzi", name = "roborazzi-compose", version.ref = "roborazzi" }
roborazzi-rule = { group = "io.github.takahirom.roborazzi", name = "roborazzi-junit-rule", version.ref = "roborazzi" }
roborazzi-plugin = { id = "io.github.takahirom.roborazzi", version.ref = "roborazzi" }
```

Apply the plugin in the root `build.gradle.kts` with `apply false`, apply it in `feature/portfolio/build.gradle.kts`, add the three `testImplementation` lines, and enable `testOptions.unitTests.isIncludeAndroidResources = true` if it is not already set.

Check the current Roborazzi release supports Compose 1.6 / AGP in use before pinning the version; adjust if not.

- [ ] **Step 4: Write the golden test**

```kotlin
package com.stocktracker.feature.portfolio

import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.stocktracker.core.designsystem.StockTrackerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Goldens are recorded at one configuration only - 360dp, font scale 1.0, dark theme - to keep
 * the image count and repository churn down. The wide size/font matrix stays assertion-based
 * in LayoutMatrixTest; this catches what assertions cannot describe: spacing, alignment, a
 * chart that renders blank.
 *
 * Regenerate deliberately with `./gradlew :feature:portfolio:recordRoborazziDebug` and review
 * the diff before committing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w360dp-h800dp-night")
class GoldenScreenshotTest {
    @get:Rule val composeTestRule = createComposeRule()

    @Test
    fun positionCard() {
        composeTestRule.setContent {
            StockTrackerTheme { PositionCard(fakeRow(), "CZK", mapOf("CZK" to 1.0), {}) }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/position_card.png")
    }

    @Test
    fun sortChips() {
        composeTestRule.setContent {
            StockTrackerTheme { SortChips(PortfolioListUiState(isLoading = false), {}) }
        }
        composeTestRule.onRoot().captureRoboImage("src/test/screenshots/sort_chips.png")
    }
}
```

- [ ] **Step 5: Record and verify**

Run:
```bash
cd android
./gradlew :feature:portfolio:recordRoborazziDebug --tests '*GoldenScreenshotTest*'
./gradlew :feature:portfolio:verifyRoborazziDebug --tests '*GoldenScreenshotTest*'
```
Open the recorded PNGs and look at them before committing. A golden recorded from broken UI locks the breakage in.

- [ ] **Step 6: Commit**

```bash
git add android/gradle/libs.versions.toml android/build.gradle.kts \
        android/feature/portfolio/build.gradle.kts \
        android/feature/portfolio/src/test/
git commit -m "test(android): layout matrix assertions and Roborazzi goldens"
```

---

### Task 8: Update CLAUDE.md

**Files:**
- Modify: `CLAUDE.md`

- [ ] **Step 1: Document what changed**

In the "Android Companion App" section, record: the logo chain and that Clearbit is dead (so nobody restores it); `LogoStore` and the bundled `assets/logos/` convention; the sort order living in settings DataStore; and the layout-assertion helpers plus the Roborazzi record/verify commands.

- [ ] **Step 2: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: record the logo chain, sort order and layout test tooling"
```

---

## Self-Review

**Spec coverage:** Part 4 → Tasks 5-6. Part 5 → Tasks 3-4. Part 6 → Task 2. Layout robustness → Tasks 1, 7. Parts 1-3 and 7 are out of this plan's scope by design and belong to the later plans.

**Placeholders:** none — every step carries the code or the exact command.

**Type consistency:** `SortOrder`/`SortField` are defined in Task 5 and used unchanged in Task 6. `logoCandidates` is defined in Task 3 and extended in Task 4. The layout helpers are defined in Task 1 and used in Tasks 2, 6, 7 under the same names.

**Known unknowns, flagged at their step rather than hidden:** whether `DeviceConfigurationOverride` resolves at Compose 1.6.8 (Task 7 Step 1 gives the fallback), whether `createComposeRule` permits repeated `setContent` (same step), and the current Roborazzi version's compatibility (Task 7 Step 3).
