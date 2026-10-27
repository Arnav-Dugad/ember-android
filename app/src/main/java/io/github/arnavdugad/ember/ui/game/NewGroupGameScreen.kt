package io.github.arnavdugad.ember.ui.game

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewGameUi(
    val loading: Boolean = true,
    val candidates: List<GamePlayer> = emptyList(),
    /** In the order they were picked, which becomes the turn order after the host. */
    val selected: List<String> = emptyList(),
    val sending: Boolean = false,
    val error: String? = null,
    val createdId: String? = null,
    /** Preview mode: every match is a demo profile, so only a solo practice game is possible. */
    val practiceOnly: Boolean = false,
)

@HiltViewModel(assistedFactory = NewGroupGameViewModel.Factory::class)
class NewGroupGameViewModel @AssistedInject constructor(
    @Assisted private val fromMatchId: String?,
    private val repo: GameRepository,
    private val auth: AuthRepository,
    private val mode: BackendMode,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(fromMatchId: String?): NewGroupGameViewModel
    }

    private val _ui = MutableStateFlow(NewGameUi())
    val ui: StateFlow<NewGameUi> = _ui.asStateFlow()

    init {
        viewModelScope.launch {
            val myUid = auth.user.first()?.uid.orEmpty()
            runCatching { repo.inviteCandidates() }
                .onSuccess { candidates ->
                    // From a chat's game sheet, that match starts out picked.
                    val other = fromMatchId?.split('_')?.firstOrNull { it != myUid }
                    _ui.update {
                        it.copy(
                            loading = false,
                            candidates = candidates,
                            selected = listOfNotNull(other?.takeIf { uid -> candidates.any { c -> c.uid == uid } }),
                            practiceOnly = mode == BackendMode.Preview && candidates.isEmpty(),
                        )
                    }
                }
                .onFailure { e -> _ui.update { it.copy(loading = false, error = e.message ?: "Couldn't load your matches.") } }
        }
    }

    fun toggle(uid: String) = _ui.update { state ->
        when {
            uid in state.selected -> state.copy(selected = state.selected - uid, error = null)
            state.selected.size >= GroupGames.MAX_INVITES ->
                state.copy(error = "A game has room for you and up to ${GroupGames.MAX_INVITES} others.")
            else -> state.copy(selected = state.selected + uid, error = null)
        }
    }

    fun create() {
        val state = _ui.value
        if (state.sending) return
        val invitees = state.selected.mapNotNull { uid -> state.candidates.firstOrNull { it.uid == uid } }
        if (invitees.isEmpty() && !state.practiceOnly) {
            _ui.update { it.copy(error = "Pick at least one match to play with.") }
            return
        }
        _ui.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            runCatching { repo.createGame(invitees) }
                .onSuccess { id -> _ui.update { it.copy(sending = false, createdId = id) } }
                .onFailure { e -> _ui.update { it.copy(sending = false, error = e.message ?: "Couldn't start the game.") } }
        }
    }
}

/** Pick up to three matches to play with. Demo profiles never show: they could never accept. */
@Composable
fun NewGroupGameScreen(fromMatchId: String?, onBack: () -> Unit, onCreated: (String) -> Unit) {
    val viewModel = hiltViewModel<NewGroupGameViewModel, NewGroupGameViewModel.Factory>(key = "new-game-$fromMatchId") { it.create(fromMatchId) }
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    LaunchedEffect(ui.createdId) { ui.createdId?.let(onCreated) }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        Column(Modifier.fillMaxSize()) {
            GameTopBar(title = "Group Truth or Dare", subtitle = "You and up to ${GroupGames.MAX_INVITES} matches", onBack = onBack)
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        "Each person gets an invite with your first name and the others' first names, and joins only if they accept. " +
                            "Players see each other's first name and first photo, nothing else. Anyone can leave whenever they like.",
                        style = EmberTheme.type.bodySmall,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                when {
                    ui.loading -> items(4) { Box(Modifier.fillMaxWidth().height(68.dp).shimmer(EmberShapes.tile)) }
                    ui.candidates.isEmpty() -> item { NoCandidates(ui.practiceOnly) }
                    else -> items(ui.candidates, key = { it.uid }) { player ->
                        CandidateRow(player, order = ui.selected.indexOf(player.uid), onClick = { viewModel.toggle(player.uid) })
                    }
                }
            }
            Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                ui.error?.let {
                    Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(bottom = 8.dp, start = 4.dp))
                }
                EmberButton(
                    text = when {
                        ui.practiceOnly -> "Start a practice round"
                        ui.selected.isEmpty() -> "Send invites"
                        ui.selected.size == 1 -> "Invite 1 match"
                        else -> "Invite ${ui.selected.size} matches"
                    },
                    onClick = viewModel::create,
                    enabled = !ui.loading && (ui.practiceOnly || ui.selected.isNotEmpty()),
                    loading = ui.sending,
                    icon = Icons.Rounded.Groups,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** A match to invite. Picked ones show their place in the turn order (after you). */
@Composable
private fun CandidateRow(player: GamePlayer, order: Int, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val picked = order >= 0
    val border by animateColorAsState(if (picked) colors.accent else Color.Transparent, label = "picked")
    val check by animateFloatAsState(if (picked) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "check")
    GlassSurface(
        Modifier.fillMaxWidth().border(1.5.dp, border, EmberShapes.tile).pressable(onClick = onClick, pressedScale = 0.98f, haptic = false),
        shape = EmberShapes.tile,
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            PlayerAvatar(player, size = 46.dp)
            Spacer(Modifier.width(14.dp))
            Text(player.name, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(28.dp)
                    .border(1.5.dp, colors.textTertiary.copy(alpha = 1f - check), CircleShape)
                    .graphicsLayer {
                        scaleX = 0.6f + 0.4f * check
                        scaleY = 0.6f + 0.4f * check
                    }
                    .background(colors.accentBrush, CircleShape, alpha = check),
                contentAlignment = Alignment.Center,
            ) {
                if (picked) Icon(Icons.Rounded.Check, contentDescription = "Picked, plays ${order + 2}", tint = colors.onAccent, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NoCandidates(practiceOnly: Boolean) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmberFlame(size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text("No one to invite yet", style = EmberTheme.type.title, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            if (practiceOnly) {
                "Demo profiles can't join group games: they could never say yes. Try a practice round to see how it plays."
            } else {
                "Group games are for real matches. Demo profiles, and anyone with a block either way, can't be invited."
            },
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}
