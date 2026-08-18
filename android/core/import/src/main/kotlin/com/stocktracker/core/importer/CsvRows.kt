package com.stocktracker.core.importer

/**
 * Minimal RFC 4180-aware CSV split — quoted fields, embedded commas, and
 * doubled-quote escaping (`""` inside a quoted field). No external library
 * needed; T212/Degiro exports are plain enough that this covers them.
 */
fun parseCsvRows(text: String): List<List<String>> {
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
            c == ',' -> endField()
            c == '\r' -> { }
            c == '\n' -> endRow()
            else -> field.append(c)
        }
        i++
    }
    if (field.isNotEmpty() || row.isNotEmpty()) endRow()

    return rows.filter { r -> r.isNotEmpty() && !(r.size == 1 && r[0].isEmpty()) }
}
