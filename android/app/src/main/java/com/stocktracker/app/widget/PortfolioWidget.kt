package com.stocktracker.app.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.stocktracker.app.MainActivity
import com.stocktracker.core.data.widget.WidgetSnapshot
import com.stocktracker.core.data.widget.WidgetSnapshotRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun widgetSnapshotRepository(): WidgetSnapshotRepository
}

private val Bg = Color(0xFF16181D)
private val Fg = Color(0xFFE9EBEF)
private val Muted = Color(0xFF8A8F99)
private val Gain = Color(0xFF22C55E)
private val Loss = Color(0xFFEF4444)

/**
 * Home-screen widget: active portfolio value and today's change, as the app last computed
 * them (see WidgetSnapshotRepository for why it never computes on its own), plus their age.
 */
class PortfolioWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val repo = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java).widgetSnapshotRepository()
        val snapshot = repo.snapshot.first()
        provideContent { Content(snapshot) }
    }

    @androidx.compose.runtime.Composable
    private fun Content(s: WidgetSnapshot?) {
        Column(
            GlanceModifier.fillMaxSize().background(Bg).cornerRadius(16.dp).padding(12.dp)
                .clickable(actionStartActivity<MainActivity>()),
        ) {
            if (s == null) {
                Text("Open Stock Tracker to load your portfolio", style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp))
                return@Column
            }
            Text(s.portfolioName, style = TextStyle(color = ColorProvider(Muted), fontSize = 12.sp), maxLines = 1)
            Text(
                String.format(Locale.US, "%,.0f %s", s.totalValue, s.currency),
                style = TextStyle(color = ColorProvider(Fg), fontSize = 20.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            val up = s.dailyChange >= 0
            Text(
                String.format(Locale.US, "%s%,.0f (%s%.2f%%) today", if (up) "+" else "", s.dailyChange, if (up) "+" else "", s.dailyChangePercent),
                style = TextStyle(color = ColorProvider(if (up) Gain else Loss), fontSize = 13.sp),
                maxLines = 1,
            )
            Text(
                "updated " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(s.updatedAtMillis)),
                style = TextStyle(color = ColorProvider(Muted), fontSize = 10.sp),
                modifier = GlanceModifier.padding(top = 4.dp),
            )
        }
    }
}

class PortfolioWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PortfolioWidget()
}
