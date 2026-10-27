package io.github.arnavdugad.ember.core.image

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** A colour found in a photo ([rgb] as 0xRRGGBB, alpha ignored) and how many pixels carry it. */
data class PhotoSwatch(val rgb: Int, val population: Int)

/**
 * Up to [limit] vivid ARGB colours for confetti, from a photo's swatches. Swatches are ranked by
 * saturation and brightness weighted by how much of the photo they cover; greys, near-blacks and
 * hues too close to one already picked are skipped. Picks are lifted to confetti brightness so
 * a moody photo still throws bright paper. Empty when the photo has nothing colourful.
 */
fun pickVividColors(swatches: List<PhotoSwatch>, limit: Int = 3): List<Int> {
    val total = swatches.sumOf { it.population }.coerceAtLeast(1).toFloat()
    val ranked = swatches
        .map { it to hsv(it.rgb) }
        .filter { (_, c) -> c[1] >= MIN_SATURATION && c[2] >= MIN_VALUE }
        .sortedByDescending { (s, c) -> c[1] * (0.4f + 0.6f * c[2]) * sqrt(s.population / total) }
    val picked = ArrayList<FloatArray>(limit)
    for ((_, c) in ranked) {
        if (picked.size == limit) break
        if (picked.none { hueDistance(it[0], c[0]) < MIN_HUE_GAP }) picked += c
    }
    return picked.map { c -> hsvToArgb(c[0], c[1].coerceAtLeast(0.55f), c[2].coerceAtLeast(0.85f)) }
}

private fun hueDistance(a: Float, b: Float): Float {
    val d = abs(a - b) % 360f
    return if (d > 180f) 360f - d else d
}

/** [hue 0..360, saturation 0..1, value 0..1]. */
internal fun hsv(rgb: Int): FloatArray {
    val r = (rgb shr 16 and 0xFF) / 255f
    val g = (rgb shr 8 and 0xFF) / 255f
    val b = (rgb and 0xFF) / 255f
    val hi = max(r, max(g, b))
    val lo = min(r, min(g, b))
    val d = hi - lo
    val h = when {
        d == 0f -> 0f
        hi == r -> 60f * (((g - b) / d) % 6f)
        hi == g -> 60f * ((b - r) / d + 2f)
        else -> 60f * ((r - g) / d + 4f)
    }
    return floatArrayOf((h + 360f) % 360f, if (hi == 0f) 0f else d / hi, hi)
}

internal fun hsvToArgb(h: Float, s: Float, v: Float): Int {
    val c = v * s
    val x = c * (1f - abs((h / 60f) % 2f - 1f))
    val m = v - c
    val (r, g, b) = when ((h / 60f).toInt().coerceIn(0, 5)) {
        0 -> Triple(c, x, 0f)
        1 -> Triple(x, c, 0f)
        2 -> Triple(0f, c, x)
        3 -> Triple(0f, x, c)
        4 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    fun channel(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
    return (0xFF shl 24) or (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
}

/** Alternates two people's colours (theirs first) so both show up in the confetti. */
fun <T> interleave(first: List<T>, second: List<T>): List<T> = buildList {
    for (i in 0 until max(first.size, second.size)) {
        first.getOrNull(i)?.let(::add)
        second.getOrNull(i)?.let(::add)
    }
}

private const val MIN_SATURATION = 0.28f
private const val MIN_VALUE = 0.22f
private const val MIN_HUE_GAP = 28f
