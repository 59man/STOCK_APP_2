package com.stocktracker.feature.portfolio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TickerLogoTest {
    @Test
    fun knownDomain_yieldsFmpThenBothFavicons() {
        assertEquals(
            listOf(
                "https://financialmodelingprep.com/image-stock/COLT.PR.png",
                "https://icons.duckduckgo.com/ip3/coltcz.com.ico",
                "https://www.google.com/s2/favicons?domain=coltcz.com&sz=128",
            ),
            logoCandidates("COLT.PR"),
        )
    }

    @Test
    fun suffixedTicker_isLookedUpWithItsExchangeSuffix() {
        // The bare base symbol names a different, US-listed company: EXUS is Nomura, DTE is
        // DTE Energy, VIG is a Vanguard ETF. Only the full symbol finds the right logo.
        assertEquals(
            "https://financialmodelingprep.com/image-stock/EXUS.DE.png",
            logoCandidates("EXUS.DE").first(),
        )
        assertTrue(logoCandidates("DTE.DE").none { it.endsWith("/DTE.png") })
    }

    @Test
    fun unknownTicker_yieldsFmpOnly() {
        assertEquals(
            listOf("https://financialmodelingprep.com/image-stock/ZZZZ.XX.png"),
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
    fun commoditiesGetNoDomainCandidates() {
        // XAU and 4GLD.DE are deliberately absent from the domain map: a metal ETC has no
        // company logo, so it should fall through to the initials avatar rather than show
        // some fund house's favicon.
        assertEquals(
            listOf("https://financialmodelingprep.com/image-stock/XAU.png"),
            logoCandidates("XAU"),
        )
    }

    @Test
    fun malformedWebsite_isIgnoredRatherThanCrashing() {
        assertEquals(
            listOf("https://financialmodelingprep.com/image-stock/ZZZZ.XX.png"),
            logoCandidates("ZZZZ.XX", website = "not a url at all"),
        )
    }
}
