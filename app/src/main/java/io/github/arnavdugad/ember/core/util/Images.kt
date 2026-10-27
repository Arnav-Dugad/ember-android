package io.github.arnavdugad.ember.core.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/** A photo ready to upload: re-encoded JPEG (so EXIF, including GPS, is stripped) and its blurhash. */
class PreparedImage(val bytes: ByteArray, val blurHash: String, val width: Int, val height: Int)

object Images {
    /**
     * Decodes [uri], applies the EXIF rotation, scales the long edge to [maxEdge] and re-encodes
     * as JPEG. Re-encoding drops every metadata tag, which is the point: a photo's GPS location
     * must never reach other users.
     */
    suspend fun prepare(context: Context, uri: Uri, maxEdge: Int = 1600, quality: Int = 86): PreparedImage = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "That file isn't an image we can read." }
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("Couldn't open the image.")
        val rotation = resolver.openInputStream(uri)?.use { stream ->
            when (ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        val matrix = Matrix().apply {
            postRotate(rotation)
            postScale(scale, scale)
        }
        val bitmap = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        val thumb = Bitmap.createScaledBitmap(bitmap, 32, (32f * bitmap.height / bitmap.width).toInt().coerceAtLeast(1), true)
        val hash = BlurHash.encode(thumb)
        PreparedImage(out.toByteArray(), hash, bitmap.width, bitmap.height)
    }
}
