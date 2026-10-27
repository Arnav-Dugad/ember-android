package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.firestore.CollectionReference
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Safety actions, done on the device because the free plan has no Cloud Functions. Each step is
 * one the security rules explicitly allow (participants may delete a match and its thread, the
 * reporter may create a report but never read one back, and so on).
 */
@Singleton
class FirebaseSafetyRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val account: Account,
) : SafetyRepository {
    private val users get() = db.collection("users")

    override suspend fun unmatch(matchId: String) {
        val uid = auth.requireUid()
        val other = matchId.split("_").firstOrNull { it != uid } ?: return
        val match = db.collection("matches").document(matchId)
        deleteAll(match.collection("messages"))
        deleteAll(match.collection("images"))
        runCatching { match.collection("typing").document(uid).delete().await() }
        // Private album access, both directions.
        runCatching { db.collection("albums").document(uid).collection("access").document(other).delete().await() }
        runCatching { db.collection("albums").document(other).collection("access").document(uid).delete().await() }
        match.delete().await()
    }

    /** The block, plus `users/{them}/hiddenBy/{me}` so their deck drops me too (rules need both in one batch). */
    override suspend fun block(uid: String) {
        val me = auth.requireUid()
        val batch = db.batch()
        batch.set(users.document(me).collection("blocks").document(uid), mapOf("createdAt" to FieldValue.serverTimestamp()))
        batch.set(users.document(uid).collection("hiddenBy").document(me), mapOf("createdAt" to FieldValue.serverTimestamp()))
        batch.commit().await()
        val matchId = listOf(me, uid).sorted().joinToString("_")
        if (db.collection("matches").document(matchId).get().await().exists()) unmatch(matchId)
    }

    override suspend fun report(uid: String, reason: ReportReason, details: String, matchId: String?, alsoBlock: Boolean) {
        db.collection("reports").add(
            mapOf(
                "reporterId" to auth.requireUid(),
                "targetId" to uid,
                "reason" to reason.key,
                "details" to details.take(1000),
                "matchId" to matchId,
                "createdAt" to FieldValue.serverTimestamp(),
                "status" to "open",
            ),
        ).await()
        if (alsoBlock) block(uid)
    }

    /**
     * Deletes everything the user owns or created, then the Auth account. Firebase requires a
     * recent sign-in to delete an account; if it's been a while, the user is asked to sign in
     * again (their data is already gone by then, and the next attempt finishes the job).
     */
    override suspend fun deleteAccount() {
        val user = auth.currentUser ?: return
        val uid = user.uid
        val me = users.document(uid)

        // 1. Matches first: deleting a thread needs the user to still be a verified adult.
        db.collection("matches").whereArrayContains("users", uid).get().await().documents.forEach { unmatch(it.id) }

        // 2. The account doc next. Once it's gone, the rules let the owner clear old swipes too.
        account.accountRef(uid).delete().await()
        account.forget()

        // 3. Everything else the user owns or created. Each step stands alone so one failure
        //    can't leave the rest behind; the profile and login go last.
        runCatching {
            me.collection("swipes").get().await().documents.forEach { swipe ->
                if (swipe.getString("direction") != "nope") {
                    runCatching { users.document(swipe.id).collection("likedBy").document(uid).delete().await() }
                }
                runCatching { swipe.reference.delete().await() }
            }
        }
        // The hiddenBy entries this user wrote: the targets are its own blocks.
        runCatching {
            me.collection("blocks").get().await().documents.forEach { block ->
                runCatching { users.document(block.id).collection("hiddenBy").document(uid).delete().await() }
            }
        }
        runCatching { deleteAll(me.collection("blocks")) }
        runCatching { db.collection("voices").document(uid).delete().await() }
        runCatching { deleteAll(db.collection("albums").document(uid).collection("access")) }
        runCatching { deleteAll(db.collection("albums").document(uid).collection("photos")) }
        runCatching {
            val photos = me.get().await().data?.maps("photos").orEmpty()
            photos.mapNotNull { FirestoreImages.pathOf(it.str("url")) }.forEach { path -> runCatching { db.document(path).delete().await() } }
        }
        runCatching { me.collection("matchOnly").document("details").delete().await() }
        me.delete().await()

        try {
            user.delete().await()
        } catch (e: FirebaseAuthRecentLoginRequiredException) {
            auth.signOut()
            throw IllegalStateException("Your data is deleted. Sign in once more and delete again to remove the login itself.")
        }
    }

    private suspend fun deleteAll(collection: CollectionReference) {
        while (true) {
            val page = collection.limit(200).get().await()
            if (page.isEmpty) return
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
    }
}
