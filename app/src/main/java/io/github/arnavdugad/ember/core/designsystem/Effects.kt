package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

/**
 * The living background behind every screen: two large, slowly drifting radial glows over the
 * palette's background color. [tint] (sampled from the current photo) blends in so the room
 * takes on the color of whoever you're looking at.
 */
@Composable
fun AmbientGlow(modifier: Modifier = Modifier, tint: Color? = null, intensity: Float = 1f) {
    val colors = EmberTheme.colors
    val primary by animateColorAsState(tint ?: colors.glow, tween(900), label = "glowTint")
    val secondary = colors.accentAlt
    val transition = rememberInfiniteTransition(label = "ambient")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing)),
        label = "phase",
    )
    val baseAlpha = (if (colors.isDark) 0.42f else 0.26f) * intensity
    Canvas(modifier.fillMaxSize().background(colors.background)) {
        val w = size.width
        val h = size.height
        val c1 = Offset(w * (0.25f + 0.08f * cos(phase)), h * (0.18f + 0.05f * sin(phase)))
        val c2 = Offset(w * (0.85f + 0.06f * sin(phase * 1.3f)), h * (0.72f + 0.06f * cos(phase)))
        drawCircle(
            brush = Brush.radialGradient(listOf(primary.copy(alpha = baseAlpha), Color.Transparent), center = c1, radius = w * 0.95f),
            radius = w * 0.95f,
            center = c1,
        )
        drawCircle(
            brush = Brush.radialGradient(listOf(secondary.copy(alpha = baseAlpha * 0.6f), Color.Transparent), center = c2, radius = w * 0.85f),
            radius = w * 0.85f,
            center = c2,
        )
    }
}

/** Skeleton shimmer: a soft highlight sweeping across the placeholder. */
fun Modifier.shimmer(shape: Shape = EmberShapes.panel): Modifier = composed {
    val colors = EmberTheme.colors
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(tween(1_400, easing = LinearEasing), RepeatMode.Restart),
        label = "shimmerProgress",
    )
    val base = colors.surfaceHigh.copy(alpha = if (colors.isDark) 0.7f else 1f)
    val highlight = if (colors.isDark) Color.White.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.8f)
    background(base, shape).drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val path = Path().apply { addOutline(outline) }
        onDrawWithContent {
            val x = size.width * progress
            drawPath(
                path,
                Brush.linearGradient(
                    listOf(Color.Transparent, highlight, Color.Transparent),
                    start = Offset(x - size.width * 0.5f, 0f),
                    end = Offset(x, size.height),
                ),
            )
            drawContent()
        }
    }
}

private fun Path.addOutline(outline: androidx.compose.ui.graphics.Outline) = when (outline) {
    is androidx.compose.ui.graphics.Outline.Generic -> addPath(outline.path)
    is androidx.compose.ui.graphics.Outline.Rounded -> addRoundRect(outline.roundRect)
    is androidx.compose.ui.graphics.Outline.Rectangle -> addRect(outline.rect)
}

/**
 * Instagram-style photo pagination: one bar per photo, the current one fills over [progress]
 * (0..1) so paging feels like a continuous motion rather than a jump.
 */
@Composable
fun PaginationBars(count: Int, current: Int, modifier: Modifier = Modifier) {
    if (count <= 1) return
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { index ->
            val fill by androidx.compose.animation.core.animateFloatAsState(
                targetValue = if (index <= current) 1f else 0f,
                animationSpec = tween(260),
                label = "bar$index",
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .background(Color.White.copy(alpha = 0.32f), PillShape),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(fill)
                        .height(3.dp)
                        .background(Color.White, PillShape),
                )
            }
        }
    }
}
