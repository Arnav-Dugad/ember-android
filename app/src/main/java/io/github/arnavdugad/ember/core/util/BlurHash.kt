package io.github.arnavdugad.ember.core.util

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.withSign

/**
 * BlurHash (https://blurha.sh): a ~30 character string that decodes to a soft, colorful
 * placeholder. Encoded once at upload, decoded instantly while the real photo streams in.
 */
object BlurHash {
    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#\$%*+,-.:;=?@[]^_{|}~"
    private val cache = LruCache<String, Bitmap>(64)

    fun decode(hash: String?, width: Int = 32, height: Int = 32): Bitmap? {
        if (hash == null || hash.length < 6) return null
        val key = "$hash:$width:$height"
        cache.get(key)?.let { return it }
        return runCatching { decodeInternal(hash, width, height) }.getOrNull()?.also { cache.put(key, it) }
    }

    private fun decodeInternal(hash: String, width: Int, height: Int): Bitmap {
        val sizeFlag = decode83(hash, 0, 1)
        val numY = sizeFlag / 9 + 1
        val numX = sizeFlag % 9 + 1
        require(hash.length == 4 + 2 * numX * numY) { "Invalid blurhash length" }
        val maxAc = (decode83(hash, 1, 2) + 1) / 166f
        val colors = Array(numX * numY) { i ->
            if (i == 0) {
                decodeDc(decode83(hash, 2, 6))
            } else {
                decodeAc(decode83(hash, 4 + i * 2, 6 + i * 2), maxAc)
            }
        }
        val pixels = IntArray(width * height)
        val cosX = FloatArray(width * numX) { cos(PI * (it / numX) * (it % numX) / width).toFloat() }
        val cosY = FloatArray(height * numY) { cos(PI * (it / numY) * (it % numY) / height).toFloat() }
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until numY) {
                    val cy = cosY[y * numY + j]
                    for (i in 0 until numX) {
                        val basis = cosX[x * numX + i] * cy
                        val c = colors[j * numX + i]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                pixels[y * width + x] = (0xFF shl 24) or (linearToSrgb(r) shl 16) or (linearToSrgb(g) shl 8) or linearToSrgb(b)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** Encodes a (small) bitmap. Callers should downscale to ~32-64 px first. */
    fun encode(bitmap: Bitmap, componentsX: Int = 4, componentsY: Int = 5): String {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val factors = Array(componentsX * componentsY) { FloatArray(3) }
        for (j in 0 until componentsY) {
            for (i in 0 until componentsX) {
                val norm = if (i == 0 && j == 0) 1f else 2f
                var r = 0f
                var g = 0f
                var b = 0f
                for (y in 0 until h) {
                    val cy = cos(PI * j * y / h).toFloat()
                    for (x in 0 until w) {
                        val basis = norm * cos(PI * i * x / w).toFloat() * cy
                        val p = pixels[y * w + x]
                        r += basis * srgbToLinear((p shr 16) and 0xFF)
                        g += basis * srgbToLinear((p shr 8) and 0xFF)
                        b += basis * srgbToLinear(p and 0xFF)
                    }
                }
                val scale = 1f / (w * h)
                factors[j * componentsX + i] = floatArrayOf(r * scale, g * scale, b * scale)
            }
        }
        val sb = StringBuilder()
        sb.append(encode83((componentsX - 1) + (componentsY - 1) * 9, 1))
        val ac = factors.drop(1)
        val maxAc: Float
        if (ac.isNotEmpty()) {
            val actualMax = ac.maxOf { f -> f.maxOf { abs(it) } }
            val quantised = (actualMax * 166 - 0.5f).roundToInt().coerceIn(0, 82)
            maxAc = (quantised + 1) / 166f
            sb.append(encode83(quantised, 1))
        } else {
            maxAc = 1f
            sb.append(encode83(0, 1))
        }
        val dc = factors[0]
        sb.append(encode83((linearToSrgb(dc[0]) shl 16) + (linearToSrgb(dc[1]) shl 8) + linearToSrgb(dc[2]), 4))
        for (f in ac) {
            fun q(v: Float) = (signPow(v / maxAc, 0.5f) * 9 + 9.5f).toInt().coerceIn(0, 18)
            sb.append(encode83(q(f[0]) * 19 * 19 + q(f[1]) * 19 + q(f[2]), 2))
        }
        return sb.toString()
    }

    private fun decode83(s: String, from: Int, to: Int): Int {
        var value = 0
        for (k in from until to) value = value * 83 + CHARS.indexOf(s[k]).also { require(it >= 0) }
        return value
    }

    private fun encode83(value: Int, length: Int): String {
        val sb = StringBuilder()
        for (i in 1..length) {
            val digit = (value / 83.0.pow(length - i).toInt()) % 83
            sb.append(CHARS[digit])
        }
        return sb.toString()
    }

    private fun decodeDc(value: Int) = floatArrayOf(
        srgbToLinear(value shr 16),
        srgbToLinear((value shr 8) and 255),
        srgbToLinear(value and 255),
    )

    private fun decodeAc(value: Int, maxAc: Float) = floatArrayOf(
        signPow((value / (19 * 19) - 9) / 9f, 2f) * maxAc,
        signPow(((value / 19) % 19 - 9) / 9f, 2f) * maxAc,
        signPow((value % 19 - 9) / 9f, 2f) * maxAc,
    )

    private fun signPow(v: Float, exp: Float) = abs(v).pow(exp).withSign(sign(v))

    private fun srgbToLinear(value: Int): Float {
        val v = value / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun linearToSrgb(value: Float): Int {
        val v = value.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) (v * 12.92f * 255 + 0.5f).toInt() else ((1.055f * v.pow(1 / 2.4f) - 0.055f) * 255 + 0.5f).toInt()
    }
}
