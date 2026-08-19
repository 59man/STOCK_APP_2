package com.stocktracker.core.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import com.stocktracker.core.designsystem.ComponentRadius
import com.stocktracker.core.designsystem.Spacing

/** Shared card shell — consistent corner radius/padding, replacing ad-hoc `Card { Column(Modifier.padding(N.dp)) { ... } }` blocks per screen. */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentPadding: Dp = Spacing.lg,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(ComponentRadius.card),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}
