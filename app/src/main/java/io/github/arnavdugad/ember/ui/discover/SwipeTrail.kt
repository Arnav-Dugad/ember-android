package io.github.arnavdugad.ember.ui.discover

import android.graphics.BlurMaskFilter
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.channels.Channel

/**
 * The last few card positions for the swipe trail, in fixed arrays used as a ring, so recording
 * and drawing never allocate. Points closer than [spacingMs] to the newest are skipped, which
 * keeps [capacity] points spread over the whole fade. Index 0 is the oldest point.
 */
class TrailBuffer(val capacity: Int, private val spacingMs: Long = 0L) {
    private val xs = FloatArray(capacity)
    private val ys = FloatArray(capacity)
    private val times = LongArray(capacity)
    private val tints = IntArray(capacity)
    private val weights = FloatArray(capacity)
    private val angles = FloatArray(capacity)
    private var head = 0

    var size = 0
        private set

    /** Records a point, overwriting the oldest when full. False when it came too soon after the last. */
    fun add(x: Float, y: Float, time: Long, tint: Int, weight: Float, angle: Float = 0f): Boolean {
        if (size > 0 && time - times[slot(size - 1)] < spacingMs) return false
        val i: Int
        if (size < capacity) {
            i = slot(size)
            size++
        } else {
            i = head
            head = (head + 1) % capacity
        }
        xs[i] = x
        ys[i] = y
        times[i] = time
        tints[i] = tint
        weights[i] = weight
        angles[i] = angle
        return true
    }

    /** Drops every point at least [life] ms old at [now]. */
    fun prune(now: Long, life: Long) {
        while (size > 0 && now - times[head] >= life) {
            head = (head + 1) % capacity
            size--
        }
    }

    fun clear() {
        head = 0
        size = 0
    }

    fun x(index: Int): Float = xs[slot(index)]
    fun y(index: Int): Float = ys[slot(index)]
    fun time(index: Int): Long = times[slot(index)]
    fun tint(index: Int): Int = tints[slot(index)]
    fun weight(index: Int): Float = weights[slot(index)]
    fun angle(index: Int): Float = angles[slot(index)]

    private fun slot(index: Int) = (head + index) % capacity
}

/** A trail point's opacity at [age] ms: full when fresh, easing out to nothing at [life]. */
fun trailFade(age: Long, life: Long): Float {
    val t = (1f - age.toFloat() / life).coerceIn(0f, 1f)
    return t * t
}

/** Swipe trail tints, indexing [Modifier.swipeTrail]'s colours. */
const val TRAIL_LIKE = 0
const val TRAIL_NOPE = 1
const val TRAIL_SUPER = 2

/** Recent poses of the dragged card (centre offset from rest, rotation), fading over [TRAIL_LIFE_MS]. */
@Stable
class SwipeTrail {
    internal val points = TrailBuffer(TRAIL_POINTS, TRAIL_LIFE_MS / TRAIL_POINTS)
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** The trail's clock: drawing reads it, so each new point or fading frame redraws. */
    internal var now by mutableLongStateOf(0L)
        private set

    fun record(x: Float, y: Float, angle: Float, tint: Int, weight: Float) {
        val time = SystemClock.uptimeMillis()
        if (points.add(x, y, time, tint, weight, angle)) {
            now = time
            wake.trySend(Unit)
        }
    }

    /** Ticks the clock every frame while any point is still fading, then sleeps until the next one. */
    internal suspend fun run() {
        while (true) {
            wake.receive()
            while (points.size > 0) {
                withFrameMillis { }
                now = SystemClock.uptimeMillis()
                points.prune(now, TRAIL_LIFE_MS)
            }
        }
    }
}

@Composable
fun rememberSwipeTrail(): SwipeTrail {
    val trail = remember { SwipeTrail() }
    LaunchedEffect(trail) { trail.run() }
    return trail
}

/**
 * Draws [trail] behind this card: a blurred card-shaped glow at each recent position, tinted by
 * where the drag was heading ([tints], by TRAIL_* index) and fading out. The glow is rendered
 * once into a small bitmap and stamped, so a frame is a handful of image draws.
 */
fun Modifier.swipeTrail(trail: SwipeTrail, tints: List<Color>, corner: Dp): Modifier = drawWithCache {
    val w = size.width / SPRITE_SCALE
    val h = size.height / SPRITE_SCALE
    val blur = 22.dp.toPx() / SPRITE_SCALE
    val sprite = ImageBitmap((w + blur * 4).toInt().coerceAtLeast(1), (h + blur * 4).toInt().coerceAtLeast(1))
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.WHITE
        maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
    }
    val r = corner.toPx() / SPRITE_SCALE
    Canvas(sprite).nativeCanvas.drawRoundRect(blur * 2, blur * 2, blur * 2 + w, blur * 2 + h, r, r, paint)
    val filters = tints.map { ColorFilter.tint(it) }
    val stamp = IntSize(sprite.width * SPRITE_SCALE, sprite.height * SPRITE_SCALE)
    onDrawBehind {
        val now = trail.now
        val points = trail.points
        for (i in 0 until points.size) {
            val alpha = trailFade(now - points.time(i), TRAIL_LIFE_MS) * points.weight(i) * 0.7f
            if (alpha <= 0.01f) continue
            val cx = size.width / 2f + points.x(i)
            val cy = size.height / 2f + points.y(i)
            rotate(points.angle(i), pivot = Offset(cx, cy)) {
                drawImage(
                    sprite,
                    dstOffset = IntOffset((cx - stamp.width / 2f).toInt(), (cy - stamp.height / 2f).toInt()),
                    dstSize = stamp,
                    alpha = alpha,
                    colorFilter = filters[points.tint(i)],
                )
            }
        }
    }
}

private const val TRAIL_POINTS = 16
private const val TRAIL_LIFE_MS = 400L
private const val SPRITE_SCALE = 4
