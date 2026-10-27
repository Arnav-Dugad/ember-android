package io.github.arnavdugad.ember.ui.game

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.GameMove
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** [game] is null once it's gone: ended, deleted, or I'm no longer in it. */
data class GameState(val loading: Boolean = true, val game: GroupGame? = null)

data class GameUi(
    val busy: Boolean = false,
    val error: String? = null,
    /** I left, declined or the host ended it from this screen: close it. */
    val closed: Boolean = false,
)

@HiltViewModel(assistedFactory = GroupGameViewModel.Factory::class)
class GroupGameViewModel @AssistedInject constructor(
    @Assisted private val gameId: String,
    private val repo: GameRepository,
    auth: AuthRepository,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(gameId: String): GroupGameViewModel
    }

    val myUid: StateFlow<String> = auth.user.map { it?.uid.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val state: StateFlow<GameState> = repo.observeGame(gameId)
        .map { GameState(loading = false, game = it) }
        .catch { emit(GameState(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GameState())

    /** Members only: the rules show an invitee nothing beyond the invite. */
    val moves: StateFlow<List<GameMove>> = combine(state, myUid) { s, me -> s.game?.let { me in it.members } == true }
        .distinctUntilChanged()
        .flatMapLatest { member -> if (member) repo.observeMoves(gameId).catch { emit(emptyList()) } else flowOf(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui.asStateFlow()

    /** Draws from the same deck as the 1:1 game, skipping prompts this game already had. */
    fun pick(mode: GameMode) = act {
        val used = moves.value.mapNotNull { it.card?.prompt }
        repo.pickCard(gameId, GroupGames.draw(mode, used))
    }

    fun answer(text: String) = act { repo.answer(gameId, text, pass = false) }

    fun pass() = act { repo.answer(gameId, "", pass = true) }

    fun skip() = act { repo.skipTurn(gameId) }

    fun sendChat(text: String) {
        if (text.isBlank()) return
        viewModelScope.launch {
            runCatching { repo.sendChat(gameId, text) }.onFailure { e -> showError(e.message) }
        }
    }

    fun react(move: GameMove, emoji: String) {
        val mine = move.reactions[myUid.value]
        viewModelScope.launch {
            runCatching { repo.reactToMove(gameId, move.id, if (mine == emoji) null else emoji) }.onFailure { e -> showError(e.message) }
        }
    }

    fun accept() = act { repo.accept(gameId) }

    fun decline() = act(closeAfter = true) { repo.decline(gameId) }

    fun leave() = act(closeAfter = true) { repo.leave(gameId) }

    fun end() = act(closeAfter = true) { repo.endGame(gameId) }

    fun remove(uid: String) = act { repo.removePlayer(gameId, uid) }

    /** Ended or quiet for a day: clear it away so it stops taking up space. */
    fun cleanUp() {
        viewModelScope.launch { runCatching { repo.sweep() } }
    }

    fun clearError() = _ui.update { it.copy(error = null) }

    private fun showError(message: String?) = _ui.update { it.copy(error = message ?: "Something went wrong. Try again.") }

    private fun act(closeAfter: Boolean = false, block: suspend () -> Unit) {
        if (_ui.value.busy) return
        _ui.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            runCatching { block() }
                .onSuccess { _ui.update { it.copy(busy = false, closed = closeAfter) } }
                .onFailure { e -> _ui.update { it.copy(busy = false, error = e.message ?: "Something went wrong. Try again.") } }
        }
    }
}
