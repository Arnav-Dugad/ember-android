package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.functions.FirebaseFunctions
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The deck is assembled server side (getDiscoveryDeck) so blocks, age, already-swiped and the
 * demo-visibility flag are enforced where the client can't tamper with them. Swipes are plain
 * writes; the onSwipeCreated function decides whether they make a match.
 */
@Singleton
class FirebaseDiscoveryRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val functions: FirebaseFunctions,
) : DiscoveryRepository {
    override suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile> {
        val result = functions.call(
            "getDiscoveryDeck",
            mapOf(
                "maxDistanceKm" to filters.maxDistanceKm,
                "minAge" to filters.minAge,
                "maxAge" to filters.maxAge,
                "lookingFor" to filters.lookingFor.map(LookingFor::key),
                "afterDark" to afterDark,
                "limit" to 30,
            ),
        )
        return result?.maps("profiles").orEmpty().mapNotNull { p ->
            val uid = p.str("uid") ?: return@mapNotNull null
            p.toProfile(uid)
        }
    }

    override suspend fun swipe(target: Profile, direction: SwipeDirection) {
        val uid = auth.requireUid()
        db.collection("users").document(uid).collection("swipes").document(target.uid)
            .set(mapOf("direction" to direction.key, "createdAt" to FieldValue.serverTimestamp()))
            .await()
    }

    override suspend fun rewind(targetUid: String): Boolean =
        functions.call("rewindSwipe", mapOf("targetId" to targetUid))?.bool("ok") ?: false
}
