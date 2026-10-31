package com.stocktracker.core.calc

import java.time.LocalDate

/** A real calendar date as exactly `YYYY-MM-DD`, the only form lot dates may be stored in. */
fun isIsoDate(value: String): Boolean = value.length == 10 && runCatching { LocalDate.parse(value) }.isSuccess
