package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.core.util.Images
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.UnderageException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** The signed-in uid as a Flow (null when signed out). */
internal fun FirebaseAuth.uidFlow(): Flow<String?> = callbackFlow {
    val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser?.uid) }
    addAuthStateListener(listener)
    awaitClose { removeAuthStateListener(listener) }
}

internal fun FirebaseAuth.requireUid(): String = currentUser?.uid ?: error("You're signed out.")

@Singleton
class FirebaseProfileRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    private val functions: FirebaseFunctions,
    @ApplicationContext private val context: Context,
) : ProfileRepository {
    private val users get() = db.collection("users")

    @Volatile private var myLocation: Pair<Double, Double>? = null

    override fun observeMyProfile(): Flow<Profile?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(null)
        } else {
            users.document(uid).snapshots()
                // A cold cache reports "doesn't exist" before the server answers; waiting for the
                // server avoids flashing profile setup at someone who already has a profile.
                .filter { snap -> snap == null || snap.exists() || !snap.metadata.isFromCache }
                .map { snap ->
                snap?.data?.toProfile(uid)?.also { p -> if (p.lat != null && p.lng != null) myLocation = p.lat to p.lng }
            }
        }
    }

    override fun observeMyMatchOnly(): Flow<MatchOnlyDetails?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) flowOf(null) else matchOnlyRef(uid).snapshots().map { it?.data?.toMatchOnly() }
    }

    override suspend fun completeAgeGate(birthDate: LocalDate, termsVersion: String): Int {
        val result = try {
            functions.call("completeAgeGate", mapOf("birthDate" to birthDate.toString(), "termsVersion" to termsVersion))
        } catch (e: FirebaseFunctionsException) {
            if (e.code == FirebaseFunctionsException.Code.FAILED_PRECONDITION && e.message.orEmpty().contains("underage")) {
                runCatching { auth.signOut() }
                throw UnderageException()
            }
            throw e
        }
        // The function granted the `adult` claim; refresh so rules see it immediately.
        auth.currentUser?.getIdToken(true)?.await()
        return result?.int("age") ?: 0
    }

    override suspend fun saveProfile(draft: ProfileDraft, finishOnboarding: Boolean) {
        val uid = auth.requireUid()
        val ref = users.document(uid)
        val existing = ref.get().await()
        val data = draft.toFirestore(uid).toMutableMap()
        data["updatedAt"] = FieldValue.serverTimestamp()
        data["lastActiveAt"] = FieldValue.serverTimestamp()
        if (!existing.contains("createdAt")) data["createdAt"] = FieldValue.serverTimestamp()
        if (finishOnboarding) data["onboarded"] = true
        ref.set(data, SetOptions.merge()).await()
        matchOnlyRef(uid).set(
            mapOf("boundaries" to draft.matchOnly.boundaries, "turnOns" to draft.matchOnly.turnOns),
        ).await()
        if (draft.lat != null && draft.lng != null) myLocation = draft.lat to draft.lng
    }

    override suspend fun uploadPhoto(uri: Uri): Photo {
        val uid = auth.requireUid()
        val prepared = Images.prepare(context, uri)
        val id = UUID.randomUUID().toString()
        val path = "users/$uid/photos/$id.jpg"
        val ref = storage.reference.child(path)
        ref.putBytes(prepared.bytes, StorageMetadata.Builder().setContentType("image/jpeg").build()).await()
        return Photo(id = id, url = ref.downloadUrl.await().toString(), path = path, blurHash = prepared.blurHash)
    }

    override suspend fun deletePhoto(photo: Photo) {
        if (photo.path.isBlank()) return
        try {
            storage.reference.child(photo.path).delete().await()
        } catch (e: StorageException) {
            if (e.errorCode != StorageException.ERROR_OBJECT_NOT_FOUND) throw e
        }
    }

    override suspend fun getProfile(uid: String): Profile? {
        val profile = users.document(uid).get().await().data?.toProfile(uid) ?: return null
        val here = myLocation
        return if (profile.distanceKm == null && here != null && profile.lat != null && profile.lng != null) {
            val km = Geo.distanceKm(here.first, here.second, profile.lat, profile.lng)
            profile.copy(distanceKm = kotlin.math.ceil(km).toInt().coerceAtLeast(1))
        } else {
            profile
        }
    }

    override suspend fun getMatchOnly(uid: String): MatchOnlyDetails? =
        runCatching { matchOnlyRef(uid).get().await().data?.toMatchOnly() }.getOrNull()

    override suspend fun registerPushToken(token: String) {
        val uid = auth.currentUser?.uid ?: return
        users.document(uid).collection("tokens").document(token)
            .set(mapOf("token" to token, "platform" to "android", "updatedAt" to FieldValue.serverTimestamp()))
            .await()
    }

    override suspend fun touchLastActive() {
        val uid = auth.currentUser?.uid ?: return
        runCatching { users.document(uid).update("lastActiveAt", FieldValue.serverTimestamp()).await() }
    }

    private fun matchOnlyRef(uid: String) = users.document(uid).collection("matchOnly").document("details")
}
