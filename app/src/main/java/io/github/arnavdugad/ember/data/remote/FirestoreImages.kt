package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.arnavdugad.ember.core.util.Images
import io.github.arnavdugad.ember.core.util.PreparedImage
import kotlinx.coroutines.tasks.await
import okio.Buffer

/**
 * Images live in Firestore documents (`{ data: bytes, ... }`) because Cloud Storage needs the paid
 * Blaze plan. They are addressed as `ember-fs://<document path>`, and the same security rules
 * that protect a private album or a chat protect its photos.
 */
object FirestoreImages {
    const val SCHEME = "ember-fs"

    /** Firestore caps a document at 1 MiB; the rules allow 900 KB of image data. */
    private const val MAX_BYTES = 850 * 1024

    fun url(documentPath: String) = "$SCHEME://$documentPath"

    fun pathOf(url: String?): String? = url?.takeIf { it.startsWith("$SCHEME://") }?.removePrefix("$SCHEME://")

    /** Compresses until it fits a Firestore document, stepping down size and quality. */
    suspend fun prepare(context: Context, uri: Uri): PreparedImage {
        for ((edge, quality) in listOf(1280 to 82, 1080 to 76, 900 to 70, 720 to 64)) {
            val image = Images.prepare(context, uri, maxEdge = edge, quality = quality)
            if (image.bytes.size <= MAX_BYTES) return image
        }
        error("That photo is too large to upload.")
    }

    suspend fun write(ref: DocumentReference, image: PreparedImage, extra: Map<String, Any?> = emptyMap()) {
        ref.set(mapOf("data" to Blob.fromBytes(image.bytes), "createdAt" to FieldValue.serverTimestamp()) + extra).await()
    }
}

/** Coil fetcher for `ember-fs://` URLs. Firestore's own offline cache doubles as the disk cache. */
class FirestoreImageFetcher(private val path: String, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val snapshot = FirebaseFirestore.getInstance().document(path).get().await()
        val bytes = snapshot.getBlob("data")?.toBytes() ?: error("Image $path is missing")
        return SourceFetchResult(
            source = ImageSource(Buffer().write(bytes), options.fileSystem),
            mimeType = "image/jpeg",
            dataSource = if (snapshot.metadata.isFromCache) DataSource.DISK else DataSource.NETWORK,
        )
    }

    class Factory : Fetcher.Factory<coil3.Uri> {
        override fun create(data: coil3.Uri, options: Options, imageLoader: ImageLoader): Fetcher? {
            if (data.scheme != FirestoreImages.SCHEME) return null
            val path = listOfNotNull(data.authority, data.path?.trimStart('/')).joinToString("/")
            return FirestoreImageFetcher(path, options)
        }
    }
}
