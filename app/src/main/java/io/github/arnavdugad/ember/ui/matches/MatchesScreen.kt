package io.github.arnavdugad.ember.ui.matches

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.ScreenTitle
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.ui.main.BottomBarSpace
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Duration
import java.time.Instant
import javax.inject.Inject

@HiltViewModel
class MatchesViewModel @Inject constructor(matches: MatchRepository) : ViewModel() {
    /** Null while the first snapshot loads. */
    val matches: StateFlow<List<Match>?> = matches.observeMatches()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unreadCount: StateFlow<Int> = this.matches
        .map { list -> list.orEmpty().count { it.hasUnread || it.lastMessage == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
}

@Composable
fun MatchesScreen(viewModel: MatchesViewModel, onOpenChat: (String) -> Unit) {
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val fresh = matches.orEmpty().filter { it.lastMessage == null }
    val conversations = matches.orEmpty().filter { it.lastMessage != null }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.7f) }) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = BottomBarSpace + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { ScreenTitle("Matches", subtitle = "Say something bold. They already like you.") }
            when {
                matches == null -> items(4) { Box(Modifier.fillMaxWidth().height(76.dp).shimmer(EmberShapes.tile)) }
                matches!!.isEmpty() -> item { EmptyMatches() }
                else -> {
                    if (fresh.isNotEmpty()) {
                        item { SectionHeader("New matches · ${fresh.size}", Modifier.padding(top = 12.dp)) }
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(fresh, key = { it.id }) { match -> NewMatchBubble(match) { onOpenChat(match.id) } }
                            }
                        }
                    }
                    if (conversations.isNotEmpty()) {
                        item { SectionHeader("Messages", Modifier.padding(top = 12.dp)) }
                        items(conversations, key = { it.id }) { match -> ConversationRow(match) { onOpenChat(match.id) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun NewMatchBubble(match: Match, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp).pressable(onClick = onClick)) {
        Box(
            Modifier
                .size(74.dp)
                .border(2.5.dp, Brush.sweepGradient(colors.gradient + colors.gradient.first()), CircleShape)
                .padding(4.dp)
                .clip(CircleShape),
        ) {
            EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = match.otherName, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(6.dp))
        Text(match.otherName, style = EmberTheme.type.caption, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ConversationRow(match: Match, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val last = match.lastMessage
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(54.dp).clip(CircleShape)) {
                EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = match.otherName, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(match.otherName, style = EmberTheme.type.titleSmall, color = colors.textPrimary, maxLines = 1)
                    if (match.otherIsDemo) {
                        Spacer(Modifier.width(8.dp))
                        DemoBadge()
                    }
                    Spacer(Modifier.weight(1f))
                    Text(relativeTime(last?.sentAt), style = EmberTheme.type.caption, color = colors.textTertiary)
                }
                val preview = when (last?.type) {
                    MessageType.Image -> "📷 Photo"
                    MessageType.Game -> "🎲 Truth or Dare"
                    else -> last?.text.orEmpty()
                }
                val mine = last?.senderId != match.otherUid
                Text(
                    (if (mine) "You: " else "") + preview,
                    style = EmberTheme.type.bodySmall,
                    color = if (match.hasUnread) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (match.hasUnread) {
                Spacer(Modifier.width(10.dp))
                Box(Modifier.size(10.dp).background(colors.accentBrush, CircleShape))
            }
        }
    }
}

@Composable
private fun EmptyMatches() {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmberFlame(size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text("No matches yet", style = EmberTheme.type.title, color = colors.textPrimary)
        Spacer(Modifier.height(6.dp))
        Text(
            "Keep swiping. When someone likes you back, they'll show up here.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
    }
}

internal fun relativeTime(instant: Instant?): String {
    if (instant == null) return ""
    val d = Duration.between(instant, Instant.now())
    return when {
        d.toMinutes() < 1 -> "now"
        d.toHours() < 1 -> "${d.toMinutes()}m"
        d.toDays() < 1 -> "${d.toHours()}h"
        d.toDays() < 7 -> "${d.toDays()}d"
        else -> "${d.toDays() / 7}w"
    }
}
