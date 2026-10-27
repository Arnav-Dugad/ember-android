package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.roundToInt

/** The active segment's extent in page units: it covers pages [start] through [end]. */
@Immutable
data class LiquidSpan(val start: Float, val end: Float) {
    /** 0 when resting on a page, 1 when stretched across two. */
    val stretch: Float get() = end - start
}

/**
 * Where the liquid segment sits for a pager [position] (page + offset fraction). The leading
 * edge races to the next page over the first half of the move and the trailing edge catches up
 * over the second, so the segment stretches out and then snaps back to one page. Positions past
 * either end are clamped.
 */
fun liquidSpan(position: Float, count: Int): LiquidSpan {
    if (count <= 1) return LiquidSpan(0f, 0f)
    val p = position.coerceIn(0f, (count - 1).toFloat())
    val base = floor(p)
    val f = p - base
    return LiquidSpan(start = base + (2f * f - 1f).coerceAtLeast(0f), end = base + (2f * f).coerceAtMost(1f))
}

/**
 * Photo pagination as one bar per photo with a liquid highlight: while paging, the highlight
 * stretches toward the next photo and thins as it does, then springs onto it. [position] is read
 * at draw time (page + offset fraction, or an animated index), so dragging never recomposes.
 */
@Composable
fun LiquidPageIndicator(count: Int, position: () -> Float, modifier: Modifier = Modifier) {
    if (count <= 1) return
    val discrete = rememberReduceMotion()
    Canvas(modifier.fillMaxWidth().height(3.dp)) {
        val gap = 4.dp.toPx()
        val slot = (size.width + gap) / count
        val bar = slot - gap
        val h = size.height
        val radius = CornerRadius(h / 2f)
        repeat(count) { i ->
            drawRoundRect(Color.White.copy(alpha = 0.32f), topLeft = Offset(i * slot, 0f), size = Size(bar, h), cornerRadius = radius)
        }
        val raw = position()
        val span = if (discrete) liquidSpan(raw.roundToInt().toFloat(), count) else liquidSpan(raw, count)
        // Stretching thins the bead a little, the way a drop of liquid would.
        val thickness = h * (1f - 0.3f * span.stretch)
        drawRoundRect(
            Color.White,
            topLeft = Offset(span.start * slot, (h - thickness) / 2f),
            size = Size(span.end * slot + bar - span.start * slot, thickness),
            cornerRadius = CornerRadius(thickness / 2f),
        )
    }
}
