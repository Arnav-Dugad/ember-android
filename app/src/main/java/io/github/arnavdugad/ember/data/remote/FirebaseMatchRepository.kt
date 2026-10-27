package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Images
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
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.tasks.await
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseMatchRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    private val storage: FirebaseStorage,
    @ApplicationContext private val context: Context,
) : MatchRepository {
    private val matches get() = db.collection("matches")
    private val urlCache = ConcurrentHashMap<String, String>()

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
            .mapLatest { snap ->
                snap?.documents.orEmpty().map { doc ->
                    val message = doc.toMessage()
                    if (message.imagePath != null) message.copy(imageUrl = resolveUrl(message.imagePath)) else message
                }
            }

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
        val ref = matches.document(matchId).collection("messages").document()
        ref.set(messageData(MessageType.Text, text.trim())).await()
    }

    override suspend fun sendImage(matchId: String, uri: Uri, caption: String) {
        val ref = matches.document(matchId).collection("messages").document()
        val prepared = Images.prepare(context, uri, maxEdge = 1440)
        val path = "chats/$matchId/${ref.id}.jpg"
        storage.reference.child(path)
            .putBytes(prepared.bytes, StorageMetadata.Builder().setContentType("image/jpeg").build())
            .await()
        ref.set(messageData(MessageType.Image, caption.trim(), imagePath = path, blurHash = prepared.blurHash)).await()
    }

    override suspend fun sendGame(matchId: String, card: GameCard) {
        val ref = matches.document(matchId).collection("messages").document()
        ref.set(messageData(MessageType.Game, card.prompt, game = mapOf("mode" to card.mode.key, "prompt" to card.prompt))).await()
    }

    override suspend fun markRead(matchId: String) {
        val uid = auth.currentUser?.uid ?: return
        runCatching { matches.document(matchId).update("lastRead.$uid", FieldValue.serverTimestamp()).await() }
    }

    private fun messageData(
        type: MessageType,
        text: String,
        imagePath: String? = null,
        blurHash: String? = null,
        game: Map<String, String>? = null,
    ): Map<String, Any?> = mapOf(
        "senderId" to auth.requireUid(),
        "type" to type.key,
        "text" to text,
        "imagePath" to imagePath,
        "imageBlurhash" to blurHash,
        "game" to game,
        "createdAt" to FieldValue.serverTimestamp(),
    )

    private suspend fun resolveUrl(path: String): String? = urlCache[path] ?: runCatching {
        storage.reference.child(path).downloadUrl.await().toString()
    }.getOrNull()?.also { urlCache[path] = it }

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

    private fun DocumentSnapshot.toMessage(): Message {
        val game = (get("game") as? Map<*, *>)?.let { GameCard(GameMode.of(it.str("mode")), it.str("prompt").orEmpty()) }
        return Message(
            id = id,
            senderId = getString("senderId").orEmpty(),
            type = MessageType.of(getString("type")),
            text = getString("text").orEmpty(),
            imagePath = getString("imagePath"),
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
