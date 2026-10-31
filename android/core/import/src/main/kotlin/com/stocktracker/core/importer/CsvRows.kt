package com.stocktracker.core.importer

/**
 * Minimal RFC 4180-aware CSV split — quoted fields, embedded commas, and
 * doubled-quote escaping (`""` inside a quoted field). No external library
 * needed; T212/Degiro exports are plain enough that this covers them. The
 * delimiter is whichever of comma, semicolon or tab the first line uses most,
 * since European exports commonly use a semicolon. A leading BOM is ignored.
 */
fun parseCsvRows(input: String): List<List<String>> {
    val text = input.removePrefix("\uFEFF")
    val firstLine = text.lineSequence().firstOrNull().orEmpty()
    val delimiter = listOf(',', ';', '\t').maxBy { d -> firstLine.count { it == d } }
    val rows = mutableListOf<List<String>>()
    var field = StringBuilder()
    var row = mutableListOf<String>()
    var inQuotes = false
    var i = 0

    fun endField() { row.add(field.toString()); field = StringBuilder() }
    fun endRow() { endField(); rows.add(row); row = mutableListOf() }

    while (i < text.length) {
        val c = text[i]
        when {
            inQuotes -> {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(c)
            }
            c == '"' -> inQuotes = true
            c == delimiter -> endField()
            c == '\r' -> { }
            c == '\n' -> endRow()
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()

    return rows.filter { r -> r.isNotEmpty() && !(r.size == 1 && r[0].isEmpty()) }
}
