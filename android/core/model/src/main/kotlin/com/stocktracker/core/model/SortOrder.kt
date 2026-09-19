package com.stocktracker.core.model

/** Sortable columns of the position list. Mirrors the web table's sortable headers. */
enum class SortField { NAME, TYPE, VALUE, TODAY, TOTAL_RETURN }

/** Default matches what the list showed before sorting existed: largest position first. */
data class SortOrder(
    val field: SortField = SortField.VALUE,
    val ascending: Boolean = false,
)
