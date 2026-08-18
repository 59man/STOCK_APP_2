package com.stocktracker.core.importer

import com.stocktracker.core.model.Position
import com.stocktracker.core.model.PositionType
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

private val LenientJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

@Serializable
private data class ImportedPositionJson(
    val ticker: String,
    val name: String? = null,
    val type: String? = null,
    val quantity: Double,
    val buyPrice: Double,
    val buyDate: String,
    val currency: String? = null,
    val broker: String? = null,
    val isin: String? = null,
    val sellPrice: Double? = null,
    val sellDate: String? = null,
)

private fun mapPositionType(raw: String?): PositionType = when (raw) {
    "etf" -> PositionType.ETF
    "fund" -> PositionType.FUND
    "commodity" -> PositionType.COMMODITY
    else -> PositionType.STOCK
}

/** Structural validity check mirroring `isValidPosition` in importParser.ts. */
private fun isValidCandidate(el: JsonElement): Boolean {
    val obj = el as? JsonObject ?: return false
    val ticker = (obj["ticker"] as? JsonPrimitive)?.contentOrNull
    val quantity = (obj["quantity"] as? JsonPrimitive)?.doubleOrNull
    val buyPrice = (obj["buyPrice"] as? JsonPrimitive)?.doubleOrNull
    val buyDate = (obj["buyDate"] as? JsonPrimitive)?.contentOrNull
    return !ticker.isNullOrBlank() &&
        quantity != null && quantity.isFinite() && quantity > 0 &&
        buyPrice != null && buyPrice.isFinite() && buyPrice >= 0 &&
        !buyDate.isNullOrEmpty()
}

private fun toPosition(el: JsonElement): Position? {
    if (!isValidCandidate(el)) return null
    return try {
        val p = LenientJson.decodeFromJsonElement(ImportedPositionJson.serializer(), el)
        Position(
            id = newId(),
            ticker = p.ticker,
            name = p.name ?: p.ticker,
            type = mapPositionType(p.type),
            quantity = p.quantity,
            buyPrice = p.buyPrice,
            buyDate = p.buyDate,
            currency = p.currency ?: "USD",
            broker = p.broker,
            isin = p.isin,
            sellPrice = p.sellPrice,
            sellDate = p.sellDate,
        )
    } catch (_: Exception) {
        null
    }
}

/**
 * Re-import of a previously exported app JSON file — handles three shapes:
 * `{version:1, positions:[...]}`, a bare `Position[]` array, or the legacy
 * `{stock_tracker_positions: "..."}` / multi-portfolio
 * `{stock_tracker_positions_<uuid>: "..."}` shapes. Mirrors
 * parsePositionsFromJson in src/utils/importParser.ts.
 */
fun parsePositionsFromJson(raw: JsonElement): ParseResult? {
    var candidates: List<JsonElement> = emptyList()
    var dividendTaxOverrides: Map<String, Double>? = null
    var manualPrices: Map<String, ManualPriceImport>? = null

    if (raw is JsonObject) {
        val version = (raw["version"] as? JsonPrimitive)?.longOrNull
        val positions = raw["positions"] as? JsonArray
        if (version == 1L && positions != null) {
            candidates = positions.toList()
            (raw["dividendTaxOverrides"] as? JsonObject)?.let { overrides ->
                dividendTaxOverrides = overrides.mapNotNull { (k, v) ->
                    (v as? JsonPrimitive)?.doubleOrNull?.let { k to it }
                }.toMap()
            }
            (raw["manualPrices"] as? JsonObject)?.let { prices ->
                manualPrices = prices.mapNotNull { (k, v) ->
                    val obj = v as? JsonObject ?: return@mapNotNull null
                    val price = (obj["price"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
                    val updatedAt = (obj["updatedAt"] as? JsonPrimitive)?.contentOrNull ?: return@mapNotNull null
                    k to ManualPriceImport(price, updatedAt)
                }.toMap()
            }
        }
    }

    if (candidates.isEmpty()) {
        when {
            raw is JsonArray -> candidates = raw.toList()
            raw is JsonObject -> {
                val legacy = (raw["stock_tracker_positions"] as? JsonPrimitive)?.contentOrNull
                if (legacy != null) {
                    runCatching { LenientJson.parseToJsonElement(legacy).jsonArray.toList() }
                        .getOrNull()?.let { candidates = it }
                }
                if (candidates.isEmpty()) {
                    val merged = mutableListOf<JsonElement>()
                    for ((key, value) in raw) {
                        if (key.startsWith("stock_tracker_positions_") && value is JsonPrimitive) {
                            runCatching { LenientJson.parseToJsonElement(value.content).jsonArray.toList() }
                                .getOrNull()?.let { merged.addAll(it) }
                        }
                    }
                    if (merged.isNotEmpty()) candidates = merged
                }
            }
        }
    }

    if (candidates.isEmpty()) return null

    val valid = candidates.mapNotNull(::toPosition)
    return ParseResult(
        valid = valid,
        skipped = candidates.size - valid.size,
        dividendTaxOverrides = dividendTaxOverrides,
        manualPrices = manualPrices,
    )
}
