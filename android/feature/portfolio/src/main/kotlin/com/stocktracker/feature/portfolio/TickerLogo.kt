package com.stocktracker.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.stocktracker.core.model.PositionType
import java.io.File
import java.net.URI

/**
 * Domains for tickers the stock-image API does not carry — it is keyed on US-style base
 * symbols, so anything European or Japanese misses. Same one-line-per-ticker curated-map
 * pattern as TICKER_COUNTRY (core/calc/Dividends.kt).
 *
 * XAU and 4GLD.DE are deliberately absent: a metal ETC has no company logo to fetch, so it
 * should fall straight through to the initials avatar.
 */
internal val TICKER_LOGO_DOMAINS: Map<String, String> = mapOf(
    "VIG.PR" to "vig.com",
    "VIG.VI" to "vig.com",
    "UCG.MI" to "unicreditgroup.eu",
    "DTE.DE" to "telekom.com",
    // No favicon service carries these two, so they are bundled under app/src/main/assets/logos.
    // The domains stay listed anyway: if a bundled file is ever removed, the chain still has
    // something to try.
    "8306.T" to "mufg.jp",
    "8591.T" to "orix.co.jp",
    // csg.com, not csgroup.cz — the old domain no longer resolves, which is why both favicon
    // services returned 404 for it.
    "CSG.AS" to "csg.com",
    "CSG.PR" to "csg.com",
    "COLT.PR" to "coltcz.com",
    "CZG.PR" to "coltcz.com",
    "FIOG.PR" to "fio.cz",
    "KOMB.PR" to "kb.cz",
    "MONET.PR" to "moneta.cz",
    "KOFOL.PR" to "kofola.cz",
    // Philip Morris ČR: philipmorris.cz has no favicon on either service, the group domain does.
    "TABAK.PR" to "pmi.com",
    "AMEW.DE" to "amundietf.com",
    "CW8.PA" to "amundietf.com",
    "LU2606422355" to "onemarkets.cz",
    "LU2606421548" to "onemarkets.cz",
    "LU2595011649" to "onemarkets.cz",
    "EXUS.DE" to "ishares.com",
)

/**
 * Ordered logo sources for a ticker, best quality first. [TickerLogo] walks the list,
 * advancing on each load failure, and falls back to an initials avatar once it runs out.
 *
 * Clearbit used to sit at position two and is gone: `logo.clearbit.com` stopped resolving
 * altogether after HubSpot retired the free API, which is why every non-US holding rendered
 * as a bare initial. The two favicon services replacing it both answer a miss with a real
 * non-2xx, so a failure advances the chain rather than pinning a generic globe in place.
 *
 * Favicons are 16-64px and look soft at 40dp, so they rank below the stock-image API's proper
 * logo art — and below any local override, which [TickerLogo] puts ahead of this list.
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

/** A website arrives from Yahoo's profile data, so it is parsed defensively rather than trusted. */
private fun hostOf(website: String): String? =
    runCatching { URI(website).host }
        .getOrNull()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }

/**
 * 40dp circular avatar walking [logoCandidates] in order, falling back to a colored initial.
 *
 * The previous version nested SubcomposeAsyncImage inside its own error slot, which reads
 * badly past two sources and does not extend to four; this keeps an index into one flat list
 * instead.
 */
@Composable
internal fun TickerLogo(
    ticker: String,
    type: PositionType,
    website: String? = null,
    localFile: File? = null,
    modifier: Modifier = Modifier,
) {
    val sources: List<Any> = remember(ticker, website, localFile) {
        listOfNotNull(localFile?.takeIf { it.exists() }) + logoCandidates(ticker, website)
    }
    var index by remember(sources) { mutableStateOf(0) }

    if (index >= sources.size) {
        InitialAvatar(ticker, type, modifier)
        return
    }
    SubcomposeAsyncImage(
        model = sources[index],
        contentDescription = null,
        modifier = modifier.size(40.dp).clip(CircleShape),
        loading = { InitialAvatar(ticker, type, modifier) },
        error = { InitialAvatar(ticker, type, modifier) },
        onError = { index += 1 },
    )
}

@Composable
internal fun InitialAvatar(ticker: String, type: PositionType, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(40.dp).clip(CircleShape).background(typeBadgeColor(type)),
        contentAlignment = Alignment.Center,
    ) {
        // Font size *and* line height are pinned in dp-derived sp so the glyph ignores the
        // system font scale. It stands in for a logo image inside a fixed 40dp circle: at font
        // scale 2.0 the scaled line height alone overflowed that circle and cut the letter off.
        val density = LocalDensity.current
        Text(
            ticker.take(1).uppercase(),
            style = MaterialTheme.typography.titleMedium.copy(
                fontSize = with(density) { 18.dp.toSp() },
                lineHeight = with(density) { 22.dp.toSp() },
            ),
            fontWeight = FontWeight.Bold,
            color = Color.White,
        )
    }
}
