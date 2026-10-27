package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Immutable
private class Ember(val x: Float, val speed: Float, val size: Float, val phase: Float, val sway: Float, val color: Int)

/**
 * Embers drifting up from the bottom of the screen. Positions are a pure function of one
 * animated clock, so a frame costs a few dozen draw calls and zero allocations.
 */
@Composable
fun RisingEmbers(modifier: Modifier = Modifier, count: Int = 26, seed: Int = 7) {
    val colors = EmberTheme.colors
    val palette = listOf(colors.accentWarm, colors.gradient[1], colors.accentAlt)
    val embers = remember(count, seed) {
        val r = Random(seed)
        List(count) { Ember(r.nextFloat(), 0.35f + r.nextFloat() * 0.6f, 1.5f + r.nextFloat() * 3.5f, r.nextFloat(), 8f + r.nextFloat() * 26f, r.nextInt(3)) }
    }
    val transition = rememberInfiniteTransition(label = "embers")
    val time by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(14_000, easing = LinearEasing)), label = "time")
    Canvas(modifier) {
        embers.forEach { e ->
            val progress = (time * e.speed * 3f + e.phase) % 1f
            val y = size.height * (1.05f - progress * 1.1f)
            val x = size.width * e.x + sin((progress * 4f + e.phase) * 2f * PI.toFloat()) * e.sway
            val alpha = (sin(progress * PI.toFloat())).coerceIn(0f, 1f) * 0.9f
            val radius = e.size * density
            val c = palette[e.color]
            drawCircle(
                Brush.radialGradient(listOf(c.copy(alpha = alpha * 0.55f), Color.Transparent), center = Offset(x, y), radius = radius * 4f),
                radius = radius * 4f,
                center = Offset(x, y),
            )
            drawCircle(c.copy(alpha = alpha), radius = radius, center = Offset(x, y))
        }
    }
}

@Immutable
private class Spark(val angle: Float, val speed: Float, val size: Float, val life: Float, val color: Int, val streak: Boolean)

/**
 * The match celebration: embers and sparks thrown out from [origin] (fractions of the canvas),
 * arcing under gravity and burning out. Replays whenever [key] changes.
 */
@Composable
fun EmberBurst(key: Any, modifier: Modifier = Modifier, origin: Offset = Offset(0.5f, 0.42f), count: Int = 90) {
    val colors = EmberTheme.colors
    val palette = listOf(colors.accentWarm, colors.gradient[1], colors.accentAlt, Color.White)
    val sparks = remember(key) {
        val r = Random(key.hashCode())
        List(count) {
            Spark(
                angle = r.nextFloat() * 2f * PI.toFloat(),
                speed = 0.35f + r.nextFloat() * 0.9f,
                size = 1.5f + r.nextFloat() * 4f,
                life = 0.55f + r.nextFloat() * 0.45f,
                color = r.nextInt(4),
                streak = r.nextInt(3) == 0,
            )
        }
    }
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(2_200, easing = LinearEasing)) }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f) return@Canvas
        val reach = size.minDimension * 0.75f
        val gravity = size.height * 0.9f
        val o = Offset(size.width * origin.x, size.height * origin.y)
        sparks.forEach { s ->
            val local = (t / s.life).coerceIn(0f, 1f)
            if (local >= 1f) return@forEach
            // Fast start, eased drift: the burst pops, then floats.
            val travel = (1f - (1f - local) * (1f - local)) * s.speed * reach
            val x = o.x + cos(s.angle) * travel
            val y = o.y + sin(s.angle) * travel + gravity * local * local * 0.35f
            val alpha = (1f - local) * (1f - local)
            val c = palette[s.color].copy(alpha = alpha)
            val radius = s.size * density * (1f - local * 0.5f)
            if (s.streak) {
                val tail = Offset(x - cos(s.angle) * radius * 6f, y - sin(s.angle) * radius * 6f)
                drawLine(c, tail, Offset(x, y), strokeWidth = radius * 0.9f, cap = StrokeCap.Round)
            } else {
                drawCircle(
                    Brush.radialGradient(listOf(c.copy(alpha = alpha * 0.5f), Color.Transparent), center = Offset(x, y), radius = radius * 3.5f),
                    radius = radius * 3.5f,
                    center = Offset(x, y),
                )
                drawCircle(c, radius = radius, center = Offset(x, y))
            }
        }
    }
}
