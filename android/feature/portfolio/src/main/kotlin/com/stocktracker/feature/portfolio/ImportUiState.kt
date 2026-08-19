package com.stocktracker.feature.portfolio

import com.stocktracker.core.importer.ColumnMapping
import com.stocktracker.core.importer.ParseResult

enum class ImportTarget { CURRENT, NEW }

/** Mirrors the states ImportModal/ColumnMappingModal walk through in the web app. */
sealed interface ImportUiState {
    data object Idle : ImportUiState
    data object Parsing : ImportUiState

    data class Ready(
        val fileName: String,
        val result: ParseResult,
        val hasCurrentPortfolio: Boolean,
        val target: ImportTarget,
        val newPortfolioName: String,
        val currencyOverride: String,
    ) : ImportUiState

    data class MappingNeeded(
        val fileName: String,
        val rows: List<List<String>>,
        val mapping: ColumnMapping,
        val defaultCurrency: String,
        val defaultBroker: String,
        val skipRows: Int,
    ) : ImportUiState

    data class Error(val message: String) : ImportUiState
    data class Done(val count: Int, val duplicatesSkipped: Int = 0) : ImportUiState
}

val ImportUiState.MappingNeeded.requiredFieldsMapped: Boolean
    get() = mapping.ticker != null && mapping.date != null && mapping.quantity != null && mapping.buyPrice != null
