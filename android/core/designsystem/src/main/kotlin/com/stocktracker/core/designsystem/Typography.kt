package com.stocktracker.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight

/** Explicit M3 type scale — was implicit `Typography()` defaults before; now a named object every screen reads from. */
val AppTypography = Typography()

/** CSS font-feature-settings string enabling fixed-width ("tabular") digits. */
private const val TabularNums = "tnum"

/**
 * Tabular-figure variants for price/qty/currency columns — fixed-width digits so aligned
 * numeric columns (position tables, dialogs) don't jitter with proportional digit widths.
 */
object NumericTypography {
    val bodyLarge = AppTypography.bodyLarge.merge(fontFeatureSettings = TabularNums)
    val bodyMedium = AppTypography.bodyMedium.merge(fontFeatureSettings = TabularNums)
    val titleLarge = AppTypography.titleLarge.merge(fontFeatureSettings = TabularNums)
    val titleMedium = AppTypography.titleMedium.merge(fontFeatureSettings = TabularNums)
    val titleSmall = AppTypography.titleSmall.merge(fontFeatureSettings = TabularNums)
    val labelMedium = AppTypography.labelMedium.merge(fontFeatureSettings = TabularNums)

    /** Emphasized numeric style for hero/summary values (e.g. portfolio total). */
    val headlineEmphasis = AppTypography.headlineSmall.merge(
        fontFeatureSettings = TabularNums,
        fontWeight = FontWeight.Bold,
    )
}
