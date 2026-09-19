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
        assertEquals("VIG.PR.png", LogoStore(temp.root).fileFor("vig.pr").name)
    }

    @Test
    fun has_followsTheFileLifecycle() {
        val store = LogoStore(temp.root)
        assertFalse(store.has("VIG.PR"))
        store.fileFor("VIG.PR").also { it.parentFile?.mkdirs() }.writeBytes(byteArrayOf(1, 2, 3))
        assertTrue(store.has("VIG.PR"))
        store.clear("VIG.PR")
        assertFalse(store.has("VIG.PR"))
    }

    @Test
    fun fileFor_cannotEscapeTheLogoDirectory() {
        // Tickers come out of imported broker statements, so they are sanitised rather than
        // trusted: a "../" in a filename would otherwise write outside app-private storage.
        val file = LogoStore(temp.root).fileFor("../../etc/passwd")
        assertTrue(file.canonicalPath.startsWith(temp.root.canonicalPath))
    }

    @Test
    fun clear_onAMissingFileIsNotAnError() {
        LogoStore(temp.root).clear("NOPE.XX")
    }
}
