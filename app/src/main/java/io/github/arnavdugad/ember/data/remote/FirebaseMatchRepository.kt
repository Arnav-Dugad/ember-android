package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.LastMessage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.repo.MatchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseMatchRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    @ApplicationContext private val context: Context,
) : MatchRepository {
    private val matches get() = db.collection("matches")

    override fun observeMatches(): Flow<List<Match>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            // Sorted on the client: an array-contains query with orderBy would need a composite index
            // on a field (lastMessage.sentAt) that is missing on brand-new matches.
            matches.whereArrayContains("users", uid).snapshots().map { snap ->
                snap?.documents.orEmpty()
                    .mapNotNull { it.toMatch(uid) }
                    .sortedByDescending { it.lastMessage?.sentAt ?: it.createdAt ?: Instant.EPOCH }
            }
        }
    }

    override fun observeMatch(matchId: String): Flow<Match?> {
        val uid = auth.requireUid()
        return matches.document(matchId).snapshots().map { it?.toMatch(uid) }
    }

    override fun observeMessages(matchId: String): Flow<List<Message>> =
        matches.document(matchId).collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(300)
            .snapshots()
            .map { snap -> snap?.documents.orEmpty().map { it.toMessage(matchId) } }

    override fun observeTyping(matchId: String, otherUid: String): Flow<Boolean> =
        matches.document(matchId).collection("typing").document(otherUid).snapshots().map { snap ->
            val data = snap?.data ?: return@map false
            val updated = data.instant("updatedAt") ?: return@map false
            // A stale "typing" flag (app killed mid-sentence) expires on its own.
            data.bool("typing") && updated.isAfter(Instant.now().minusSeconds(TYPING_TTL_SECONDS))
        }

    override suspend fun setTyping(matchId: String, typing: Boolean) {
        val uid = auth.currentUser?.uid ?: return
        runCatching {
            matches.document(matchId).collection("typing").document(uid)
                .set(mapOf("typing" to typing, "updatedAt" to FieldValue.serverTimestamp()))
                .await()
        }
    }

    override suspend fun sendText(matchId: String, text: String) {
        send(matchId, MessageType.Text, text.trim())
    }

    /** The image goes into its own document (Firestore holds images on the free plan), then the message. */
    override suspend fun sendImage(matchId: String, uri: Uri, caption: String) {
        val image = FirestoreImages.prepare(context, uri)
        val imageRef = matches.document(matchId).collection("images").document()
        FirestoreImages.write(imageRef, image, mapOf("owner" to auth.requireUid()))
        send(matchId, MessageType.Image, caption.trim(), imageId = imageRef.id, blurHash = image.blurHash)
    }

    override suspend fun sendGame(matchId: String, card: GameCard) {
        send(matchId, MessageType.Game, card.prompt, game = mapOf("mode" to card.mode.key, "prompt" to card.prompt))
    }

    /** Message and the match's `lastMessage` in one batch; there is no server trigger to do it. */
    private suspend fun send(
        matchId: String,
        type: MessageType,
        text: String,
        imageId: String? = null,
        blurHash: String? = null,
        game: Map<String, String>? = null,
    ) {
        val uid = auth.requireUid()
        val match = matches.document(matchId)
        val batch = db.batch()
        batch.set(
            match.collection("messages").document(),
            mapOf(
                "senderId" to uid,
                "type" to type.key,
                "text" to text,
                "imageId" to imageId,
                "imageBlurhash" to blurHash,
                "game" to game,
                "createdAt" to FieldValue.serverTimestamp(),
            ),
        )
        val preview = when (type) {
            MessageType.Image -> text.ifBlank { "Photo" }
            else -> text
        }.take(200)
        batch.update(
            match,
            mapOf(
                "lastMessage" to mapOf("text" to preview, "senderId" to uid, "type" to type.key, "sentAt" to FieldValue.serverTimestamp()),
                "lastRead.$uid" to FieldValue.serverTimestamp(),
            ),
        )
        batch.commit().await()
    }

    override suspend fun markRead(matchId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching { matches.document(matchId).update("lastRead.$uid", FieldValue.serverTimestamp()).await() }
    }

    private fun DocumentSnapshot.toMatch(myUid: String): Match? {
        val data = data ?: return null
        val users = data.strings("users")
        val other = users.firstOrNull { it != myUid } ?: return null
        val profile = data.map("profiles")?.map(other)
        val last = data.map("lastMessage")
        val lastRead = data.map("lastRead")
        return Match(
            id = id,
            otherUid = other,
            otherName = profile?.str("name") ?: "Your match",
            otherPhotoUrl = profile?.str("photoUrl"),
            otherBlurHash = profile?.str("blurhash"),
            otherIsDemo = profile?.bool("isDemo") ?: false,
            createdAt = getTimestamp("createdAt")?.toInstant(),
            lastMessage = last?.let {
                LastMessage(
                    text = it.str("text").orEmpty(),
                    senderId = it.str("senderId").orEmpty(),
                    type = MessageType.of(it.str("type")),
                    sentAt = it.instant("sentAt"),
                )
            },
            myLastRead = lastRead?.instant(myUid),
            otherLastRead = lastRead?.instant(other),
            superLike = data.bool("superLike"),
        )
    }

    private fun DocumentSnapshot.toMessage(matchId: String): Message {
        val game = (get("game") as? Map<*, *>)?.let { GameCard(GameMode.of(it.str("mode")), it.str("prompt").orEmpty()) }
        return Message(
            id = id,
            senderId = getString("senderId").orEmpty(),
            type = MessageType.of(getString("type")),
            text = getString("text").orEmpty(),
            imagePath = getString("imageId")?.let { "matches/$matchId/images/$it" },
            imageUrl = getString("imageId")?.let { FirestoreImages.url("matches/$matchId/images/$it") },
            imageBlurHash = getString("imageBlurhash"),
            game = game,
            // Our own just-sent messages carry an estimated timestamp until the server confirms.
            createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
            pending = metadata.hasPendingWrites(),
        )
    }

    private companion object {
        const val TYPING_TTL_SECONDS = 12L
    }
}
