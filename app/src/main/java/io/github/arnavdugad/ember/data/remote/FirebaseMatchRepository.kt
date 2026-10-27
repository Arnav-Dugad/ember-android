package io.github.arnavdugad.ember.data.remote

import android.content.Context
import android.net.Uri
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.Timestamp
import com.google.firebase.firestore.Blob
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.Source
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.media.VoiceFiles
import io.github.arnavdugad.ember.core.util.Connectivity
import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.LastMessage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Reactions
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.VoiceClip
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseMatchRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
) : MatchRepository {
    private val matches get() = db.collection("matches")

    override fun observeMatches(): Flow<List<Match>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            // Sorted on the client: an array-contains query with orderBy would need a composite index
            // on a field (lastMessage.sentAt) that is missing on brand-new matches.
            matches.whereArrayContains("users", uid).snapshots().combine(settings.privateReads) { snap, reads ->
                snap?.documents.orEmpty().mapNotNull { it.toMatch(uid, reads) }.newestFirst()
            }
        }
    }

    override suspend fun fetchMatches(): List<Match> {
        val uid = auth.currentUser?.uid ?: return emptyList()
        val reads = settings.privateReads.first()
        return matches.whereArrayContains("users", uid).get().await().documents.mapNotNull { it.toMatch(uid, reads) }.newestFirst()
    }

    override fun observeMatch(matchId: String): Flow<Match?> {
        val uid = auth.requireUid()
        return matches.document(matchId).snapshots().combine(settings.privateReads) { snap, reads -> snap?.toMatch(uid, reads) }
    }

    // Metadata changes are included so a message's "Sending…" clears the moment the server
    // acknowledges it, even when nothing else about the document changed.
    override fun observeMessages(matchId: String): Flow<List<Message>> =
        matches.document(matchId).collection("messages")
            .orderBy("createdAt", Query.Direction.DESCENDING)
            .limit(300)
            .snapshots(includeMetadata = true)
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
            commit(
                matches.document(matchId).collection("typing").document(uid)
                    .set(mapOf("typing" to typing, "updatedAt" to FieldValue.serverTimestamp())),
            )
        }
    }

    override suspend fun sendText(matchId: String, text: String, replyTo: ReplyRef?) {
        val trimmed = text.trim()
        send(matchId, MessageType.Text, trimmed, preview = trimmed, replyTo = replyTo)
    }

    /** The image goes into its own document (Firestore holds images on the free plan), then the message. */
    override suspend fun sendImage(matchId: String, uri: Uri, caption: String, viewOnce: Boolean, replyTo: ReplyRef?) {
        requireOnline("Photos need a connection. Try again when you're back online.")
        val image = FirestoreImages.prepare(context, uri)
        val imageRef = matches.document(matchId).collection("images").document()
        FirestoreImages.write(imageRef, image, mapOf("owner" to auth.requireUid()))
        val text = caption.trim()
        send(
            matchId,
            MessageType.Image,
            text,
            fields = mapOf("imageId" to imageRef.id, "imageBlurhash" to image.blurHash) + (if (viewOnce) mapOf("viewOnce" to true) else emptyMap()),
            preview = if (viewOnce) "View-once photo" else text.ifBlank { "Photo" },
            replyTo = replyTo,
        )
    }

    override suspend fun sendGame(matchId: String, card: GameCard) {
        send(
            matchId,
            MessageType.Game,
            card.prompt,
            fields = mapOf("game" to mapOf("mode" to card.mode.key, "prompt" to card.prompt)),
            preview = card.prompt,
        )
    }

    /** Like a photo: the AAC bytes go into their own document first, then the message. */
    override suspend fun sendVoice(matchId: String, clip: VoiceClip, replyTo: ReplyRef?) {
        requireOnline("Voice notes need a connection. Try again when you're back online.")
        val bytes = withContext(Dispatchers.IO) { clip.file.readBytes() }
        require(bytes.size <= MAX_AUDIO_BYTES) { "That voice note is too long to send." }
        val duration = clip.durationMs.coerceIn(1, MAX_VOICE_MS)
        val audioRef = matches.document(matchId).collection("audio").document()
        audioRef.set(
            mapOf(
                "owner" to auth.requireUid(),
                "data" to Blob.fromBytes(bytes),
                "createdAt" to FieldValue.serverTimestamp(),
                "durationMs" to duration,
            ),
        ).await()
        // Keep the sender's copy, so playing it back needs no download.
        withContext(Dispatchers.IO) {
            val cached = VoiceFiles.cached(context, audioRef.id)
            cached.parentFile?.mkdirs()
            if (!clip.file.renameTo(cached)) {
                clip.file.copyTo(cached, overwrite = true)
                clip.file.delete()
            }
        }
        send(
            matchId,
            MessageType.Voice,
            "",
            fields = mapOf("audioId" to audioRef.id, "durationMs" to duration),
            preview = "Voice note",
            replyTo = replyTo,
        )
    }

    override suspend fun sendDate(matchId: String, idea: DateIdea) {
        send(
            matchId,
            MessageType.Date,
            idea.title,
            fields = mapOf("date" to mapOf("title" to idea.title, "detail" to idea.detail, "emoji" to idea.emoji)),
            preview = idea.title,
        )
    }

    override suspend fun react(matchId: String, messageId: String, emoji: String?) {
        require(emoji == null || emoji in Reactions.all) { "That reaction isn't available." }
        val uid = auth.requireUid()
        val message = matches.document(matchId).collection("messages").document(messageId)
        commit(message.update("reactions.$uid", emoji ?: FieldValue.delete()))
    }

    /** One batch: the photo is gone for good, and the sender sees "Opened". */
    override suspend fun markViewOnceOpened(matchId: String, message: Message) {
        val batch = db.batch()
        message.imagePath?.let { batch.delete(db.document(it)) }
        batch.update(matches.document(matchId).collection("messages").document(message.id), "viewedAt", FieldValue.serverTimestamp())
        commit(batch.commit())
    }

    override suspend fun voiceFile(matchId: String, message: Message): File {
        val audioId = message.audioId ?: error("Not a voice note.")
        val file = VoiceFiles.cached(context, audioId)
        if (file.length() > 0) return file
        val snapshot = matches.document(matchId).collection("audio").document(audioId).get().await()
        val bytes = snapshot.getBlob("data")?.toBytes() ?: error("This voice note isn't available anymore.")
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val partial = File(file.parentFile, "$audioId.part")
            partial.writeBytes(bytes)
            partial.renameTo(file)
        }
        return file
    }

    /**
     * One batch: the tombstone (every content field deleted, so the rules can check exactly what
     * is left), the photo or voice note it carried, and the chat's preview if this was the latest.
     */
    override suspend fun unsend(matchId: String, message: Message) {
        requireOnline("Unsending needs a connection. Try again when you're back online.")
        val uid = auth.requireUid()
        check(message.canUnsend(uid, Instant.now())) { UNSEND_TOO_LATE }
        val match = matches.document(matchId)
        val batch = db.batch()
        batch.update(
            match.collection("messages").document(message.id),
            UNSENT_CLEARED_FIELDS.associateWith { FieldValue.delete() } + mapOf(
                "type" to MessageType.Text.key,
                "text" to Message.UNSENT_TEXT,
                "unsentAt" to FieldValue.serverTimestamp(),
            ),
        )
        message.imagePath?.let { batch.delete(db.document(it)) }
        message.audioId?.let { batch.delete(match.collection("audio").document(it)) }
        val last = cachedOrServer(match)?.get("lastMessage") as? Map<*, *>
        val lastSentAt = last?.get("sentAt") as? Timestamp
        if (last?.str("senderId") == uid && lastSentAt?.toInstant() == message.createdAt) {
            batch.update(
                match,
                "lastMessage",
                mapOf("text" to Message.UNSENT_TEXT, "senderId" to uid, "type" to MessageType.Text.key, "sentAt" to lastSentAt),
            )
        }
        try {
            commit(batch.commit())
        } catch (e: FirebaseFirestoreException) {
            if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw IllegalStateException(UNSEND_TOO_LATE)
            throw e
        }
        message.audioId?.let { id -> withContext(Dispatchers.IO) { VoiceFiles.cached(context, id).delete() } }
    }

    override suspend fun sendEvent(matchId: String, event: ChatEvent) {
        val uid = auth.requireUid()
        commit(
            matches.document(matchId).collection("messages").document().set(
                mapOf(
                    "senderId" to uid,
                    "type" to MessageType.Event.key,
                    "text" to event.fallback,
                    "event" to event.code,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            ),
        )
    }

    private suspend fun cachedOrServer(ref: DocumentReference): DocumentSnapshot? =
        runCatching { ref.get(Source.CACHE).await() }.getOrNull()?.takeIf { it.exists() }
            ?: runCatching { ref.get().await() }.getOrNull()

    /** Message and the match's `lastMessage` in one batch; there is no server trigger to do it. */
    private suspend fun send(
        matchId: String,
        type: MessageType,
        text: String,
        preview: String,
        fields: Map<String, Any?> = emptyMap(),
        replyTo: ReplyRef? = null,
    ) {
        val uid = auth.requireUid()
        val match = matches.document(matchId)
        val batch = db.batch()
        val reply = replyTo?.let {
            mapOf("id" to it.id, "senderId" to it.senderId, "text" to it.text, "type" to it.type.key)
        }
        batch.set(
            match.collection("messages").document(),
            mapOf(
                "senderId" to uid,
                "type" to type.key,
                "text" to text,
                "imageId" to null,
                "imageBlurhash" to null,
                "game" to null,
                "createdAt" to FieldValue.serverTimestamp(),
            ) + fields + (if (reply != null) mapOf("replyTo" to reply) else emptyMap()),
        )
        // With read receipts off, even replying doesn't move the marker the other person sees.
        val receipts = !settings.readReceiptsOff(matchId)
        batch.update(
            match,
            mapOf(
                "lastMessage" to mapOf("text" to preview.take(200), "senderId" to uid, "type" to type.key, "sentAt" to FieldValue.serverTimestamp()),
            ) + (if (receipts) mapOf("lastRead.$uid" to FieldValue.serverTimestamp()) else emptyMap()),
        )
        commit(batch.commit())
    }

    /**
     * Firestore applies a write to its local cache at once and syncs it whenever it can, so the
     * UI never waits for the network: offline, the message simply reads "Sending…" until then.
     * Online, the acknowledgement is awaited briefly so that a rejection (say, the match is gone)
     * still surfaces as an error.
     */
    private suspend fun commit(task: Task<Void>) {
        if (Connectivity.isOnline(context)) withTimeoutOrNull(ACK_TIMEOUT_MS) { task.await() }
    }

    private fun requireOnline(message: String) {
        check(Connectivity.isOnline(context)) { message }
    }

    override suspend fun markRead(matchId: String) {
        val uid = auth.currentUser?.uid ?: return
        val match = matches.document(matchId)
        if (settings.readReceiptsOff(matchId)) {
            // The newest message's server time too, so a phone clock running behind still covers it.
            val newest = runCatching { match.get(Source.CACHE).await().get("lastMessage.sentAt") as? Timestamp }.getOrNull()
            settings.setPrivateRead(matchId, maxOf(System.currentTimeMillis(), newest?.toInstant()?.toEpochMilli() ?: 0L))
            return
        }
        runCatching { commit(match.update("lastRead.$uid", FieldValue.serverTimestamp())) }
    }

    private fun List<Match>.newestFirst() = sortedByDescending { it.lastMessage?.sentAt ?: it.createdAt ?: Instant.EPOCH }

    /** [privateReads]: read markers kept on this phone for chats with read receipts off. */
    private fun DocumentSnapshot.toMatch(myUid: String, privateReads: Map<String, Long>): Match? {
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
            myLastRead = later(lastRead?.instant(myUid), privateReads[id]),
            otherLastRead = lastRead?.instant(other),
            superLike = data.bool("superLike"),
        )
    }

    private fun later(a: Instant?, millis: Long?): Instant? {
        val b = millis?.let(Instant::ofEpochMilli) ?: return a
        return if (a == null || b.isAfter(a)) b else a
    }

    private fun DocumentSnapshot.toMessage(matchId: String): Message {
        val game = mapField("game")?.let { GameCard(GameMode.of(it.str("mode")), it.str("prompt").orEmpty()) }
        val imageId = getString("imageId")
        val type = MessageType.of(getString("type"))
        return Message(
            id = id,
            senderId = getString("senderId").orEmpty(),
            type = type,
            text = getString("text").orEmpty(),
            imagePath = imageId?.let { "matches/$matchId/images/$it" },
            imageUrl = imageId?.let { FirestoreImages.url("matches/$matchId/images/$it") },
            imageBlurHash = getString("imageBlurhash"),
            game = game,
            // Our own just-sent messages carry an estimated timestamp until the server confirms.
            createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
            pending = metadata.hasPendingWrites(),
            reactions = mapField("reactions")?.entries
                ?.mapNotNull { (uid, emoji) -> if (uid is String && emoji is String) uid to emoji else null }
                ?.toMap()
                .orEmpty(),
            replyTo = mapField("replyTo")?.let { r ->
                r.str("id")?.let { ReplyRef(it, r.str("senderId").orEmpty(), r.str("text").orEmpty(), MessageType.of(r.str("type"))) }
            },
            viewOnce = getBoolean("viewOnce") == true,
            viewedAt = getTimestamp("viewedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
            audioId = getString("audioId"),
            durationMs = getLong("durationMs") ?: 0L,
            date = mapField("date")?.let { DateIdea(it.str("title").orEmpty(), it.str("detail").orEmpty(), it.str("emoji").orEmpty()) },
            event = if (type == MessageType.Event) ChatEvent.of(getString("event")) else null,
            unsentAt = getTimestamp("unsentAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
        )
    }

    private fun DocumentSnapshot.mapField(field: String): Map<*, *>? = get(field) as? Map<*, *>

    private companion object {
        const val TYPING_TTL_SECONDS = 12L
        const val ACK_TIMEOUT_MS = 8_000L
        const val MAX_VOICE_MS = 60_000L
        const val MAX_AUDIO_BYTES = 880 * 1024
        const val UNSEND_TOO_LATE = "It's been more than 15 minutes, so this one can't be unsent."

        /** Everything a message can carry besides who sent it and when; a tombstone keeps none of it. */
        val UNSENT_CLEARED_FIELDS = listOf(
            "imageId", "imageBlurhash", "game", "viewOnce", "viewedAt", "audioId", "durationMs", "date", "replyTo", "reactions", "event",
        )
    }
}
