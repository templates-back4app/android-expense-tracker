package com.back4app.expensetracker.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.back4app.expensetracker.data.CategoryTotal
import com.back4app.expensetracker.domain.Money

/**
 * One bar per category for the selected month, drawn with Compose Canvas (no chart library).
 * Shows at most [maxBars] categories; the rest are summed into "Other".
 */
@Composable
fun CategoryBarChart(totals: List<CategoryTotal>, modifier: Modifier = Modifier, maxBars: Int = 6) {
    val bars = if (totals.size <= maxBars) totals else {
        totals.take(maxBars - 1) + CategoryTotal("Other", totals.drop(maxBars - 1).sumOf { it.totalCents })
    }
    val barColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 11.sp, color = labelColor)

    Canvas(modifier = modifier.fillMaxWidth().height(160.dp)) {
        if (bars.isEmpty()) return@Canvas
        val max = bars.maxOf { it.totalCents }.coerceAtLeast(1L)
        val labelHeight = 16.dp.toPx()
        val valueHeight = 14.dp.toPx()
        val chartHeight = size.height - labelHeight - valueHeight
        val slot = size.width / bars.size
        val barWidth = slot * 0.6f
        bars.forEachIndexed { i, bar ->
            val h = (bar.totalCents.toFloat() / max.toFloat()) * chartHeight
            val left = i * slot + (slot - barWidth) / 2
            val top = valueHeight + (chartHeight - h)
            drawRect(color = barColor, topLeft = Offset(left, top), size = Size(barWidth, h))

            val value = measurer.measure(Money.format(bar.totalCents), labelStyle, maxLines = 1, softWrap = false)
            drawText(value, topLeft = Offset(left + (barWidth - value.size.width) / 2, top - valueHeight))

            val label = measurer.measure(
                bar.category, labelStyle, maxLines = 1, overflow = TextOverflow.Ellipsis, softWrap = false,
                constraints = Constraints(maxWidth = slot.toInt().coerceAtLeast(1)),
            )
            drawText(label, topLeft = Offset(i * slot + (slot - label.size.width) / 2, size.height - labelHeight))
        }
    }
}
