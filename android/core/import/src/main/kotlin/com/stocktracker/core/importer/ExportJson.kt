package com.stocktracker.core.importer

import com.stocktracker.core.model.ManualPriceEntry
import com.stocktracker.core.model.Position
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
private data class ExportPayload(
    val version: Int = 1,
    val positions: List<Position>,
    val dividendTaxOverrides: Map<String, Double>? = null,
    val manualPrices: Map<String, ManualPriceEntry>? = null,
)

// encodeDefaults=true is required — parsePositionsFromJson only recognizes this format when
// "version": 1 is present in the JSON, and version is a default-valued field that would
// otherwise be silently omitted (breaking re-import of the app's own export).
private val ExportJsonFormat = Json { prettyPrint = true; encodeDefaults = true }

/** Mirrors PortfolioTable.tsx's handleExport — the exact shape parsePositionsFromJson reads back on re-import. */
fun buildExportJson(
    positions: List<Position>,
    manualPrices: Map<String, ManualPriceEntry> = emptyMap(),
    dividendTaxOverrides: Map<String, Double> = emptyMap(),
): String {
    val payload = ExportPayload(
        positions = positions,
        dividendTaxOverrides = dividendTaxOverrides.ifEmpty { null },
        manualPrices = manualPrices.ifEmpty { null },
    )
    return ExportJsonFormat.encodeToString(ExportPayload.serializer(), payload)
}
