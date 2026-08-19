package com.stocktracker.feature.portfolio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.stocktracker.core.data.ImportRepository
import com.stocktracker.core.data.PortfolioRepository
import com.stocktracker.core.importer.ColumnMapping
import com.stocktracker.core.importer.MappingDefaults
import com.stocktracker.core.importer.NeedsMappingFile
import com.stocktracker.core.importer.ParseResult
import com.stocktracker.core.importer.ParseResultFile
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ImportViewModel @Inject constructor(
    private val importRepository: ImportRepository,
    private val portfolioRepository: PortfolioRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState

    fun onFilePicked(bytes: ByteArray, fileName: String, hasCurrentPortfolio: Boolean) {
        _uiState.value = ImportUiState.Parsing
        viewModelScope.launch {
            try {
                when (val parsed = importRepository.parseFile(bytes, fileName)) {
                    null -> _uiState.value = ImportUiState.Error("Couldn't read \"$fileName\" — unrecognized file format.")
                    is ParseResultFile -> onParsed(fileName, parsed.result, hasCurrentPortfolio)
                    is NeedsMappingFile -> {
                        val header = parsed.needsMapping.rows.firstOrNull().orEmpty()
                        _uiState.value = ImportUiState.MappingNeeded(
                            fileName = fileName,
                            rows = parsed.needsMapping.rows,
                            mapping = importRepository.autoDetectMapping(header),
                            defaultCurrency = "USD",
                            defaultBroker = "",
                            skipRows = 1,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.value = ImportUiState.Error(e.message ?: "Import failed")
            }
        }
    }

    private fun onParsed(fileName: String, result: ParseResult, hasCurrentPortfolio: Boolean) {
        if (result.valid.isEmpty()) {
            _uiState.value = ImportUiState.Error("No positions found in \"$fileName\".")
            return
        }
        _uiState.value = ImportUiState.Ready(
            fileName = fileName,
            result = result,
            hasCurrentPortfolio = hasCurrentPortfolio,
            target = if (hasCurrentPortfolio) ImportTarget.CURRENT else ImportTarget.NEW,
            newPortfolioName = fileName.substringBeforeLast('.').ifBlank { "Imported" },
            currencyOverride = "CZK",
        )
    }

    fun setTarget(target: ImportTarget) = updateReady { it.copy(target = target) }
    fun setNewPortfolioName(name: String) = updateReady { it.copy(newPortfolioName = name) }
    fun setCurrencyOverride(currency: String) = updateReady { it.copy(currencyOverride = currency) }

    private inline fun updateReady(transform: (ImportUiState.Ready) -> ImportUiState.Ready) {
        _uiState.update { current -> if (current is ImportUiState.Ready) transform(current) else current }
    }

    fun updateMapping(mapping: ColumnMapping) = updateMappingState { it.copy(mapping = mapping) }
    fun updateSkipRows(skipRows: Int) = updateMappingState { it.copy(skipRows = skipRows) }
    fun updateDefaultCurrency(currency: String) = updateMappingState { it.copy(defaultCurrency = currency) }
    fun updateDefaultBroker(broker: String) = updateMappingState { it.copy(defaultBroker = broker) }

    private inline fun updateMappingState(transform: (ImportUiState.MappingNeeded) -> ImportUiState.MappingNeeded) {
        _uiState.update { current -> if (current is ImportUiState.MappingNeeded) transform(current) else current }
    }

    fun confirmMapping(hasCurrentPortfolio: Boolean) {
        val state = _uiState.value as? ImportUiState.MappingNeeded ?: return
        _uiState.value = ImportUiState.Parsing
        viewModelScope.launch {
            val result = importRepository.parseWithMapping(
                state.rows,
                state.mapping,
                MappingDefaults(state.defaultCurrency, state.defaultBroker, state.skipRows),
            )
            if (result == null) {
                _uiState.value = ImportUiState.Error("No valid rows found with this column mapping.")
            } else {
                onParsed(state.fileName, result, hasCurrentPortfolio)
            }
        }
    }

    fun confirmImport(activePortfolioId: String?) {
        val state = _uiState.value as? ImportUiState.Ready ?: return
        _uiState.value = ImportUiState.Parsing
        viewModelScope.launch {
            val portfolioId = if (state.target == ImportTarget.CURRENT && activePortfolioId != null) {
                activePortfolioId
            } else {
                portfolioRepository.add(state.newPortfolioName.ifBlank { "Imported" }).id
            }
            val currencyOverride = state.currencyOverride.takeIf { state.result.currencyUncertain }
            val commitResult = importRepository.commit(portfolioId, state.result, currencyOverride)
            _uiState.value = ImportUiState.Done(commitResult.committed, commitResult.duplicatesSkipped)
        }
    }

    /** A photographed statement, OCR'd on-device — feeds the same generic parser fallback as an unrecognized PDF. */
    fun onOcrTextExtracted(lines: List<String>, hasCurrentPortfolio: Boolean) {
        _uiState.value = ImportUiState.Parsing
        viewModelScope.launch {
            val result = importRepository.parseOcrText(lines)
            onParsed("Scanned statement", result, hasCurrentPortfolio)
        }
    }

    fun onOcrFailed() {
        _uiState.value = ImportUiState.Error("Couldn't read that photo — try a clearer, well-lit shot of the statement.")
    }

    fun reset() {
        _uiState.value = ImportUiState.Idle
    }
}
