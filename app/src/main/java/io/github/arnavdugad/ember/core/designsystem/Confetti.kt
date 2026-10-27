package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
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
 *
 * The paper starts in brand colours; when [photoColors] arrive (sampled from photos, possibly
 * mid-flight) they cross-fade into most of the slots, see [mixPalette]. Skipped entirely when the
 * system says to remove animations.
 */
@Composable
fun ConfettiBurst(
    key: Any,
    modifier: Modifier = Modifier,
    origin: Offset = Offset(0.5f, 0.3f),
    count: Int = 120,
    photoColors: List<Color> = emptyList(),
) {
    if (rememberReduceMotion()) return
    val colors = EmberTheme.colors
    val brand = listOf(colors.accent, colors.accentAlt, colors.accentWarm, colors.superLike, colors.like, Color(0xFFFFD166), Color.White)
    val target = remember(brand, photoColors) { mixPalette(brand, photoColors) }
    val mix by animateFloatAsState(if (photoColors.isEmpty()) 0f else 1f, tween(450), label = "confettiMix")
    // Re-blended only while the cross-fade runs; a steady frame reuses the list.
    val palette = remember(brand, target, mix) {
        when (mix) {
            0f -> brand
            1f -> target
            else -> List(brand.size) { lerp(brand[it], target[it], mix) }
        }
    }
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
                color = r.nextInt(brand.size),
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

/**
 * Photo colours woven into a brand palette of the same size: the first and last slots keep the
 * brand's accent and finishing colour (white) and every slot between cycles through [photo], so
 * the burst mostly wears the photos with a thread of Ember left in. Returns [brand] unchanged
 * when there are no photo colours.
 */
fun <T> mixPalette(brand: List<T>, photo: List<T>): List<T> {
    if (photo.isEmpty() || brand.size < 3) return brand
    return List(brand.size) { i -> if (i == 0 || i == brand.lastIndex) brand[i] else photo[(i - 1) % photo.size] }
}
