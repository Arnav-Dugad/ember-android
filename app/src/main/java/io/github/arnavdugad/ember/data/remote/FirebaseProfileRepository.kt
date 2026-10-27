package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Ages
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
    private val account: Account,
    @ApplicationContext private val context: Context,
) : ProfileRepository {
    private val users get() = db.collection("users")

    @Volatile private var myLocation: Pair<Double, Double>? = null

    override fun observeMyProfile(): Flow<Profile?> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(null)
        } else {
            users.document(uid).snapshots(includeMetadata = true)
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

    /**
     * The age gate. Rules only accept the account document for a real birth date at least 18
     * years ago, so this cannot be bypassed by a modified client. Under 18, the Auth user is
     * deleted on the spot.
     */
    override suspend fun completeAgeGate(birthDate: LocalDate, termsVersion: String): Int {
        val user = auth.currentUser ?: error("You're signed out.")
        val age = Ages.ageOn(birthDate, LocalDate.now())
        if (age < Ages.MINIMUM) {
            runCatching { user.delete().await() }
            auth.signOut()
            throw UnderageException()
        }
        require(age <= Ages.MAXIMUM) { "Please check your date of birth." }
        val batch = db.batch()
        batch.set(
            account.accountRef(user.uid),
            mapOf(
                "birthYear" to birthDate.year,
                "birthMonth" to birthDate.monthValue,
                "birthDay" to birthDate.dayOfMonth,
                "termsVersion" to termsVersion,
                "termsAcceptedAt" to FieldValue.serverTimestamp(),
            ),
        )
        batch.set(
            users.document(user.uid),
            mapOf(
                "uid" to user.uid,
                "age" to age,
                "onboarded" to false,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp(),
            ),
            SetOptions.merge(),
        )
        batch.commit().await()
        account.remember(user.uid, birthDate)
        return age
    }

    override suspend fun saveProfile(draft: ProfileDraft, finishOnboarding: Boolean) {
        val uid = auth.requireUid()
        val data = draft.toFirestore(uid).toMutableMap()
        data["lastActiveAt"] = FieldValue.serverTimestamp()
        if (finishOnboarding) data["onboarded"] = true
        users.document(uid).set(account.profileFields(data), SetOptions.merge()).await()
        matchOnlyRef(uid).set(mapOf("boundaries" to draft.matchOnly.boundaries, "turnOns" to draft.matchOnly.turnOns)).await()
        if (draft.lat != null && draft.lng != null) myLocation = draft.lat to draft.lng
    }

    override suspend fun uploadPhoto(uri: Uri): Photo {
        val uid = auth.requireUid()
        val image = FirestoreImages.prepare(context, uri)
        val id = UUID.randomUUID().toString()
        val ref = db.collection("images").document(id)
        FirestoreImages.write(ref, image, mapOf("owner" to uid))
        return Photo(id = id, url = FirestoreImages.url(ref.path), path = ref.path, blurHash = image.blurHash)
    }

    override suspend fun deletePhoto(photo: Photo) {
        val path = FirestoreImages.pathOf(photo.url) ?: return
        runCatching { db.document(path).delete().await() }
    }

    override suspend fun getProfile(uid: String): Profile? {
        val profile = users.document(uid).get().await().data?.toProfile(uid) ?: return null
        val here = myLocation
        return if (here != null && profile.lat != null && profile.lng != null) {
            val km = Geo.distanceKm(here.first, here.second, profile.lat, profile.lng)
            profile.copy(distanceKm = kotlin.math.ceil(km).toInt().coerceAtLeast(1))
        } else {
            profile
        }
    }

    override suspend fun getMatchOnly(uid: String): MatchOnlyDetails? =
        runCatching { matchOnlyRef(uid).get().await().data?.toMatchOnly() }.getOrNull()

    /** Marks the user active and keeps their shown age current (it is checked by the rules). */
    override suspend fun touchLastActive() {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            users.document(uid).update(account.profileFields(mapOf("lastActiveAt" to FieldValue.serverTimestamp()))).await()
        }
    }

    private fun matchOnlyRef(uid: String) = users.document(uid).collection("matchOnly").document("details")
}
