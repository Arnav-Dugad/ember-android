package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

@Immutable
private class Paper(
    val angle: Float,
    val speed: Float,
    val width: Float,
    val height: Float,
    val spin: Float,
    val flip: Float,
    val sway: Float,
    val delay: Float,
    val color: Int,
)

/**
 * A celebration: multi-colour paper rectangles popped up from [origin] (fractions of the canvas),
 * spinning and flipping as they flutter down, with the Ember spark burst on top. Positions are
 * analytic in one clock, like [EmberBurst]. Replays whenever [key] changes.
 */
@Composable
fun ConfettiBurst(key: Any, modifier: Modifier = Modifier, origin: Offset = Offset(0.5f, 0.3f), count: Int = 120) {
    val colors = EmberTheme.colors
    val palette = listOf(colors.accent, colors.accentAlt, colors.accentWarm, colors.superLike, colors.like, Color(0xFFFFD166), Color.White)
    val papers = remember(key) {
        val r = Random(key.hashCode())
        List(count) {
            Paper(
                // Mostly upward, fanning out to both sides.
                angle = (-PI / 2 + (r.nextFloat() - 0.5f) * PI * 1.1f).toFloat(),
                speed = 0.45f + r.nextFloat() * 0.75f,
                width = 5f + r.nextFloat() * 5f,
                height = 9f + r.nextFloat() * 8f,
                spin = (r.nextFloat() - 0.5f) * 900f,
                flip = 3f + r.nextFloat() * 6f,
                sway = 10f + r.nextFloat() * 26f,
                delay = r.nextFloat() * 0.12f,
                color = r.nextInt(palette.size),
            )
        }
    }
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(3_000, easing = LinearEasing)) }
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val t = progress.value
            if (t >= 1f) return@Canvas
            val o = Offset(size.width * origin.x, size.height * origin.y)
            val launch = size.height * 0.55f
            val gravity = size.height * 1.6f
            papers.forEach { p ->
                val local = ((t - p.delay) / (1f - p.delay)).coerceIn(0f, 1f)
                if (local <= 0f || local >= 1f) return@forEach
                // Launched fast, slowed by air, then pulled down: y = v*t - drag + g*t^2.
                val lift = (1f - (1f - local) * (1f - local)) * p.speed * launch
                val x = o.x + cos(p.angle) * lift + sin(local * p.flip * 2f) * p.sway * density
                val y = o.y + sin(p.angle) * lift + gravity * local * local * 0.5f
                val alpha = if (local > 0.75f) (1f - local) / 0.25f else 1f
                val w = p.width * density
                val h = p.height * density
                // Flutter: the paper flips edge-on and back, which reads as 3D.
                val flutter = abs(cos(local * p.flip * PI.toFloat()))
                rotate(p.spin * local, pivot = Offset(x, y)) {
                    scale(scaleX = 0.25f + 0.75f * flutter, scaleY = 1f, pivot = Offset(x, y)) {
                        drawRect(palette[p.color].copy(alpha = alpha), topLeft = Offset(x - w / 2, y - h / 2), size = Size(w, h))
                    }
                }
            }
        }
        EmberBurst(key = key, modifier = Modifier.fillMaxSize(), origin = origin, count = 60)
    }
}
