package io.github.arnavdugad.ember.data.ai

import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZonedDateTime
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton
import kotlin.random.Random

/** Qualifier for the raw backend [MatchRepository], before AI replies are layered on. */
const val BACKEND = "backend"

/** A reply that couldn't be written, for the chat it belongs to. */
data class AiReplyError(val matchId: String, val message: String, val kind: AiException.Kind)

/**
 * Writes demo profiles' replies with the user's own AI key. After the user sends something it
 * waits for the burst to finish, "reads" it, shows typing, asks the model, then delivers the
 * reply as one to four bubbles at a believable pace. Runs app-wide, so a reply still lands (and
 * notifies) after the user leaves the chat.
 */
@Singleton
class AiChatEngine @Inject constructor(
    private val settings: AiSettingsRepository,
    private val client: AiClient,
    private val store: AiChatStore,
    @Named(BACKEND) private val backend: MatchRepository,
    private val profiles: ProfileRepository,
    private val appSettings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()

    /** Chats whose reply is already being delivered: a new message there waits its turn. */
    private val delivering = mutableSetOf<String>()
    private val again = mutableSetOf<String>()
    private val random = Random.Default

    private val _typing = MutableStateFlow<Set<String>>(emptySet())

    /** Match ids where the persona is "typing". */
    val typing: StateFlow<Set<String>> = _typing.asStateFlow()

    private val _errors = MutableSharedFlow<AiReplyError>(extraBufferCapacity = 8)
    val errors: SharedFlow<AiReplyError> = _errors.asSharedFlow()

    /** The user sent something in [matchId]. */
    fun onUserMessage(matchId: String) = start(matchId, opener = false)

    /**
     * The chat was opened: the persona may make the first move, and a message left unanswered
     * (say the app was closed mid-reply) gets its answer.
     */
    fun onChatOpened(matchId: String) {
        if (jobs[matchId]?.isActive == true) return
        scope.launch {
            val config = settings.current()
            if (!config.ready) return@launch
            val match = backend.observeMatch(matchId).first() ?: return@launch
            if (!match.otherIsDemo) return@launch
            val history = history(match)
            when {
                history.isEmpty() -> if (config.firstMove) start(matchId, opener = true)
                history.first().senderId != match.otherUid -> start(matchId, opener = false)
            }
        }
    }

    private fun start(matchId: String, opener: Boolean) {
        if (matchId in delivering) {
            again += matchId
            return
        }
        jobs[matchId]?.cancel()
        jobs[matchId] = scope.launch { run(matchId, opener) }
    }

    private suspend fun run(matchId: String, opener: Boolean) {
        try {
            respond(matchId, opener)
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            _errors.tryEmit(AiReplyError(matchId, e.message.orEmpty(), e.kind))
        } catch (e: Exception) {
            _errors.tryEmit(AiReplyError(matchId, "Couldn't get a reply. Try again.", AiException.Kind.Other))
        } finally {
            setTyping(matchId, false)
            delivering -= matchId
        }
        jobs.remove(matchId)
        if (again.remove(matchId)) start(matchId, opener = false)
    }

    private suspend fun respond(matchId: String, opener: Boolean) {
        val credentials = settings.credentials() ?: return
        val match = backend.observeMatch(matchId).first() ?: return
        if (!match.otherIsDemo) return
        val pace = settings.current().pace

        // Let a burst of texts land before answering them all at once.
        delay(if (opener) Persona.readDelayMs(pace, random) * 2 else BURST_MS[pace.ordinal])
        val history = history(match)
        if (opener && history.isNotEmpty()) return
        if (!opener && history.firstOrNull()?.senderId.let { it == null || it == match.otherUid }) return
        val them = profiles.getProfile(match.otherUid) ?: return
        val me = runCatching { profiles.observeMyProfile().first() }.getOrNull()

        if (!opener) {
            delay(Persona.readDelayMs(pace, random))
            store.markSeen(matchId, match.myUid, match.otherUid)
        }
        setTyping(matchId, true)
        val asked = System.currentTimeMillis()
        val system = Persona.systemPrompt(them, me, ZonedDateTime.now(), appSettings.current().afterDark)
        val turns = if (opener) Persona.opener() else Persona.transcript(history, match.otherUid)
        val bubbles = Persona.bubbles(client.reply(credentials, system, turns), them.name)

        delivering += matchId
        bubbles.forEachIndexed { index, text ->
            if (index > 0) {
                setTyping(matchId, false)
                delay(Persona.gapMs(pace, random))
                setTyping(matchId, true)
            }
            // The request itself already looked like typing; only make up the difference.
            val typed = if (index == 0) System.currentTimeMillis() - asked else 0
            delay((Persona.typingMs(text, pace, random) - typed).coerceAtLeast(MIN_TYPING_MS))
            store.add(matchId, match.myUid, match.otherUid, text)
        }
    }

    /** The whole chat, newest first, with the persona's messages merged in. */
    private suspend fun history(match: Match): List<Message> {
        store.load()
        val base = backend.observeMessages(match.id).first()
        return mergeAiMessages(base, store.chats.value[match.id])
    }

    private fun setTyping(matchId: String, typing: Boolean) =
        _typing.update { if (typing) it + matchId else it - matchId }

    private companion object {
        /** Indexed by [AiPace.ordinal]. */
        val BURST_MS = longArrayOf(2_500, 700)
        const val MIN_TYPING_MS = 300L
    }
}

/** [base] (newest first) with the persona's stored messages slotted in by time. */
internal fun mergeAiMessages(base: List<Message>, chat: StoredAiChat?): List<Message> {
    if (chat == null || chat.messages.isEmpty()) return base
    return (base + chat.messages.map { it.toMessage(chat) }).sortedByDescending { it.createdAt ?: Instant.MAX }
}
