package io.github.arnavdugad.ember.data.ai

import android.net.Uri
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.LastMessage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.VoiceClip
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import java.io.File
import java.time.Instant

/**
 * The backend's [MatchRepository] with demo profiles' AI replies layered on top: merged into the
 * chat, the match list's preview, unread state and read receipts, and the typing indicator. Every
 * screen, and the notifier, sees them as ordinary messages. Chats with real people pass straight
 * through.
 */
class AiMatchRepository(
    private val base: MatchRepository,
    private val store: AiChatStore,
    private val engine: AiChatEngine,
) : MatchRepository by base {
    override fun observeMatches(): Flow<List<Match>> =
        combine(base.observeMatches(), store.chats) { matches, chats ->
            if (chats.isEmpty()) {
                matches
            } else {
                matches.map { match -> chats[match.id]?.let { match.withAi(it) } ?: match }
                    .sortedByDescending { it.lastMessage?.sentAt ?: it.createdAt }
            }
        }.onStart { store.load() }

    override suspend fun fetchMatches(): List<Match> {
        store.load()
        val chats = store.chats.value
        return base.fetchMatches().map { match -> chats[match.id]?.let { match.withAi(it) } ?: match }
    }

    override fun observeMatch(matchId: String): Flow<Match?> =
        combine(base.observeMatch(matchId), store.observe(matchId)) { match, chat ->
            if (match != null && chat != null) match.withAi(chat) else match
        }.onStart { store.load() }

    override fun observeMessages(matchId: String): Flow<List<Message>> =
        combine(base.observeMessages(matchId), store.observe(matchId), ::mergeAiMessages).onStart { store.load() }

    override fun observeTyping(matchId: String, otherUid: String): Flow<Boolean> =
        combine(base.observeTyping(matchId, otherUid), engine.typing.map { matchId in it }) { real, ai -> real || ai }
            .distinctUntilChanged()

    override suspend fun sendText(matchId: String, text: String, replyTo: ReplyRef?) {
        base.sendText(matchId, text, replyTo)
        engine.onUserMessage(matchId)
    }

    override suspend fun sendImage(matchId: String, uri: Uri, caption: String, viewOnce: Boolean, replyTo: ReplyRef?) {
        base.sendImage(matchId, uri, caption, viewOnce, replyTo)
        engine.onUserMessage(matchId)
    }

    override suspend fun sendGame(matchId: String, card: GameCard) {
        base.sendGame(matchId, card)
        engine.onUserMessage(matchId)
    }

    override suspend fun sendVoice(matchId: String, clip: VoiceClip, replyTo: ReplyRef?) {
        base.sendVoice(matchId, clip, replyTo)
        engine.onUserMessage(matchId)
    }

    override suspend fun sendDate(matchId: String, idea: DateIdea) {
        base.sendDate(matchId, idea)
        engine.onUserMessage(matchId)
    }

    /** Reactions to the persona's messages stay on the phone with them. */
    override suspend fun react(matchId: String, messageId: String, emoji: String?) {
        if (AiChatStore.isAiMessage(messageId)) store.react(matchId, messageId, emoji) else base.react(matchId, messageId, emoji)
    }

    /** The persona's voice notes are synthesized on this phone. */
    override suspend fun voiceFile(matchId: String, message: Message): File =
        if (AiChatStore.isAiMessage(message.id)) {
            store.voiceFile(matchId, message.id) ?: throw IllegalStateException("That voice note is no longer on this phone.")
        } else {
            base.voiceFile(matchId, message)
        }

    override suspend fun markRead(matchId: String) {
        base.markRead(matchId)
        store.markRead(matchId)
    }

    /** The persona's latest message as the preview, and the local read/seen markers. */
    private fun Match.withAi(chat: StoredAiChat): Match {
        val latest = chat.messages.lastOrNull()?.let { LastMessage(it.text, chat.personaUid, MessageType.Text, Instant.ofEpochMilli(it.at)) }
        return copy(
            lastMessage = listOfNotNull(lastMessage, latest).maxByOrNull { it.sentAt ?: Instant.MIN },
            myLastRead = later(myLastRead, chat.readAt),
            otherLastRead = later(otherLastRead, chat.seenAt),
        )
    }

    private fun later(a: Instant?, b: Long?): Instant? {
        val other = b?.let(Instant::ofEpochMilli) ?: return a
        return if (a == null || other.isAfter(a)) other else a
    }
}

/** Unmatching or deleting the account also clears the AI side, which only this phone holds. */
class AiAwareSafetyRepository(
    private val base: SafetyRepository,
    private val store: AiChatStore,
    private val settings: AiSettingsRepository,
    private val cloud: AiCloudSync,
) : SafetyRepository by base {
    override suspend fun unmatch(matchId: String) {
        base.unmatch(matchId)
        store.delete(matchId)
    }

    override suspend fun deleteAccount() {
        // First, while still signed in: the rules only let the owner delete it.
        cloud.deleteFromAccount()
        base.deleteAccount()
        store.wipe()
        settings.wipe()
    }
}
