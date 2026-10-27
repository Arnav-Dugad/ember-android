package io.github.arnavdugad.ember.ui.discover

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** "Remove animations" in the system settings (animator duration scale 0). */
fun reduceMotion(context: Context): Boolean =
    runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

// ---------- Free tonight ----------

/** A glass "🌙 Free tonight" pill with a slow breathing glow behind it. */
@Composable
fun FreeTonightBadge(modifier: Modifier = Modifier, overPhoto: Boolean = true, compact: Boolean = false) {
    val colors = EmberTheme.colors
    val transition = rememberInfiniteTransition(label = "freeTonight")
    val pulse by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_300, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val glow = colors.superLike
    Row(
        modifier
            .graphicsLayer {
                val s = 1f + pulse * 0.04f
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                // A soft halo that swells and fades with the pulse.
                val r = size.height * (0.9f + pulse * 0.5f)
                drawRoundRect(
                    Brush.radialGradient(listOf(glow.copy(alpha = 0.45f * (1f - pulse * 0.6f)), Color.Transparent), center = center, radius = size.width * 0.75f),
                    topLeft = Offset(-r * 0.3f, -r * 0.3f),
                    size = Size(size.width + r * 0.6f, size.height + r * 0.6f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height),
                )
            }
            .glass(PillShape, if (overPhoto) GlassDefaults.overPhoto() else GlassDefaults.surface(), LocalHazeState.current)
            .padding(horizontal = if (compact) 8.dp else 11.dp, vertical = if (compact) 4.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (compact) "🌙 Tonight" else "🌙 Free tonight",
            style = EmberTheme.type.caption,
            color = if (overPhoto) Color.White else colors.textPrimary,
        )
    }
}

// ---------- Heart burst ----------

@Immutable
private class Heart(val spread: Float, val speed: Float, val size: Float, val delay: Float, val wobble: Float, val spin: Float, val color: Int)

/** A unit heart in a 1x1 box, drawn scaled. */
private val HeartPath: Path = Path().apply {
    moveTo(0.5f, 0.92f)
    cubicTo(0.18f, 0.7f, 0f, 0.5f, 0f, 0.3f)
    cubicTo(0f, 0.12f, 0.14f, 0f, 0.28f, 0f)
    cubicTo(0.38f, 0f, 0.46f, 0.06f, 0.5f, 0.16f)
    cubicTo(0.54f, 0.06f, 0.62f, 0f, 0.72f, 0f)
    cubicTo(0.86f, 0f, 1f, 0.12f, 1f, 0.3f)
    cubicTo(1f, 0.5f, 0.82f, 0.7f, 0.5f, 0.92f)
    close()
}

/**
 * Hearts thrown from [from] toward [to] (both in this canvas's coordinates): a fan that
 * decelerates, wobbles and fades as it reaches the card. Positions are analytic in one clock, like
 * the other particle effects, so a frame allocates nothing. Replays whenever [key] changes.
 */
@Composable
fun HeartBurst(key: Long, from: Offset, to: Offset, modifier: Modifier = Modifier, count: Int = 16) {
    val colors = EmberTheme.colors
    val palette = listOf(colors.like, colors.accent, colors.accentAlt, Color.White)
    val hearts = remember(key) {
        val r = Random(key)
        List(count) {
            Heart(
                spread = (r.nextFloat() - 0.5f) * 1.1f,
                speed = 0.55f + r.nextFloat() * 0.6f,
                size = 10f + r.nextFloat() * 14f,
                delay = r.nextFloat() * 0.18f,
                wobble = 6f + r.nextFloat() * 14f,
                spin = (r.nextFloat() - 0.5f) * 60f,
                color = if (it % 5 == 4) 3 else r.nextInt(3),
            )
        }
    }
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(1_000, easing = LinearEasing)) }
    Canvas(modifier) {
        val t = progress.value
        if (t >= 1f || key == 0L) return@Canvas
        val dx = to.x - from.x
        val dy = to.y - from.y
        val distance = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
        val heading = atan2(dy, dx)
        hearts.forEach { h ->
            val local = ((t - h.delay) / (1f - h.delay)).coerceIn(0f, 1f)
            if (local <= 0f || local >= 1f) return@forEach
            val angle = heading + h.spread
            // Fast launch, eased arrival.
            val travel = (1f - (1f - local) * (1f - local) * (1f - local)) * distance * h.speed
            val side = sin(local * PI.toFloat() * 3f + h.spread * 4f) * h.wobble * density
            val x = from.x + cos(angle) * travel - sin(angle) * side
            val y = from.y + sin(angle) * travel + cos(angle) * side - local * 24f * density
            val pop = if (local < 0.15f) local / 0.15f else 1f
            val s = h.size * density * pop * (1f - local * 0.3f)
            val alpha = (1f - local) * (1f - local * 0.4f)
            translate(x - s / 2, y - s / 2) {
                rotate(h.spin * local, pivot = Offset(s / 2, s / 2)) {
                    scale(s, s, pivot = Offset.Zero) {
                        drawPath(HeartPath, palette[h.color].copy(alpha = alpha))
                    }
                }
            }
        }
    }
}

// ---------- Shakes ----------

/** A quick decaying side-to-side shake around 0, in whatever unit [amplitude] is. */
suspend fun Animatable<Float, AnimationVector1D>.shake(amplitude: Float, millis: Int = 360) {
    snapTo(0f)
    animateTo(
        0f,
        keyframes {
            durationMillis = millis
            -amplitude at millis * 12 / 100
            amplitude at millis * 29 / 100
            -amplitude * 0.7f at millis * 46 / 100
            amplitude * 0.45f at millis * 63 / 100
            -amplitude * 0.2f at millis * 80 / 100
        },
    )
}

// ---------- Gyroscope tilt ----------

/** Device tilt in degrees, low-pass filtered and clamped to +/-[DeviceTilt.MAX]. */
@Stable
class DeviceTilt {
    var x by mutableFloatStateOf(0f)
        internal set
    var y by mutableFloatStateOf(0f)
        internal set

    companion object {
        const val MAX = 4f
    }
}

/**
 * Listens to the game rotation vector while the screen is resumed (and unregisters on pause or
 * when it leaves the screen). The neutral pose slowly follows however you hold the phone, so only
 * movement tilts the card. Off entirely when the system says to remove animations.
 */
@Composable
fun rememberDeviceTilt(): DeviceTilt {
    val context = LocalContext.current
    val tilt = remember { DeviceTilt() }
    val enabled = remember(context) { !reduceMotion(context) }
    if (!enabled) return tilt
    LifecycleResumeEffect(tilt) {
        val manager = context.getSystemService(SensorManager::class.java)
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val listener = object : SensorEventListener {
            private val matrix = FloatArray(9)
            private val orientation = FloatArray(3)
            private var basePitch = Float.NaN
            private var baseRoll = Float.NaN

            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(matrix, event.values)
                SensorManager.getOrientation(matrix, orientation)
                val pitch = Math.toDegrees(orientation[1].toDouble()).toFloat()
                val roll = Math.toDegrees(orientation[2].toDouble()).toFloat()
                if (basePitch.isNaN() || abs(roll - baseRoll) > 120f) {
                    basePitch = pitch
                    baseRoll = roll
                }
                basePitch += (pitch - basePitch) * DRIFT
                baseRoll += (roll - baseRoll) * DRIFT
                val targetX = ((basePitch - pitch) * GAIN).coerceIn(-DeviceTilt.MAX, DeviceTilt.MAX)
                val targetY = ((roll - baseRoll) * GAIN).coerceIn(-DeviceTilt.MAX, DeviceTilt.MAX)
                tilt.x += (targetX - tilt.x) * SMOOTHING
                tilt.y += (targetY - tilt.y) * SMOOTHING
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        if (sensor != null) manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME)
        onPauseOrDispose {
            manager?.unregisterListener(listener)
            tilt.x = 0f
            tilt.y = 0f
        }
    }
    return tilt
}

private const val GAIN = 0.3f
private const val SMOOTHING = 0.12f
private const val DRIFT = 0.015f

// ---------- Flame pull-to-refresh ----------

/**
 * The pull-to-refresh indicator: a glass bead that slides down with the pull while the Ember
 * flame inside grows, then stretches taller past the threshold; while refreshing it flickers.
 */
@Composable
fun BoxScope.FlameRefreshIndicator(state: PullToRefreshState, refreshing: Boolean) {
    val fraction = if (refreshing) 1f else state.distanceFraction
    if (fraction <= 0f) return
    val transition = rememberInfiniteTransition(label = "flicker")
    val flicker by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse), label = "flickerT")
    val sway by transition.animateFloat(-1f, 1f, infiniteRepeatable(tween(610, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "sway")
    Box(
        Modifier
            .align(Alignment.TopCenter)
            .graphicsLayer {
                translationY = (fraction.coerceAtMost(1.5f) * 64.dp.toPx()) - 46.dp.toPx()
                alpha = fraction.coerceIn(0f, 1f)
            }
            .size(46.dp)
            .glass(CircleShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(30.dp)
                .background(Brush.radialGradient(listOf(EmberTheme.colors.accentWarm.copy(alpha = 0.35f * fraction.coerceAtMost(1f)), Color.Transparent)), CircleShape),
        )
        EmberFlame(
            size = 28.dp,
            modifier = Modifier.graphicsLayer {
                val grow = 0.45f + 0.55f * fraction.coerceAtMost(1f)
                val stretch = 1f + (fraction - 0.8f).coerceIn(0f, 0.7f) * 0.55f
                transformOrigin = TransformOrigin(0.5f, 0.92f)
                if (refreshing) {
                    scaleX = grow * (0.96f + flicker * 0.06f)
                    scaleY = grow * (0.94f + flicker * 0.16f)
                    rotationZ = sway * 5f
                } else {
                    scaleX = grow / kotlin.math.sqrt(stretch)
                    scaleY = grow * stretch
                    rotationZ = 0f
                }
            },
        )
    }
}
