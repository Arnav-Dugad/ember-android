package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.github.arnavdugad.ember.core.util.Connectivity
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
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
    private val connectivity: Connectivity,
) : DiscoveryRepository {
    private val users get() = db.collection("users")

    /** Likes written while offline, whose match attempt waits for the network (uid -> super). */
    private val pendingMatches = ConcurrentHashMap<String, Boolean>()

    override suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile> {
        val uid = auth.requireUid()
        retryPendingMatches(uid)
        val me = users.document(uid).get().await().data?.toProfile(uid) ?: return emptyList()
        val swiped = users.document(uid).collection("swipes").get().await().documents.map { it.id }.toSet()
        val blocked = users.document(uid).collection("blocks").get().await().documents.map { it.id }.toSet()
        // People who blocked me: their profile can still come back from the list query (rules
        // can't filter per document), so their hiddenBy entry drops them here.
        val hiddenBy = runCatching { users.document(uid).collection("hiddenBy").get().await().documents.map { it.id }.toSet() }.getOrDefault(emptySet())
        val hideDemos = runCatching { FirebaseRemoteConfig.getInstance().getBoolean("hide_demo_profiles") }.getOrDefault(false)
        val candidates = users
            .whereEqualTo("onboarded", true)
            .orderBy("lastActiveAt", Query.Direction.DESCENDING)
            .limit(MAX_CANDIDATES)
            .get().await()
            .documents.mapNotNull { doc -> doc.data?.toProfile(doc.id) }
        // A stable per-day shuffle, real people first: the deck feels fresh without jumping around.
        val random = Random(LocalDate.now().toEpochDay() xor uid.hashCode().toLong())
        val origin = filters.browseFrom?.let { it.lat to it.lng } ?: me.lat?.let { lat -> me.lng?.let { lng -> lat to lng } }
        val now = Instant.now()
        return candidates.asSequence()
            .filter { it.uid != uid && it.uid !in swiped && it.uid !in blocked && it.uid !in hiddenBy && !it.paused }
            .filter { !(hideDemos && it.isDemo) }
            .filter { it.age in filters.minAge..filters.maxAge }
            .filter { filters.lookingFor.isEmpty() || it.lookingFor in filters.lookingFor }
            .filter { !afterDark || it.afterDark || it.lookingFor.isAdventurous }
            .filter { !filters.freeTonightOnly || it.isFreeTonight(now) }
            .filter { me.interestedIn.isEmpty() || it.gender in me.interestedIn }
            .filter { it.interestedIn.isEmpty() || me.gender == null || me.gender in it.interestedIn }
            .map { it.withDistanceFrom(origin) }
            .toList()
            .nearFirst(filters.maxDistanceKm, random)
    }

    /**
     * Offline, the batch is committed without waiting: Firestore keeps the pending writes and sends
     * them when the network returns, so a swipe never blocks or fails. The match attempt needs the
     * server (the rules read both likes), so it waits for the next deck load while online.
     * Returns the new match's id when this like completed one.
     */
    override suspend fun swipe(target: Profile, direction: SwipeDirection): String? {
        val uid = auth.requireUid()
        val batch = db.batch()
        batch.set(
            users.document(uid).collection("swipes").document(target.uid),
            mapOf("direction" to direction.key, "createdAt" to FieldValue.serverTimestamp()),
        )
        val like = direction != SwipeDirection.Nope
        if (like) {
            batch.set(
                users.document(target.uid).collection("likedBy").document(uid),
                mapOf("createdAt" to FieldValue.serverTimestamp(), "super" to (direction == SwipeDirection.Super)),
            )
        }
        val commit = batch.commit()
        // Online but slow ("lie-fi") is treated like offline after a few seconds; the write stays queued.
        val confirmed = connectivity.isOnline() && withTimeoutOrNull(COMMIT_TIMEOUT_MS) {
            commit.await()
            true
        } == true
        if (!like) return null
        if (confirmed) return tryMatch(uid, target, direction == SwipeDirection.Super)
        pendingMatches[target.uid] = direction == SwipeDirection.Super
        return null
    }

    private suspend fun retryPendingMatches(uid: String) {
        if (pendingMatches.isEmpty() || !connectivity.isOnline()) return
        pendingMatches.toMap().forEach { (targetUid, superLike) ->
            val ok = runCatching {
                val target = users.document(targetUid).get().await().data?.toProfile(targetUid)
                if (target != null) tryMatch(uid, target, superLike)
            }.isSuccess
            if (ok) pendingMatches.remove(targetUid)
        }
    }

    /**
     * Creates the match if the rules agree it's mutual. "Permission denied" just means they
     * haven't liked back yet; when they do, their client creates the match instead.
     */
    private suspend fun tryMatch(uid: String, target: Profile, superLike: Boolean): String? {
        val me = users.document(uid).get().await().data?.toProfile(uid) ?: return null
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
        val id = pair.joinToString("_")
        return try {
            db.collection("matches").document(id).set(data).await()
            id
        } catch (e: FirebaseFirestoreException) {
            if (e.code != FirebaseFirestoreException.Code.PERMISSION_DENIED) throw e
            null
        }
    }

    override suspend fun rewind(targetUid: String): Boolean {
        val uid = auth.requireUid()
        val batch = db.batch()
        batch.delete(users.document(uid).collection("swipes").document(targetUid))
        batch.delete(users.document(targetUid).collection("likedBy").document(uid))
        return try {
            batch.commit().await()
            pendingMatches.remove(targetUid)
            true
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) false else throw e
        }
    }

    /** `users/{me}/likedBy` (readable by me only), minus anyone blocked in either direction. */
    override fun observeLikesYou(): Flow<List<Admirer>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            val me = users.document(uid)
            combine(
                me.collection("likedBy").snapshots(),
                me.collection("blocks").snapshots(),
                me.collection("hiddenBy").snapshots(),
            ) { likes, blocks, hidden ->
                val excluded = blocks?.documents.orEmpty().map { it.id }.toSet() + hidden?.documents.orEmpty().map { it.id }
                likes?.documents.orEmpty()
                    .filter { it.id !in excluded }
                    .map { Admirer(uid = it.id, superLike = it.getBoolean("super") ?: false, likedAt = it.getTimestamp("createdAt")?.toInstant()) }
                    .sortedByDescending { it.likedAt ?: Instant.EPOCH }
            }
        }
    }

    private fun Profile.snapshot() = mapOf(
        "name" to name,
        "photoUrl" to firstPhoto?.url,
        "blurhash" to firstPhoto?.blurHash,
        "isDemo" to isDemo,
    )

    private companion object {
        const val MAX_CANDIDATES = 300L
        const val COMMIT_TIMEOUT_MS = 8_000L
    }
}

/** Sets [Profile.distanceKm] from [origin] (lat to lng), when both ends have coordinates. */
internal fun Profile.withDistanceFrom(origin: Pair<Double, Double>?): Profile {
    if (origin == null || lat == null || lng == null) return this
    return copy(distanceKm = ceil(Geo.distanceKm(origin.first, origin.second, lat, lng)).toInt().coerceAtLeast(1))
}

/**
 * People within [maxKm] first (a stable shuffle, real people before demos), then everyone further
 * away, nearest first. A small community never shows an empty deck just because nobody is close.
 */
internal fun List<Profile>.nearFirst(maxKm: Int, random: Random): List<Profile> {
    val (near, far) = partition { p -> p.distanceKm == null || maxKm >= 160 || p.distanceKm <= maxKm }
    return near.shuffled(random).sortedBy { it.isDemo } + far.sortedBy { it.distanceKm }
}
