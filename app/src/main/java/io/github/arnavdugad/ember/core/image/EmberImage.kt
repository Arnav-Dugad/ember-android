package io.github.arnavdugad.ember.core.image

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.palette.graphics.Palette
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Size
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.util.BlurHash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** BlurHash as a painter, decoded once per hash and cached. */
@Composable
fun rememberBlurHashPainter(hash: String?): Painter? = remember(hash) {
    BlurHash.decode(hash)?.let { BitmapPainter(it.asImageBitmap()) }
}

/**
 * Every remote photo in Ember: BlurHash placeholder (or shimmer when there's no hash), then the
 * real image cross-fading in.
 */
@Composable
fun EmberImage(
    url: String?,
    blurHash: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = rememberBlurHashPainter(blurHash)
    if (url == null) {
        Box(modifier.shimmer())
        return
    }
    Box(modifier) {
        if (placeholder != null) {
            Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        AsyncImage(
            model = url,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Dominant color of a photo via the Palette API, used to tint the ambient glow behind the swipe
 * deck. Works on a 96 px software copy so it costs a few milliseconds off the main thread.
 */
@Singleton
class PaletteExtractor @Inject constructor(@ApplicationContext private val context: Context) {
    private val cache = LruCache<String, Color>(128)

    suspend fun dominant(url: String): Color? {
        cache.get(url)?.let { return it }
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(96, 96))
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        return withContext(Dispatchers.Default) {
            val palette = Palette.from(result.image.toBitmap()).maximumColorCount(12).generate()
            val swatch = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return@withContext null
            Color(swatch.rgb)
        }?.also { cache.put(url, it) }
    }
}
