package com.stocktracker.feature.portfolio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.stocktracker.core.calc.MarketDay
import com.stocktracker.core.calc.MarketPhase
import com.stocktracker.core.calc.MarketWindow
import com.stocktracker.core.calc.ReturnPeriod
import com.stocktracker.core.calc.phaseAt
import com.stocktracker.core.designsystem.Spacing
import com.stocktracker.core.designsystem.StockTrackerColors
import com.stocktracker.core.designsystem.components.AppCard
import com.stocktracker.core.model.InstrumentProfile
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val TimeFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
private val DayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE dd.MM", Locale.ENGLISH)

private val PreOpenColor = Color(0xFFF59E0B)
private val OpenColor = Color(0xFF16A34A)

/**
 * The XTB-style instrument block under the price chart: About, Market hours, Essentials and
 * Rates of return.
 *
 * Every card renders only if it has something to say. Most of the About and Essentials content
 * comes from Yahoo's crumb-gated quoteSummary, which can stop working at any time — when it
 * does, those rows simply are not there, and the market-hours bar, the 52-week range and the
 * return chips carry on from the chart-meta data that always works.
 */
@Composable
internal fun InstrumentInfoSection(
    state: InstrumentInfoUiState,
    isin: String?,
    distributionType: String?,
    modifier: Modifier = Modifier,
) {
    val profile = state.profile ?: return
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        AboutCard(profile)
        MarketHoursCard(state.today, state.nextDays, state.zone)
        EssentialsCard(profile, isin, distributionType)
        RatesOfReturnCard(state.returns)
    }
}

@Composable
private fun InfoCard(title: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(true) }
    AppCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(if (expanded) "▲" else "▼", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (expanded) {
            Column(Modifier.padding(top = Spacing.sm)) { content() }
        }
    }
}

@Composable
private fun AboutCard(profile: InstrumentProfile) {
    val subtitle = listOfNotNull(profile.fundFamily, profile.sector, profile.industry)
        .distinct()
        .joinToString(" · ")
        .takeIf { it.isNotBlank() }
    if (profile.description == null && subtitle == null) return

    InfoCard("About") {
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val description = profile.description
        if (description != null) {
            var showAll by remember { mutableStateOf(false) }
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (showAll) Int.MAX_VALUE else 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            Text(
                if (showAll) "Show less" else "Show more",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .padding(top = Spacing.xs)
                    .clickable { showAll = !showAll },
            )
        }
    }
}

@Composable
private fun MarketHoursCard(today: MarketDay?, nextDays: List<MarketDay>, zone: ZoneId) {
    if (today == null) return
    var showNext by remember { mutableStateOf(false) }
    val now = remember(zone) { LocalTime.now(zone) }
    // Yahoo's currentTradingPeriod is the exchange's most recent session, which on a weekend
    // or before the open is not today — calling it "Today" would be a plain lie on a Saturday.
    val isToday = remember(today, zone) { today.date == LocalDate.now(zone) }

    InfoCard("Market hours") {
        Text(
            (if (isToday) "Today " else "Last session ") + today.date.format(DayFormat),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
        // The "now" marker belongs on the strip only when the strip is today's.
        DayBar(today, marker = now.takeIf { isToday }, modifier = Modifier.padding(top = Spacing.sm))
        Row(
            // Scrolls rather than squeezing: three phase blocks plus their time ranges do not
            // fit a 320dp screen, and a clipped "17:30 - 20:30" is worse than a swipe.
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(top = Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            today.windows.forEach { window ->
                PhaseBlock(window, highlighted = isToday && phaseAt(today, now) == window.phase)
            }
        }
        Text(
            if (showNext) "Hide next days ▲" else "Show next days ▼",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = Spacing.sm).clickable { showNext = !showNext },
        )
        if (showNext) {
            nextDays.forEach { day ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.xs),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(day.date.format(DayFormat), style = MaterialTheme.typography.bodySmall)
                    Text(
                        day.windows.firstOrNull { it.phase == MarketPhase.OPEN }
                            ?.let { "${it.start.format(TimeFormat)} - ${it.end.format(TimeFormat)}" }
                            .orEmpty(),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            // Yahoo publishes no exchange holiday calendar, so a market closed for a national
            // holiday would still be listed here as open. Say so rather than imply certainty.
            Text(
                "Estimated from today's hours; exchange holidays are not accounted for.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
        Text(
            "Hours shown in $zone.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = Spacing.sm),
        )
    }
}

/**
 * A 24-hour strip with the day's windows drawn in place and a dot at the current time.
 *
 * Positions are fractions of the day, so a window that wraps past midnight in the viewer's
 * zone is clamped to the end of the strip instead of drawing backwards.
 */
@Composable
private fun DayBar(day: MarketDay, marker: LocalTime?, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(trackColor)) {
        Row(Modifier.fillMaxWidth()) {
            var cursor = 0f
            day.windows.sortedBy { it.start }.forEach { window ->
                val start = window.start.toSecondOfDay() / 86_400f
                val rawEnd = window.end.toSecondOfDay() / 86_400f
                val end = if (rawEnd <= start) 1f else rawEnd
                if (start > cursor) Box(Modifier.weight(start - cursor).height(10.dp))
                Box(
                    Modifier
                        .weight((end - start).coerceAtLeast(0.001f))
                        .height(10.dp)
                        .background(phaseColor(window.phase)),
                )
                cursor = end
            }
            if (cursor < 1f) Box(Modifier.weight(1f - cursor).height(10.dp))
        }
        if (marker != null) Row(Modifier.fillMaxWidth()) {
            val position = marker.toSecondOfDay() / 86_400f
            if (position > 0f) Box(Modifier.weight(position).height(10.dp))
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurface),
            )
            if (position < 1f) Box(Modifier.weight(1f - position).height(10.dp))
        }
    }
}

private fun phaseColor(phase: MarketPhase): Color = when (phase) {
    MarketPhase.PRE_OPEN -> PreOpenColor
    MarketPhase.OPEN -> OpenColor
    MarketPhase.POST -> PreOpenColor.copy(alpha = 0.55f)
    MarketPhase.CLOSED -> Color.Transparent
}

@Composable
private fun PhaseBlock(window: MarketWindow, highlighted: Boolean) {
    val color = phaseColor(window.phase)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = if (highlighted) 0.35f else 0.15f))
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs),
    ) {
        Text(
            phaseLabel(window.phase),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        Text(
            "${window.start.format(TimeFormat)} - ${window.end.format(TimeFormat)}",
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
        )
    }
}

private fun phaseLabel(phase: MarketPhase) = when (phase) {
    MarketPhase.PRE_OPEN -> "Pre-open"
    MarketPhase.OPEN -> "Open"
    MarketPhase.POST -> "Post"
    MarketPhase.CLOSED -> "Closed"
}

@Composable
private fun EssentialsCard(profile: InstrumentProfile, isin: String?, distributionType: String?) {
    fun range(low: Double?, high: Double?): String? =
        if (low != null && high != null) "${formatMoney(low)} - ${formatMoney(high)}" else null

    // Absent values are omitted rather than shown as a dash: a grid of dashes reads as a
    // broken fetch, while a shorter grid simply reflects what this instrument publishes.
    val cells = listOfNotNull(
        profile.instrumentType?.let { DetailMetric("Asset class", it) },
        profile.category?.let { DetailMetric("Category", it) },
        profile.currency?.let { DetailMetric("Currency", it) },
        profile.exchange?.let { DetailMetric("Exchange", it) },
        isin?.let { DetailMetric("ISIN", it) },
        distributionType?.let { DetailMetric("Distribution", it) },
        range(profile.fiftyTwoWeekLow, profile.fiftyTwoWeekHigh)?.let { DetailMetric("52-week range", it) },
        range(profile.dayLow, profile.dayHigh)?.let { DetailMetric("Day range", it) },
        // Share counts are integers; formatMoney would print "16,090,017.00".
        profile.volume?.let { DetailMetric("Volume", String.format(Locale.US, "%,d", it)) },
        // Two decimals: a 0.15% TER rounds to "0.2%" at the app's usual one decimal, which
        // misstates the headline number funds compete on.
        profile.expenseRatio?.let { DetailMetric("Expense ratio", percent2dp(it * 100)) },
        profile.totalNetAssets?.let { DetailMetric("Net assets", formatMoney(it)) },
        profile.legalType?.let { DetailMetric("Legal type", it) },
    )
    if (cells.isEmpty()) return
    InfoCard("Essentials") {
        DetailMetricGrid(cells = cells, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun RatesOfReturnCard(returns: Map<ReturnPeriod, Double?>) {
    val present = ReturnPeriod.entries.mapNotNull { period -> returns[period]?.let { period to it } }
    if (present.isEmpty()) return
    InfoCard("Rates of return") {
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            present.forEach { (period, value) ->
                val color = if (value < 0) StockTrackerColors.loss else StockTrackerColors.gain
                Column(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = 0.14f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Text(
                        periodLabel(period),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                    Text(
                        signedPercent2dp(value),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = color,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Two decimals, unlike the app's shared [signedPercent], which rounds to one.
 *
 * A 1D move is often a fraction of a percent, where one decimal turns -0.25% into -0.3% and
 * loses the number's whole point. This matches how brokers print the same figure.
 */
private fun signedPercent2dp(value: Double): String {
    val text = percent2dp(value)
    return if (Math.round(value * 100) / 100.0 > 0) "+$text" else text
}

private fun percent2dp(value: Double): String =
    String.format(Locale.US, "%.2f%%", Math.round(value * 100) / 100.0)

private fun periodLabel(period: ReturnPeriod) = when (period) {
    ReturnPeriod.D1 -> "1D"
    ReturnPeriod.W1 -> "1W"
    ReturnPeriod.M1 -> "1M"
    ReturnPeriod.M3 -> "3M"
    ReturnPeriod.Y1 -> "1Y"
}
