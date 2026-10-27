package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Images
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private albums. Files live under `private/{owner}/` in Storage, whose rules read
 * `albums/{owner}/access/{viewer}` on every request, so approving or revoking takes effect
 * immediately.
 *
 * Download URLs are never used here: they carry their own token and bypass rules, so a revoked
 * viewer could keep one. Bytes are fetched through the authenticated SDK (`getBytes`), which the
 * rules check on every read.
 */
@Singleton
class FirebaseAlbumRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    @ApplicationContext private val context: Context,
) : AlbumRepository {
    private fun albumOf(owner: String) = db.collection("albums").document(owner)

    override fun observeMyAlbum(): Flow<List<AlbumPhoto>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList()) else observePhotos(uid)
    }

    override suspend fun addPhoto(uri: Uri) {
        val uid = auth.requireUid()
        val prepared = Images.prepare(context, uri)
        val id = UUID.randomUUID().toString()
        val path = "private/$uid/$id.jpg"
        storage.reference.child(path)
            .putBytes(prepared.bytes, StorageMetadata.Builder().setContentType("image/jpeg").build())
            .await()
        albumOf(uid).collection("photos").document(id)
            .set(mapOf("path" to path, "createdAt" to FieldValue.serverTimestamp()))
            .await()
        db.collection("users").document(uid).update("privateAlbumCount", FieldValue.increment(1)).await()
    }

    override suspend fun removePhoto(photo: AlbumPhoto) {
        val uid = auth.requireUid()
        try {
            storage.reference.child(photo.path).delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
        albumOf(uid).collection("photos").document(photo.id).delete().await()
        db.collection("users").document(uid).update("privateAlbumCount", FieldValue.increment(-1)).await()
    }

    override fun observeRequests(): Flow<List<AlbumAccessRequest>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            albumOf(uid).collection("access").snapshots().mapLatest { snap ->
                snap?.documents.orEmpty().map { doc ->
                    val viewer = doc.id
                    val profile = runCatching { db.collection("users").document(viewer).get().await().data }.getOrNull()
                    AlbumAccessRequest(
                        viewerId = viewer,
                        viewerName = profile?.str("name") ?: "A match",
                        viewerPhotoUrl = profile?.maps("photos")?.firstOrNull()?.str("url"),
                        status = AccessStatus.of(doc.getString("status")),
                        requestedAt = doc.getTimestamp("requestedAt")?.toInstant(),
                    )
                }.sortedByDescending { it.requestedAt }
            }
        }
    }

    override suspend fun respond(viewerUid: String, approve: Boolean) {
        val uid = auth.requireUid()
        albumOf(uid).collection("access").document(viewerUid)
            .update(mapOf("status" to if (approve) "approved" else "denied", "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    override suspend fun revoke(viewerUid: String) {
        val uid = auth.requireUid()
        albumOf(uid).collection("access").document(viewerUid).delete().await()
    }

    override fun observeAccess(ownerUid: String): Flow<AccessStatus> {
        val uid = auth.requireUid()
        return albumOf(ownerUid).collection("access").document(uid).snapshots().map { snap ->
            if (snap == null || !snap.exists()) AccessStatus.None else AccessStatus.of(snap.getString("status"))
        }
    }

    override suspend fun requestAccess(ownerUid: String) {
        val uid = auth.requireUid()
        albumOf(ownerUid).collection("access").document(uid).set(
            mapOf(
                "ownerId" to ownerUid,
                "viewerId" to uid,
                "status" to "pending",
                "requestedAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
        ).await()
    }

    override suspend fun withdrawAccess(ownerUid: String) {
        val uid = auth.requireUid()
        albumOf(ownerUid).collection("access").document(uid).delete().await()
    }

    override fun observeAlbum(ownerUid: String): Flow<List<AlbumPhoto>> = observePhotos(ownerUid)

    /**
     * Photo metadata, with the image bytes written to an app-private cache file. The cache is
     * cleared whenever the album is closed (see AlbumViewerViewModel), and the URL handed to the
     * UI is a local file, never a shareable link.
     */
    private fun observePhotos(owner: String): Flow<List<AlbumPhoto>> =
        albumOf(owner).collection("photos").orderBy("createdAt", Query.Direction.DESCENDING).snapshots().mapLatest { snap ->
            snap?.documents.orEmpty().mapNotNull { doc ->
                val path = doc.getString("path") ?: return@mapNotNull null
                AlbumPhoto(id = doc.id, path = path, url = cacheLocally(path))
            }
        }

    private suspend fun cacheLocally(path: String): String? = runCatching {
        val dir = java.io.File(context.cacheDir, "private_album").apply { mkdirs() }
        val file = java.io.File(dir, path.replace('/', '_'))
        if (!file.exists()) {
            val bytes = storage.reference.child(path).getBytes(MAX_BYTES).await()
            file.writeBytes(bytes)
        }
        file.toURI().toString()
    }.getOrNull()

    private companion object {
        const val MAX_BYTES = 12L * 1024 * 1024
    }
}
