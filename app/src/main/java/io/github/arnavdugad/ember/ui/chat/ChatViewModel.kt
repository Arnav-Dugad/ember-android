package io.github.arnavdugad.ember.ui.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ChatUiState(
    val sending: Boolean = false,
    val uploading: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val removed: Boolean = false,
)

@HiltViewModel(assistedFactory = ChatViewModel.Factory::class)
class ChatViewModel @AssistedInject constructor(
    @Assisted private val matchId: String,
    private val repo: MatchRepository,
    private val safety: SafetyRepository,
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

    private val _ui = MutableStateFlow(ChatUiState())
    val ui: StateFlow<ChatUiState> = _ui.asStateFlow()

    private var typingJob: Job? = null
    private var typingSent = false

    init {
        // Read receipts: mark read whenever new messages arrive while the chat is open.
        viewModelScope.launch {
            messages.filterNotNull().map { it.firstOrNull()?.id }.distinctUntilChanged().collect { repo.markRead(matchId) }
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

    fun send(text: String) {
        if (text.isBlank()) return
        stopTyping()
        launch(sending = true) { repo.sendText(matchId, text.take(2000)) }
    }

    fun sendImage(uri: Uri) = launch(uploading = true) { repo.sendImage(matchId, uri, "") }

    fun drawCard(mode: GameMode): GameCard = GameCard(mode, (if (mode == GameMode.Truth) Catalog.truths else Catalog.dares).random())

    fun sendCard(card: GameCard) = launch(sending = true) { repo.sendGame(matchId, card) }

    fun report(reason: ReportReason, details: String, block: Boolean) {
        val other = match.value?.otherUid ?: return
        launch(busy = true, closeAfter = block) { safety.report(other, reason, details, matchId, block) }
    }

    fun block() {
        val other = match.value?.otherUid ?: return
        launch(busy = true, closeAfter = true) { safety.block(other) }
    }

    fun unmatch() = launch(busy = true, closeAfter = true) { safety.unmatch(matchId) }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun launch(sending: Boolean = false, uploading: Boolean = false, busy: Boolean = false, closeAfter: Boolean = false, block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(sending = sending || it.sending, uploading = uploading || it.uploading, busy = busy || it.busy) }
            val result = runCatching { block() }
            _ui.update {
                it.copy(
                    sending = false,
                    uploading = false,
                    busy = false,
                    removed = closeAfter && result.isSuccess,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't send. Try again." },
                )
            }
        }
    }

    override fun onCleared() {
        if (typingSent) {
            // Best effort: viewModelScope is already cancelled here.
            kotlinx.coroutines.MainScope().launch { repo.setTyping(matchId, false) }
        }
    }
}
