package com.stocktracker.core.designsystem

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Spacing scale — replaces ad-hoc padding/gap values scattered per call site. */
object Spacing {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
}

/** Corner-radius scale, mirrored into [StockTrackerShapes]'s M3 slots. */
object Radius {
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
}

/** Named per-component radii — read these instead of [Radius] directly so a component's shape can change independently of the scale. */
object ComponentRadius {
    val card: Dp = Radius.lg
    val chip: Dp = Radius.xl
    val button: Dp = Radius.md
    val dialog: Dp = Radius.xl
}
