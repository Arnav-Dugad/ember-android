package io.github.arnavdugad.ember.data.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.repo.BackendMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
internal data class StoredAiMessage(
    val id: String,
    val text: String,
    val at: Long,
    /** My reaction to it, if any. */
    val reaction: String? = null,
)

/** One demo chat's AI side: the persona's messages plus their read/seen markers. */
@Serializable
internal data class StoredAiChat(
    val myUid: String,
    val personaUid: String,
    val messages: List<StoredAiMessage> = emptyList(),
    /** When the persona last "read" my messages. */
    val seenAt: Long? = null,
    /** When I last read theirs. */
    val readAt: Long? = null,
)

/**
 * The persona's side of demo chats, kept on this phone only. Their messages are never written to
 * Firestore: the security rules only let people post as themselves, and that stays true. The
 * user's own messages to a demo profile go through the normal backend like any other chat.
 *
 * Kept in memory only in Preview mode, where the chats themselves don't survive a restart.
 */
@Singleton
class AiChatStore @Inject constructor(@ApplicationContext context: Context, mode: BackendMode) {
    private val dir: File? = if (mode == BackendMode.Firebase) File(context.filesDir, "ai_chats") else null
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _chats = MutableStateFlow<Map<String, StoredAiChat>>(emptyMap())
    private var loaded = false

    internal val chats: StateFlow<Map<String, StoredAiChat>> = _chats.asStateFlow()

    internal fun observe(matchId: String): Flow<StoredAiChat?> = chats.map { it[matchId] }.distinctUntilChanged()

    /** Reads saved chats once; cheap to call repeatedly. */
    suspend fun load() = mutex.withLock {
        if (loaded) return@withLock
        loaded = true
        val files = withContext(Dispatchers.IO) { dir?.listFiles { f -> f.extension == "json" }.orEmpty().toList() }
        val restored = files.mapNotNull { file ->
            withContext(Dispatchers.IO) { runCatching { json.decodeFromString<StoredAiChat>(file.readText()) }.getOrNull() }
                ?.let { file.nameWithoutExtension to it }
        }.toMap()
        _chats.update { restored + it }
    }

    /** Adds one of the persona's messages, stamped now. */
    suspend fun add(matchId: String, myUid: String, personaUid: String, text: String) = edit(matchId, myUid, personaUid) { chat ->
        chat.copy(messages = chat.messages + StoredAiMessage("$ID_PREFIX${UUID.randomUUID()}", text, System.currentTimeMillis()))
    }

    suspend fun markSeen(matchId: String, myUid: String, personaUid: String) = edit(matchId, myUid, personaUid) {
        it.copy(seenAt = System.currentTimeMillis())
    }

    /** No-op for chats with no AI messages, so ordinary chats never create a file. */
    suspend fun markRead(matchId: String) {
        if (_chats.value[matchId] == null) return
        edit(matchId, null, null) { it.copy(readAt = System.currentTimeMillis()) }
    }

    suspend fun react(matchId: String, messageId: String, emoji: String?) = edit(matchId, null, null) { chat ->
        chat.copy(messages = chat.messages.map { if (it.id == messageId) it.copy(reaction = emoji) else it })
    }

    suspend fun delete(matchId: String) = mutex.withLock {
        _chats.update { it - matchId }
        withContext(Dispatchers.IO) { dir?.let { File(it, "$matchId.json").delete() } }
    }

    suspend fun wipe() = mutex.withLock {
        _chats.value = emptyMap()
        withContext(Dispatchers.IO) { dir?.deleteRecursively() }
    }

    private suspend fun edit(matchId: String, myUid: String?, personaUid: String?, change: (StoredAiChat) -> StoredAiChat) {
        load()
        mutex.withLock {
            val current = _chats.value[matchId]
                ?: if (myUid != null && personaUid != null) StoredAiChat(myUid, personaUid) else return@withLock
            val next = change(current)
            _chats.update { it + (matchId to next) }
            val folder = dir ?: return@withLock
            withContext(Dispatchers.IO) {
                folder.mkdirs()
                val tmp = File(folder, "$matchId.json.tmp")
                tmp.writeText(json.encodeToString(StoredAiChat.serializer(), next))
                tmp.renameTo(File(folder, "$matchId.json"))
            }
        }
    }

    companion object {
        /** AI message ids start with this, so reactions and replies know where they live. */
        const val ID_PREFIX = "ai_"

        fun isAiMessage(id: String) = id.startsWith(ID_PREFIX)
    }
}

internal fun StoredAiMessage.toMessage(chat: StoredAiChat) = Message(
    id = id,
    senderId = chat.personaUid,
    type = MessageType.Text,
    text = text,
    createdAt = Instant.ofEpochMilli(at),
    reactions = reaction?.let { mapOf(chat.myUid to it) }.orEmpty(),
)
