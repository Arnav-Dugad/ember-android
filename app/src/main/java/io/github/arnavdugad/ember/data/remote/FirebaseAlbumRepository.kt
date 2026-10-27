package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.qualifiers.ApplicationContext
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Private albums. Each photo is a document under `albums/{owner}/photos`, and the rules read
 * `albums/{owner}/access/{viewer}` on every read, so approving or revoking takes effect at once
 * and no shareable link ever exists.
 */
@Singleton
class FirebaseAlbumRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val account: Account,
    @ApplicationContext private val context: Context,
) : AlbumRepository {
    private fun albumOf(owner: String) = db.collection("albums").document(owner)

    override fun observeMyAlbum(): Flow<List<AlbumPhoto>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(emptyList()) else observePhotos(uid)
    }

    override suspend fun addPhoto(uri: Uri) {
        val uid = auth.requireUid()
        val image = FirestoreImages.prepare(context, uri)
        FirestoreImages.write(albumOf(uid).collection("photos").document(), image)
        updateCount(uid)
    }

    override suspend fun removePhoto(photo: AlbumPhoto) {
        val uid = auth.requireUid()
        albumOf(uid).collection("photos").document(photo.id).delete().await()
        updateCount(uid)
    }

    private suspend fun updateCount(uid: String) {
        val count = albumOf(uid).collection("photos").get().await().size()
        db.collection("users").document(uid).update(account.profileFields(mapOf("privateAlbumCount" to count))).await()
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

    /** Photo ids only; the bytes load through the `ember-fs://` image fetcher, checked by rules. */
    private fun observePhotos(owner: String): Flow<List<AlbumPhoto>> =
        albumOf(owner).collection("photos").orderBy("createdAt", Query.Direction.DESCENDING).snapshots().map { snap ->
            snap?.documents.orEmpty().map { doc ->
                val path = doc.reference.path
                AlbumPhoto(id = doc.id, path = path, url = FirestoreImages.url(path))
            }
        }
}
