package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** Broker tag stamped on positions the generic heuristic parser (PDF fallback or OCR) had to guess at — never read directly off a statement. */
const val UNVERIFIED_BROKER = "Unknown (verify)"

/** Mirrors QuoteInfo in src/utils/yahooLookup.ts. */
data class QuoteInfo(val ticker: String, val type: PositionType)

/**
 * Injected rather than called directly, so every parser here stays a pure
 * function testable with a fake lookup — the real implementation (Yahoo
 * search via core:network) is wired in by the caller.
 */
typealias IsinLookup = suspend (List<String>) -> Map<String, QuoteInfo>
typealias TickerLookup = suspend (List<String>) -> Map<String, QuoteInfo>

/** Mirrors `ParseResult` in src/utils/importParser.ts. */
data class ParseResult(
    val valid: List<Position>,
    val skipped: Int,
    val dividendTaxOverrides: Map<String, Double>? = null,
    val manualPrices: Map<String, ManualPriceImport>? = null,
    /** Parser had to guess the lot currency (e.g. an XTB file without an EUR_/CZK_ filename prefix). */
    val currencyUncertain: Boolean = false,
)

data class ManualPriceImport(val price: Double, val updatedAt: String)

/** Mirrors `NeedsMapping` — an unrecognized tabular file, returned for the column-mapping wizard. */
data class NeedsMapping(val rows: List<List<String>>)

sealed interface ParseFileResult
data class ParseResultFile(val result: ParseResult) : ParseFileResult
data class NeedsMappingFile(val needsMapping: NeedsMapping) : ParseFileResult

/** Mirrors `ColumnMapping` in src/utils/importParser.ts — column indices, null = unmapped. */
data class ColumnMapping(
    val ticker: Int?,
    val date: Int?,
    val quantity: Int?,
    val buyPrice: Int?,
    val name: Int?,
    val isin: Int?,
    val currency: Int?,
    val broker: Int?,
    val sellDate: Int?,
    val sellPrice: Int?,
)

data class MappingDefaults(val currency: String, val broker: String, val skipRows: Int)
