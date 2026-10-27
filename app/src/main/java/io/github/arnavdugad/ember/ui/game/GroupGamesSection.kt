package io.github.arnavdugad.ember.ui.game

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.GameRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Opens the group game picker from inside a 1:1 chat, with that match already picked. Provided
 * by the navigation host around the chat; null (no button) anywhere else.
 */
val LocalGroupGameLauncher = compositionLocalOf<(() -> Unit)?> { null }

@HiltViewModel
class GroupGamesViewModel @Inject constructor(
    private val repo: GameRepository,
    auth: AuthRepository,
) : ViewModel() {
    val myUid: StateFlow<String> = auth.user.map { it?.uid.orEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val games: StateFlow<List<GroupGame>> = repo.observeGames().catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Invites being answered right now, so their buttons can't be tapped twice. */
    private val _busy = MutableStateFlow<Set<String>>(emptySet())
    val busy: StateFlow<Set<String>> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    init {
        // Tidy up games that ended or went quiet while the app was closed.
        viewModelScope.launch { runCatching { repo.sweep() } }
    }

    fun accept(gameId: String, onJoined: (String) -> Unit) = answer(gameId) {
        repo.accept(gameId)
        onJoined(gameId)
    }

    fun decline(gameId: String) = answer(gameId) { repo.decline(gameId) }

    fun clearError() {
        _error.value = null
    }

    private fun answer(gameId: String, block: suspend () -> Unit) {
        if (gameId in _busy.value) return
        _busy.update { it + gameId }
        viewModelScope.launch {
            runCatching { block() }.onFailure { e -> _error.value = e.message ?: "Something went wrong. Try again." }
            _busy.update { it - gameId }
        }
    }
}

/**
 * The Matches screen's group games: invites first ("Arjun invited you to Truth or Dare with 2
 * others", first names only, nothing joins until you say so), then games you're in. Draws
 * nothing when there are none.
 */
@Composable
fun GroupGamesSection(onOpenGame: (String) -> Unit, viewModel: GroupGamesViewModel = hiltViewModel()) {
    val games by viewModel.games.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    error?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(3_000)
            viewModel.clearError()
        }
    }
    AnimatedVisibility(games.isNotEmpty(), enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionHeader("Group games", Modifier.padding(top = 12.dp))
            error?.let { Text(it, style = EmberTheme.type.caption, color = EmberTheme.colors.danger) }
            games.forEach { game ->
                if (myUid in game.invited) {
                    InviteCard(
                        game = game,
                        myUid = myUid,
                        busy = game.id in busy,
                        onJoin = { viewModel.accept(game.id, onOpenGame) },
                        onDecline = { viewModel.decline(game.id) },
                    )
                } else {
                    GameRow(game, myUid, onClick = { onOpenGame(game.id) })
                }
            }
        }
    }
}

@Composable
private fun InviteCard(game: GroupGame, myUid: String, busy: Boolean, onJoin: () -> Unit, onDecline: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().border(1.dp, colors.accent.copy(alpha = 0.45f), EmberShapes.tile), shape = EmberShapes.tile) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlayerAvatar(game.host, size = 46.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(GroupGames.inviteTitle(game, myUid), style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                    Text(
                        "With " + GroupGames.othersLine(game, myUid),
                        style = EmberTheme.type.caption,
                        color = colors.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SmallPill("Not now", filled = false, enabled = !busy, onClick = onDecline, modifier = Modifier.weight(1f))
                SmallPill("Join", filled = true, enabled = !busy, onClick = onJoin, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun GameRow(game: GroupGame, myUid: String, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val mine = game.turnUid == myUid
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarStack(game.members.map(game::player))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Truth or Dare", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(
                    GroupGames.joinNames(game.members.filter { it != myUid }.map { game.player(it).name }).ifEmpty { "Waiting for the others to join" },
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier
                    .then(if (mine) Modifier.background(colors.accentHorizontal, PillShape) else Modifier.background(colors.surfaceHigh, PillShape))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    GroupGames.turnLabel(game, myUid),
                    style = EmberTheme.type.overline,
                    color = if (mine) colors.onAccent else colors.textSecondary,
                )
            }
        }
    }
}

/** Overlapping circles, one per member. */
@Composable
private fun AvatarStack(players: List<GamePlayer>) {
    val shown = players.take(GroupGames.MAX_PLAYERS)
    Box(Modifier.width(34.dp + 18.dp * (shown.size - 1).coerceAtLeast(0)).height(34.dp)) {
        shown.forEachIndexed { index, player ->
            PlayerAvatar(
                player,
                size = 34.dp,
                modifier = Modifier.offset(x = 18.dp * index).border(2.dp, EmberTheme.colors.background, CircleShape),
            )
        }
    }
}

/**
 * A player's first photo, or their initial while they haven't joined (invitees have no photo in
 * the game until they accept).
 */
@Composable
internal fun PlayerAvatar(player: GamePlayer, size: Dp, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    Box(modifier.size(size).clip(CircleShape).background(colors.surfaceHigh), contentAlignment = Alignment.Center) {
        if (player.photoUrl != null) {
            EmberImage(player.photoUrl, player.blurHash, contentDescription = player.name, modifier = Modifier.fillMaxSize())
        } else {
            Text(player.name.take(1).uppercase(), style = EmberTheme.type.titleSmall, color = colors.textSecondary)
        }
    }
}

/** A compact pill button for cards in a list. */
@Composable
internal fun SmallPill(text: String, filled: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    Box(
        modifier
            .height(40.dp)
            .pressable(onClick = onClick, enabled = enabled)
            .then(if (filled) Modifier.background(colors.accentHorizontal, PillShape) else Modifier.background(colors.surfaceHigh, PillShape))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = EmberTheme.type.label, color = if (filled) colors.onAccent else colors.textPrimary)
    }
}
