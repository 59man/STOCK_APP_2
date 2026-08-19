package com.stocktracker.core.data

import com.stocktracker.core.data.sync.SyncCoordinator
import com.stocktracker.core.data.sync.SyncTarget
import com.stocktracker.core.importer.ColumnMapping
import com.stocktracker.core.importer.ImportLookups
import com.stocktracker.core.importer.MappingDefaults
import com.stocktracker.core.importer.ParseFileResult
import com.stocktracker.core.importer.ParseResult
import com.stocktracker.core.model.PositionType
import com.stocktracker.core.network.YahooLookupClient
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Result of committing a parse — how many positions actually landed vs. how many were already there. */
data class CommitResult(val committed: Int, val duplicatesSkipped: Int)

private fun mapPositionType(type: String): PositionType = when (type) {
    "etf" -> PositionType.ETF
    "fund" -> PositionType.FUND
    "commodity" -> PositionType.COMMODITY
    else -> PositionType.STOCK
}

private val lookups = ImportLookups(
    lookupIsins = { isins ->
        YahooLookupClient.batchIsins(isins).mapValues { (_, info) ->
            com.stocktracker.core.importer.QuoteInfo(info.ticker, mapPositionType(info.type))
        }
    },
    lookupTickers = { tickers ->
        YahooLookupClient.batchTickers(tickers).mapValues { (_, info) ->
            com.stocktracker.core.importer.QuoteInfo(info.ticker, mapPositionType(info.type))
        }
    },
)

/**
 * Bridges the pure, network-agnostic parsers in core:import to a real Yahoo
 * lookup (core:network) and to Room (via the other repositories) — the
 * on-device counterpart of ImportModal + importParser.ts committing into
 * usePortfolio/useManualPrices/useManualDividendTaxes.
 */
@Singleton
class ImportRepository @Inject constructor(
    private val positionRepository: PositionRepository,
    private val manualPriceRepository: ManualPriceRepository,
    private val divTaxOverrideRepository: DivTaxOverrideRepository,
    private val syncCoordinator: SyncCoordinator,
) {
    suspend fun parseFile(bytes: ByteArray, fileName: String): ParseFileResult? =
        com.stocktracker.core.importer.parseFile(bytes, fileName, lookups)

    suspend fun parseWithMapping(rows: List<List<String>>, mapping: ColumnMapping, defaults: MappingDefaults): ParseResult? =
        com.stocktracker.core.importer.parseWithMapping(rows, mapping, defaults, lookups.lookupTickers)

    fun autoDetectMapping(header: List<String>): ColumnMapping = com.stocktracker.core.importer.autoDetectMapping(header)

    /** Feeds OCR'd lines from a photographed statement through the same generic ISIN+keyword+date+numbers heuristic the PDF parsers fall back to. */
    suspend fun parseOcrText(lines: List<String>): ParseResult = com.stocktracker.core.importer.parseGeneric(lines, lookups.lookupIsins)

    /**
     * Commits a successful parse into the target portfolio and enqueues a sync push for every
     * key touched. Positions already present in the portfolio (same broker+ticker+date+qty+price)
     * are dropped first, so re-uploading the same monthly statement doesn't double-count it.
     */
    suspend fun commit(portfolioId: String, result: ParseResult, currencyOverride: String?): CommitResult {
        val positions = if (currencyOverride != null) {
            result.valid.map { it.copy(currency = currencyOverride) }
        } else {
            result.valid
        }
        val existing = positionRepository.observe(portfolioId).first()
        val (toInsert, duplicates) = com.stocktracker.core.importer.filterDuplicates(positions, existing)

        if (toInsert.isNotEmpty()) {
            positionRepository.upsertAll(portfolioId, toInsert)
            syncCoordinator.enqueuePush(portfolioId, SyncTarget.POSITIONS)
        }

        result.manualPrices?.let { prices ->
            prices.forEach { (ticker, entry) -> manualPriceRepository.set(portfolioId, ticker, entry.price, entry.updatedAt) }
            if (prices.isNotEmpty()) syncCoordinator.enqueuePush(portfolioId, SyncTarget.MANUAL_PRICES)
        }

        result.dividendTaxOverrides?.let { overrides ->
            overrides.forEach { (compositeKey, rate) ->
                val parts = compositeKey.split("::")
                if (parts.size == 2) divTaxOverrideRepository.set(portfolioId, parts[0], parts[1], rate)
            }
            if (overrides.isNotEmpty()) syncCoordinator.enqueuePush(portfolioId, SyncTarget.DIV_TAX_OVERRIDES)
        }

        return CommitResult(committed = toInsert.size, duplicatesSkipped = duplicates)
    }

    /** Builds a re-importable JSON backup of one portfolio's positions, manual prices, and div-tax overrides. */
    suspend fun exportPortfolio(portfolioId: String): String {
        val positions = positionRepository.observe(portfolioId).first()
        val manualPrices = manualPriceRepository.observe(portfolioId).first()
        val divTaxOverrides = divTaxOverrideRepository.observe(portfolioId).first()
        return com.stocktracker.core.importer.buildExportJson(positions, manualPrices, divTaxOverrides)
    }
}
