package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import kotlinx.coroutines.tasks.await
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Discovery without a server. The deck comes from one capped query (the 300 most recently
 * active profiles) and is filtered on the device. Matching is enforced by the security rules: a
 * match document can only be created once both `likedBy` records exist, so a client can't fake one.
 */
@Singleton
class FirebaseDiscoveryRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
) : DiscoveryRepository {
    private val users get() = db.collection("users")

    override suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile> {
        val uid = auth.requireUid()
        val me = users.document(uid).get().await().data?.toProfile(uid) ?: return emptyList()
        val swiped = users.document(uid).collection("swipes").get().await().documents.map { it.id }.toSet()
        val blocked = users.document(uid).collection("blocks").get().await().documents.map { it.id }.toSet()
        val hideDemos = runCatching { FirebaseRemoteConfig.getInstance().getBoolean("hide_demo_profiles") }.getOrDefault(false)
        val candidates = users
            .whereEqualTo("onboarded", true)
            .orderBy("lastActiveAt", Query.Direction.DESCENDING)
            .limit(MAX_CANDIDATES)
            .get().await()
            .documents.mapNotNull { doc -> doc.data?.toProfile(doc.id) }
        // A stable per-day shuffle, real people first: the deck feels fresh without jumping around.
        val random = Random(LocalDate.now().toEpochDay() xor uid.hashCode().toLong())
        return candidates.asSequence()
            .filter { it.uid != uid && it.uid !in swiped && it.uid !in blocked }
            .filter { !(hideDemos && it.isDemo) }
            .filter { it.age in filters.minAge..filters.maxAge }
            .filter { filters.lookingFor.isEmpty() || it.lookingFor in filters.lookingFor }
            .filter { !afterDark || it.afterDark || it.lookingFor.isAdventurous }
            .filter { me.interestedIn.isEmpty() || it.gender in me.interestedIn }
            .filter { it.interestedIn.isEmpty() || me.gender == null || me.gender in it.interestedIn }
            .map { it.withDistanceFrom(me) }
            .toList()
            .nearFirst(filters.maxDistanceKm, random)
    }

    override suspend fun swipe(target: Profile, direction: SwipeDirection) {
        val uid = auth.requireUid()
        val batch = db.batch()
        batch.set(
            users.document(uid).collection("swipes").document(target.uid),
            mapOf("direction" to direction.key, "createdAt" to FieldValue.serverTimestamp()),
        )
        if (direction != SwipeDirection.Nope) {
            batch.set(
                users.document(target.uid).collection("likedBy").document(uid),
                mapOf("createdAt" to FieldValue.serverTimestamp(), "super" to (direction == SwipeDirection.Super)),
            )
        }
        batch.commit().await()
        if (direction != SwipeDirection.Nope) tryMatch(uid, target, direction == SwipeDirection.Super)
    }

    /**
     * Creates the match if the rules agree it's mutual. "Permission denied" just means they
     * haven't liked back yet; when they do, their client creates the match instead.
     */
    private suspend fun tryMatch(uid: String, target: Profile, superLike: Boolean) {
        val me = users.document(uid).get().await().data?.toProfile(uid) ?: return
        val pair = listOf(uid, target.uid).sorted()
        val data = mapOf(
            "users" to pair,
            "profiles" to mapOf(uid to me.snapshot(), target.uid to target.snapshot()),
            "createdAt" to FieldValue.serverTimestamp(),
            "superLike" to superLike,
            "isDemo" to target.isDemo,
            "lastMessage" to null,
            "lastRead" to emptyMap<String, Any>(),
        )
        try {
            db.collection("matches").document(pair.joinToString("_")).set(data).await()
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
        }
    }

    override suspend fun rewind(targetUid: String): Boolean {
        val uid = auth.requireUid()
        val batch = db.batch()
        batch.delete(users.document(uid).collection("swipes").document(targetUid))
        batch.delete(users.document(targetUid).collection("likedBy").document(uid))
        return try {
            batch.commit().await()
            true
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw e
        }
    }

    private fun Profile.snapshot() = mapOf(
        "name" to name,
        "photoUrl" to firstPhoto?.url,
        "blurhash" to firstPhoto?.blurHash,
        "isDemo" to isDemo,
    )

    private fun Profile.withDistanceFrom(me: Profile): Profile {
        if (me.lat == null || me.lng == null || lat == null || lng == null) return this
        return copy(distanceKm = ceil(Geo.distanceKm(me.lat, me.lng, lat, lng)).toInt().coerceAtLeast(1))
    }

    private companion object {
        const val MAX_CANDIDATES = 300L
    }
}

/**
 * People within [maxKm] first (a stable shuffle, real people before demos), then everyone further
 * away, nearest first. A small community never shows an empty deck just because nobody is close.
 */
internal fun List<Profile>.nearFirst(maxKm: Int, random: Random): List<Profile> {
    val (near, far) = partition { p -> p.distanceKm == null || maxKm >= 160 || p.distanceKm <= maxKm }
    return near.shuffled(random).sortedBy { it.isDemo } + far.sortedBy { it.distanceKm }
}
