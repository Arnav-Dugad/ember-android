package io.github.arnavdugad.ember.data.remote

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import javax.inject.Inject
import javax.inject.Singleton

/** Every safety action runs server side: the client can ask, never decide. */
@Singleton
class FirebaseSafetyRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val functions: FirebaseFunctions,
) : SafetyRepository {
    override suspend fun unmatch(matchId: String) {
        functions.call("unmatch", mapOf("matchId" to matchId))
    }

    override suspend fun block(uid: String) {
        functions.call("blockUser", mapOf("targetId" to uid))
    }

    override suspend fun report(uid: String, reason: ReportReason, details: String, matchId: String?, alsoBlock: Boolean) {
        functions.call(
            "reportUser",
            mapOf("targetId" to uid, "reason" to reason.key, "details" to details.take(1000), "matchId" to matchId, "block" to alsoBlock),
        )
    }

    override suspend fun deleteAccount() {
        functions.call("deleteAccount")
        auth.signOut()
    }
}
