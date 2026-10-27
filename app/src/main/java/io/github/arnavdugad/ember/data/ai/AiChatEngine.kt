package io.github.arnavdugad.ember.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Base64
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Precision
import coil3.size.Size
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ReplyRef
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
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
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
 * waits for the burst to finish, "reads" it, shows typing, asks the model (with any recent photos
 * attached), then acts on the answer: a reaction, a quoted reply, one to four bubbles at a
 * believable pace, sometimes a voice note, and an updated mood, warmth and memory. Runs app-wide,
 * so a reply still lands (and notifies) after the user leaves the chat.
 */
@Singleton
class AiChatEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AiSettingsRepository,
    private val client: AiClient,
    private val store: AiChatStore,
    private val voice: AiVoice,
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

    /**
     * Background check-ins: at most one quiet demo chat per call gets a text from the persona,
     * following up on what it remembers. Called by the periodic message sync.
     */
    suspend fun checkIn() {
        val config = settings.current()
        if (!config.ready || !config.firstMove || settings.overCap()) return
        store.load()
        val now = System.currentTimeMillis()
        val hour = ZonedDateTime.now().hour
        val due = backend.fetchMatches().filter { it.otherIsDemo }.shuffled().firstOrNull { match ->
            val chat = store.chats.value[match.id]
            val lastCheckIn = chat?.messages?.lastOrNull { it.checkIn }
            Persona.checkInDue(match.otherUid, history(match), lastCheckIn?.id, chat?.lastCheckInAt, now, hour)
        } ?: return
        // Same thread as the chat-driven replies, which share the delivery bookkeeping.
        withContext(Dispatchers.Main.immediate) { runCatching { respond(due.id, Mode.CheckIn, instant = true) } }
    }

    private fun start(matchId: String, opener: Boolean) {
        if (matchId in delivering) {
            again += matchId
            return
        }
        jobs[matchId]?.cancel()
        jobs[matchId] = scope.launch { run(matchId, if (opener) Mode.Opener else Mode.Reply) }
    }

    private suspend fun run(matchId: String, mode: Mode) {
        try {
            respond(matchId, mode, instant = false)
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

    private enum class Mode { Reply, Opener, CheckIn }

    private suspend fun respond(matchId: String, mode: Mode, instant: Boolean) {
        val credentials = settings.credentials() ?: return
        val match = backend.observeMatch(matchId).first() ?: return
        if (!match.otherIsDemo) return
        val config = settings.current()
        val pace = config.pace
        if (settings.overCap()) {
            throw AiException("This month's AI reply limit (${config.monthlyCap}) is used up. Change it in Settings → AI chat.", AiException.Kind.Limit)
        }

        // Let a burst of texts land before answering them all at once.
        if (!instant) delay(if (mode == Mode.Opener) Persona.readDelayMs(pace, random) * 2 else BURST_MS[pace.ordinal])
        val history = history(match)
        when (mode) {
            Mode.Opener -> if (history.isNotEmpty()) return
            Mode.Reply -> if (history.firstOrNull()?.senderId.let { it == null || it == match.otherUid }) return
            Mode.CheckIn -> if (history.isEmpty()) return
        }
        val them = profiles.getProfile(match.otherUid) ?: return
        val me = runCatching { profiles.observeMyProfile().first() }.getOrNull()
        val chat = store.chats.value[matchId]
        val state = chat?.state ?: PersonaState(warmth = Persona.START_WARMTH + if (match.superLike) SUPER_LIKE_WARMTH else 0)

        if (mode == Mode.Reply) {
            if (!instant) delay(Persona.readDelayMs(pace, random))
            store.markSeen(matchId, match.myUid, match.otherUid)
        }
        setTyping(matchId, true)
        val asked = System.currentTimeMillis()
        val system = Persona.systemPrompt(them, me, ZonedDateTime.now(), appSettings.current().afterDark, state, config.voiceNotes)
        val transcript = Persona.transcript(history, match.otherUid, if (mode == Mode.Opener) emptyMap() else photos(history, match.otherUid))
        val turns = when (mode) {
            Mode.Opener -> Persona.opener()
            Mode.Reply -> transcript.turns
            Mode.CheckIn -> Persona.checkIn(transcript, ((asked - (history.first().createdAt?.toEpochMilli() ?: asked)) / HOUR_MS).coerceAtLeast(1))
        }
        val result = client.complete(credentials, system, turns, jsonReply = true)
        settings.recordUse(credentials.provider, credentials.model, result.usage, reply = true)
        val reply = Persona.parse(result.text, them.name)
        store.updateState(matchId, match.myUid, match.otherUid, Persona.next(state, reply))

        delivering += matchId
        val latestMine = history.firstOrNull { it.senderId != match.otherUid }
        if (mode == Mode.Reply && reply.react != null && latestMine != null) {
            store.reactToMine(matchId, match.myUid, match.otherUid, latestMine.id, reply.react)
        }
        val quoted = reply.replyToTag?.let(transcript.tags::get)?.let { id -> history.firstOrNull { it.id == id } }
            // Quoting the message you're plainly answering adds nothing.
            ?.takeIf { it.id != latestMine?.id }
        reply.texts.forEachIndexed { index, text ->
            if (index > 0) {
                setTyping(matchId, false)
                if (!instant) delay(Persona.gapMs(pace, random))
                setTyping(matchId, true)
            }
            val spoken = if (index == 0 && reply.voice && config.voiceNotes) speak(text, them) else null
            if (!instant) {
                // The request itself already looked like typing; only make up the difference.
                val typed = if (index == 0) System.currentTimeMillis() - asked else 0
                delay((Persona.typingMs(text, pace, random) - typed).coerceAtLeast(MIN_TYPING_MS))
            }
            store.add(
                matchId, match.myUid, match.otherUid, text,
                replyTo = if (index == 0) quoted?.let(ReplyRef::of) else null,
                voice = spoken?.first,
                durationMs = spoken?.second ?: 0,
                checkIn = mode == Mode.CheckIn,
            )
        }
    }

    private suspend fun speak(text: String, them: Profile): Pair<java.io.File, Long>? =
        runCatching { voice.speak(text, them.uid, them.gender, store.voiceDir) }.getOrNull()

    /** The newest photos the user sent (not view-once ones), ready for the model to look at. */
    private suspend fun photos(newestFirst: List<Message>, personaUid: String): Map<String, AiImage> =
        newestFirst.take(Persona.HISTORY)
            .filter { it.senderId != personaUid && it.type == MessageType.Image && !it.viewOnce && it.imageUrl != null }
            .take(MAX_PHOTOS)
            .mapNotNull { message -> loadPhoto(message.imageUrl!!)?.let { message.id to it } }
            .toMap()

    private suspend fun loadPhoto(url: String): AiImage? = runCatching {
        val request = ImageRequest.Builder(context)
            .data(url)
            .size(Size(PHOTO_PX, PHOTO_PX))
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()
        val result = SingletonImageLoader.get(context).execute(request) as? SuccessResult ?: return null
        withContext(Dispatchers.Default) {
            val bytes = ByteArrayOutputStream().use { out ->
                result.image.toBitmap().compress(Bitmap.CompressFormat.JPEG, 80, out)
                out.toByteArray()
            }
            AiImage("image/jpeg", Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }.getOrNull()

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
        const val HOUR_MS = 3_600_000L
        const val MAX_PHOTOS = 2
        const val PHOTO_PX = 768
        const val SUPER_LIKE_WARMTH = 10
    }
}

/**
 * [base] (newest first) with the persona's stored messages slotted in by time, and the persona's
 * reactions to the user's messages added to those messages.
 */
internal fun mergeAiMessages(base: List<Message>, chat: StoredAiChat?): List<Message> {
    if (chat == null) return base
    val reacted = if (chat.reactions.isEmpty()) {
        base
    } else {
        base.map { message -> chat.reactions[message.id]?.let { message.copy(reactions = message.reactions + (chat.personaUid to it)) } ?: message }
    }
    if (chat.messages.isEmpty()) return reacted
    return (reacted + chat.messages.map { it.toMessage(chat) }).sortedByDescending { it.createdAt ?: Instant.MAX }
}
