package com.stocktracker.core.importer

import com.stocktracker.core.calc.RawLot
import com.stocktracker.core.calc.applyFifo
import com.stocktracker.core.model.PositionType

private data class FioTx(
    val date: String,
    val isin: String,
    val name: String,
    val qty: Double,
    val unitPrice: Double,
    val isSell: Boolean,
)

/** Mirrors parseFio in src/utils/pdfParser.ts — Fio banka trade confirmation PDFs. */
suspend fun parseFio(lines: List<String>, lookupIsins: IsinLookup): ParseResult {
    val txs = mutableListOf<FioTx>()
    var skipped = 0

    for (i in lines.indices) {
        val line = lines[i]
        val dm = DATE_RE.find(line) ?: continue
        val isinMatch = ISIN_RE.find(line) ?: continue
        val isin = isinMatch.value

        val afterDate = line.substring(dm.value.length).trim()
        val isinIdx = afterDate.indexOf(isin)
        if (isinIdx < 0) { skipped++; continue }
        val name = afterDate.substring(0, isinIdx).trim()
        val afterIsin = afterDate.substring(isinIdx + isin.length)

        val nums = CZ_NUM.findAll(afterIsin).map { czn(it.value) }.toList()
        if (nums.isEmpty() || nums[0] <= 0) { skipped++; continue } // qty=0 → dividend row, not a trade
        val qty = nums[0]

        val nextLine = lines.getOrNull(i + 1) ?: ""
        val isBuy = nextLine.contains("Nákup")
        val isSell = nextLine.contains("Prodej")
        if (!isBuy && !isSell) { skipped++; continue }

        val priceNums = CZ_NUM.findAll(nextLine).map { czn(it.value) }.toList()
        val unitPrice = priceNums.lastOrNull()
        if (unitPrice == null || unitPrice <= 0) { skipped++; continue }

        val (d, m, y) = dm.groupValues[1].split(".")
        txs.add(FioTx(
            date = "$y-${m.padStart(2, '0')}-${d.padStart(2, '0')}",
            isin = isin,
            name = name,
            qty = qty,
            unitPrice = unitPrice,
            isSell = isSell,
        ))
    }

    if (txs.isEmpty()) return ParseResult(valid = emptyList(), skipped = skipped)

    val isinMap = lookupIsins(txs.map { it.isin }.distinct())

    val rawLots = txs.map { tx ->
        val info = isinMap[tx.isin]
        RawLot(
            ticker = info?.ticker ?: tx.isin,
            name = tx.name.ifEmpty { tx.isin },
            qty = tx.qty,
            price = tx.unitPrice,
            date = tx.date,
            currency = "CZK",
            broker = "Fio banka",
            isin = tx.isin,
            type = info?.type ?: PositionType.STOCK,
            isSell = tx.isSell,
        )
    }

    return ParseResult(valid = applyFifo(rawLots), skipped = skipped)
}
