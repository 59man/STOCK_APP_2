package com.stocktracker.feature.portfolio

import com.stocktracker.core.model.Portfolio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ResolveActivePortfolioTest {
    private val a = Portfolio("a", "A")
    private val b = Portfolio("b", "B")

    @Test
    fun `keeps the selection while it exists`() = assertEquals("b", resolveActivePortfolio("b", listOf(a, b)))

    @Test
    fun `moves off a portfolio deleted elsewhere`() = assertEquals("a", resolveActivePortfolio("gone", listOf(a, b)))

    @Test
    fun `picks the first when nothing is selected`() = assertEquals("a", resolveActivePortfolio(null, listOf(a, b)))

    @Test
    fun `has no answer before any list has loaded`() = assertNull(resolveActivePortfolio("a", emptyList()))
}
