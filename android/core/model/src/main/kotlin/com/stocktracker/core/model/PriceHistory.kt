package com.stocktracker.core.model

/** (ISO date, price) points, ascending by date. Mirrors `TickerHistory = [string, number][]` in the web app's chart components. */
typealias PriceHistory = List<Pair<String, Double>>
