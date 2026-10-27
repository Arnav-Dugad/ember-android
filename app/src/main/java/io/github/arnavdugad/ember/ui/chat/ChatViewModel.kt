package io.github.arnavdugad.ember.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.ai.AiChatEngine
import io.github.arnavdugad.ember.data.ai.AiProvider
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.DatePlanner
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.model.VoiceClip
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import io.github.arnavdugad.ember.data.settings.ChatPrefs
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.ui.matches.UnmatchQueue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant

data class ChatUiState(
    val uploading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val removed: Boolean = false,
    /** The message the next send replies to. */
    val replyTo: ReplyRef? = null,
)

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val matchId: String,
    private val repo: MatchRepository,
    private val safety: SafetyRepository,
    private val unmatches: UnmatchQueue,
    profiles: ProfileRepository,
    private val ai: AiChatEngine,
    aiSettings: AiSettingsRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(matchId: String): ChatViewModel
    }

    val match: StateFlow<Match?> = repo.observeMatch(matchId).catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val messages: StateFlow<List<Message>?> = repo.observeMessages(matchId).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val otherTyping: StateFlow<Boolean> = match.filterNotNull().map { it.otherUid }.distinctUntilChanged()
        .flatMapLatest { other -> if (other.isBlank()) emptyFlow() else repo.observeTyping(matchId, other) }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // Both profiles feed the date planner; neither is needed for anything else here.
    private val myProfile: StateFlow<Profile?> = profiles.observeMyProfile().catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val theirProfile: StateFlow<Profile?> = match.filterNotNull().map { it.otherUid }.distinctUntilChanged()
        .map { uid -> runCatching { profiles.getProfile(uid) }.getOrNull() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** The AI that answers for demo profiles, or null when AI chat is off or not set up. */
    val aiProvider: StateFlow<AiProvider?> = aiSettings.config.map { if (it.ready) it.provider else null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Read receipts, photo blurring and screenshot alerts. Device-local. */
    val prefs: StateFlow<ChatPrefs> = settings.chatPrefs.catch { emit(ChatPrefs()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ChatPrefs())

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var typingJob: Job? = null
    private var typingSent = false
    private val openedViewOnce = mutableSetOf<String>()
    private var lastScreenshotAlert = 0L

    init {
        // Read receipts: mark read whenever new messages arrive while the chat is open.
        viewModelScope.launch {
            messages.filterNotNull().map { it.firstOrNull()?.id }.distinctUntilChanged().collect { repo.markRead(matchId) }
        }
        ai.onChatOpened(matchId)
        viewModelScope.launch {
            ai.errors.filter { it.matchId == matchId }.collect { error -> _ui.update { it.copy(error = error.message) } }
        }
    }

    /** Debounced typing indicator: on at the first keystroke, off after 3 s of quiet. */
    fun onTyping(text: String) {
        if (text.isBlank()) {
            stopTyping()
            return
        }
        if (!typingSent) {
            typingSent = true
            viewModelScope.launch { repo.setTyping(matchId, true) }
        }
        typingJob?.cancel()
        typingJob = viewModelScope.launch {
            delay(3_000)
            stopTyping()
        }
    }

    private fun stopTyping() {
        typingJob?.cancel()
        if (typingSent) {
            typingSent = false
            viewModelScope.launch { repo.setTyping(matchId, false) }
        }
    }

    fun replyTo(message: Message) = _ui.update { it.copy(replyTo = ReplyRef.of(message)) }

    fun cancelReply() = _ui.update { it.copy(replyTo = null) }

    /** Takes the pending reply, so it's attached to exactly one message. */
    private fun consumeReply(): ReplyRef? = _ui.value.replyTo.also { if (it != null) cancelReply() }

    fun send(text: String) {
        if (text.isBlank()) return
        stopTyping()
        val reply = consumeReply()
        launch { repo.sendText(matchId, text.take(2000), reply) }
    }

    /** Sends one of the date card's quick replies as an ordinary text message. */
    fun quickReply(text: String) = launch { repo.sendText(matchId, text) }

    fun sendImage(uri: Uri, viewOnce: Boolean) {
        val reply = consumeReply()
        launch(uploading = true) { repo.sendImage(matchId, uri, "", viewOnce, reply) }
    }

    fun sendVoice(clip: VoiceClip) {
        val reply = consumeReply()
        launch(uploading = true) {
            // A sent clip has already been moved into the voice cache; a failed one is dropped.
            try {
                repo.sendVoice(matchId, clip, reply)
            } finally {
                clip.file.delete()
            }
        }
    }

    fun drawCard(mode: GameMode): GameCard = GameCard(mode, (if (mode == GameMode.Truth) Catalog.truths else Catalog.dares).random())

    fun sendCard(card: GameCard) = launch { repo.sendGame(matchId, card) }

    /** Fresh date ideas from both people's interests and the city. */
    fun suggestDates(): List<DateIdea> {
        val me = myProfile.value
        val them = theirProfile.value
        val city = me?.city?.ifBlank { null } ?: them?.city.orEmpty()
        return DatePlanner.suggest(me?.interests.orEmpty(), them?.interests.orEmpty(), city)
    }

    fun sendDate(idea: DateIdea) = launch { repo.sendDate(matchId, idea) }

    /** Double-tap: always a heart, so a second double-tap doesn't take it back. */
    fun heart(message: Message) {
        val mine = match.value?.myUid?.let { message.reactions[it] }
        if (mine != HEART) launch { repo.react(matchId, message.id, HEART) }
    }

    fun canUnsend(message: Message): Boolean = match.value?.myUid?.let { message.canUnsend(it, Instant.now()) } == true

    fun unsend(message: Message) {
        if (_ui.value.replyTo?.id == message.id) cancelReply()
        launch { repo.unsend(matchId, message) }
    }

    fun setReadReceipts(enabled: Boolean) {
        viewModelScope.launch {
            settings.setReadReceipts(matchId, enabled)
            // Back on: the other person catches up at once.
            if (enabled) repo.markRead(matchId)
        }
    }

    fun setBlurAllPhotos(enabled: Boolean) {
        viewModelScope.launch { settings.setBlurAllPhotos(enabled) }
    }

    fun setScreenshotAlerts(enabled: Boolean) {
        viewModelScope.launch { settings.setScreenshotAlerts(enabled) }
    }

    /** At most one alert per [SCREENSHOT_ALERT_GAP_MS], so a burst of screenshots is one row. */
    fun screenshotTaken(now: Long = System.currentTimeMillis()) {
        if (!prefs.value.screenshotAlerts || now - lastScreenshotAlert < SCREENSHOT_ALERT_GAP_MS) return
        lastScreenshotAlert = now
        viewModelScope.launch { runCatching { repo.sendEvent(matchId, ChatEvent.Screenshot) } }
    }

    /** Tapping the reaction you already gave takes it back. */
    fun react(message: Message, emoji: String) {
        val mine = match.value?.myUid?.let { message.reactions[it] }
        launch { repo.react(matchId, message.id, if (mine == emoji) null else emoji) }
    }

    /** Called once the recipient closes a view-once photo. Idempotent. */
    fun viewOnceClosed(message: Message) {
        if (!openedViewOnce.add(message.id)) return
        launch { repo.markViewOnceOpened(matchId, message) }
    }

    suspend fun voiceFile(message: Message): File = repo.voiceFile(matchId, message)

    fun report(reason: ReportReason, details: String, block: Boolean) {
        val other = match.value?.otherUid ?: return
        launch(busy = true, closeAfter = block) { safety.report(other, reason, details, matchId, block) }
    }

    fun block() {
        val other = match.value?.otherUid ?: return
        launch(busy = true, closeAfter = true) { safety.block(other) }
    }

    /** Hides the conversation now; the real unmatch waits out the Undo window. */
    fun unmatch() {
        unmatches.unmatch(matchId, match.value?.otherName ?: "your match")
        _ui.update { it.copy(removed = true) }
    }

    fun showError(message: String) = _ui.update { it.copy(error = message) }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun launch(uploading: Boolean = false, busy: Boolean = false, closeAfter: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(uploading = uploading || it.uploading, busy = busy || it.busy) }
            val result = runCatching { block() }
            _ui.update {
                it.copy(
                    uploading = if (uploading) false else it.uploading,
                    busy = if (busy) false else it.busy,
                    removed = it.removed || (closeAfter && result.isSuccess),
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't send. Try again." } ?: it.error,
                )
            }
        }
    }

    private companion object {
        const val HEART = "❤️"
        const val SCREENSHOT_ALERT_GAP_MS = 10_000L
    }

    override fun onCleared() {
        if (typingSent) {
            // Best effort: viewModelScope is already cancelled here.
            kotlinx.coroutines.MainScope().launch { repo.setTyping(matchId, false) }
        }
    }
}
