package io.github.arnavdugad.ember.ui.matches

import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
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
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.OnlineDot
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.ScreenTitle
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Presence
import io.github.arnavdugad.ember.data.repo.LikeNoteCourier
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.ui.main.BottomBarSpace
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import javax.inject.Inject
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository

@HiltViewModel
class MatchesViewModel @Inject constructor(
    matches: MatchRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val unmatches: UnmatchQueue,
    ai: AiSettingsRepository,
    likeNotes: LikeNoteCourier,
) : ViewModel() {
    /** Null while the first snapshot loads. Matches waiting out their Undo window are already gone. */
    val matches: StateFlow<List<Match>?> = combine(matches.observeMatches().catch { emit(emptyList()) }, unmatches.pending) { list, pending ->
        val leaving = pending.mapTo(HashSet()) { it.matchId }
        list.filterNot { it.id in leaving }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** With AI chat in immersive mode, demo chats are listed like any other (no Demo badge). */
    val hideDemo: StateFlow<Boolean> = ai.config.map { it.hidesDemo }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)
    /**
     * Who among my matches is online now, refreshed every heartbeat while this list is on screen.
     * Empty while I hide my own activity (then I don't see anyone's).
     */
    val online: StateFlow<Set<String>> = combine(
        this.matches.map { list -> list.orEmpty().map { it.otherUid }.toSet() }.distinctUntilChanged(),
        profiles.observeMyProfile().map { it?.hideActivity == true }.catch { emit(false) }.distinctUntilChanged(),
    ) { uids, hidden -> if (hidden) emptySet() else uids }
        .flatMapLatest { uids ->
            if (uids.isEmpty()) {
                flowOf(emptySet())
            } else {
                flow {
                    while (true) {
                        val seen = runCatching { profiles.lastActive(uids) }.getOrDefault(emptyMap())
                        val now = Instant.now()
                        emit(seen.filterValues { Presence.isOnline(it, now) }.keys)
                        delay(Presence.HEARTBEAT.toMillis())
                    }
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    init {
        // Lives with the main screen, so a note on my like reaches the chat even if they matched me.
        viewModelScope.launch { this@MatchesViewModel.matches.filterNotNull().collect { likeNotes.deliverPending(it) } }
    }

    /** Archived conversations: a device-local list, the other person is never told. */
    val archived: StateFlow<Set<String>> = settings.archivedMatches
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    val unreadCount: StateFlow<Int> = this.matches
        .map { list -> list.orEmpty().count { it.hasUnread || it.lastMessage == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    /** The most recent unmatch that can still be undone. */
    val pendingUnmatch: StateFlow<PendingUnmatch?> = unmatches.pending.map { it.lastOrNull() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val unmatchFailures: SharedFlow<String> = unmatches.failures

    fun unmatch(match: Match) = unmatches.unmatch(match.id, match.otherName)

    fun undoUnmatch(matchId: String) = unmatches.undo(matchId)

    fun setArchived(matchId: String, archived: Boolean) {
        viewModelScope.launch { settings.setArchived(matchId, archived) }
    }
}

/** [groupGames] is the group Truth or Dare invites and games, shown under the title. */
@Composable
fun MatchesScreen(
    viewModel: MatchesViewModel,
    onOpenChat: (String) -> Unit,
    onNewGroupGame: (() -> Unit)? = null,
    groupGames: @Composable () -> Unit = {},
) {
    val matches by viewModel.matches.collectAsStateWithLifecycle()
    val archivedIds by viewModel.archived.collectAsStateWithLifecycle()
    val hideDemo by viewModel.hideDemo.collectAsStateWithLifecycle()
    val online by viewModel.online.collectAsStateWithLifecycle()
    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    val all = matches.orEmpty()
    val archived = all.filter { it.id in archivedIds }
    val active = all.filterNot { it.id in archivedIds }
    val fresh = active.filter { it.lastMessage == null }
    val conversations = active.filter { it.lastMessage != null }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.7f) }) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = BottomBarSpace + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.Top) {
                    ScreenTitle("Matches", Modifier.weight(1f), subtitle = "Say something bold. They already like you.")
                    onNewGroupGame?.let { GlassIconButton(Icons.Rounded.Groups, contentDescription = "Group game", onClick = it) }
                }
            }
            item(key = "groupGames") { groupGames() }
            when {
                matches == null -> items(4) { Box(Modifier.fillMaxWidth().height(76.dp).shimmer(EmberShapes.tile)) }
                all.isEmpty() -> item { EmptyMatches() }
                else -> {
                    if (fresh.isNotEmpty()) {
                        item(key = "freshHeader") { SectionHeader("New matches · ${fresh.size}", Modifier.padding(top = 12.dp)) }
                        item(key = "fresh") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                                items(fresh, key = { it.id }) { match -> NewMatchBubble(match, online = match.otherUid in online) { onOpenChat(match.id) } }
                            }
                        }
                    }
                    if (conversations.isNotEmpty()) {
                        item(key = "messagesHeader") { SectionHeader("Messages", Modifier.padding(top = 12.dp)) }
                        items(conversations, key = { it.id }) { match ->
                            SwipeActionsRow(
                                actions = listOf(
                                    RowAction(Icons.Rounded.Archive, "Archive") { viewModel.setArchived(match.id, true) },
                                    RowAction(Icons.Rounded.HeartBroken, "Unmatch", danger = true) { viewModel.unmatch(match) },
                                ),
                                onClick = { onOpenChat(match.id) },
                                modifier = Modifier.animateItem(),
                            ) { ConversationRow(match, hideDemo, online = match.otherUid in online) }
                        }
                    }
                    if (archived.isNotEmpty()) {
                        item(key = "archivedHeader") {
                            ArchivedHeader(
                                count = archived.size,
                                unread = archived.any { it.hasUnread },
                                open = archiveOpen,
                                onToggle = { archiveOpen = !archiveOpen },
                                modifier = Modifier.animateItem(),
                            )
                        }
                        if (archiveOpen) {
                            items(archived, key = { it.id }) { match ->
                                SwipeActionsRow(
                                    actions = listOf(
                                        RowAction(Icons.Rounded.Unarchive, "Unarchive") { viewModel.setArchived(match.id, false) },
                                        RowAction(Icons.Rounded.HeartBroken, "Unmatch", danger = true) { viewModel.unmatch(match) },
                                    ),
                                    onClick = { onOpenChat(match.id) },
                                    modifier = Modifier.animateItem(),
                                ) { ConversationRow(match, hideDemo, online = match.otherUid in online) }
                            }
                        }
                    }
                }
            }
        }
    }
}

private class RowAction(val icon: ImageVector, val label: String, val danger: Boolean = false, val onClick: () -> Unit)

/**
 * Swipe a row left to reveal its actions; a tick marks the point where letting go keeps them
 * open. Tapping an open row closes it instead of opening the chat.
 */
@Composable
private fun SwipeActionsRow(
    actions: List<RowAction>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val scope = rememberCoroutineScope()
    val reveal = with(LocalDensity.current) { (actions.size * 72).dp.toPx() }
    val offset = remember { Animatable(0f) }
    var armed by remember { mutableStateOf(false) }
    fun settle(open: Boolean) {
        scope.launch { offset.animateTo(if (open) -reveal else 0f, spring(dampingRatio = 0.8f, stiffness = 500f)) }
    }
    Box(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .matchParentSize()
                .graphicsLayer { alpha = (-offset.value / reveal).coerceIn(0f, 1f) },
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions.forEach { action ->
                Column(
                    Modifier.width(72.dp).pressable(onClick = {
                        settle(open = false)
                        action.onClick()
                    }),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        Modifier
                            .size(46.dp)
                            .background(if (action.danger) colors.danger.copy(alpha = 0.9f) else colors.surfaceHigh, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(action.icon, contentDescription = action.label, tint = if (action.danger) Color.White else colors.textPrimary)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(action.label, style = EmberTheme.type.caption, color = colors.textSecondary)
                }
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        val next = (offset.value + delta).coerceIn(-reveal * 1.15f, 0f)
                        val nowArmed = next < -reveal / 2
                        if (nowArmed != armed) {
                            armed = nowArmed
                            haptics.tick()
                        }
                        scope.launch { offset.snapTo(next) }
                    },
                    onDragStopped = { velocity -> settle(open = velocity < -1_200f || (velocity < 1_200f && offset.value < -reveal / 2)) },
                )
                .pressable(onClick = { if (offset.value < 0f) settle(open = false) else onClick() }, pressedScale = 0.98f),
        ) {
            content()
        }
    }
}

@Composable
private fun ArchivedHeader(count: Int, unread: Boolean, open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val rotation by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
    Row(
        modifier.fillMaxWidth().padding(top = 12.dp).pressable(onClick = onToggle, pressedScale = 0.98f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader("Archived · $count", Modifier.weight(1f))
        if (unread) {
            Box(Modifier.padding(end = 8.dp).size(8.dp).background(colors.accentBrush, CircleShape))
        }
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = if (open) "Hide archived" else "Show archived",
            tint = colors.textTertiary,
            modifier = Modifier.graphicsLayer { rotationZ = rotation },
        )
    }
}

@Composable
private fun NewMatchBubble(match: Match, online: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(78.dp).pressable(onClick = onClick)) {
        Box(contentAlignment = Alignment.BottomCenter) {
            Box(
                Modifier
                    .size(74.dp)
                    .border(2.5.dp, Brush.sweepGradient(colors.gradient + colors.gradient.first()), CircleShape)
                    .padding(4.dp)
                    .clip(CircleShape),
            ) {
                EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = match.otherName, modifier = Modifier.fillMaxSize())
            }
            Badge("NEW", Modifier.offset(y = 6.dp))
            if (online) OnlineDot(Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = 2.dp), size = 12.dp, ring = colors.background)
        }
        Spacer(Modifier.height(10.dp))
        Text(match.otherName, style = EmberTheme.type.caption, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A small gradient pill: "NEW" on fresh matches, "Your turn" when they wrote last. */
@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(EmberTheme.colors.accentHorizontal, PillShape).padding(horizontal = 7.dp, vertical = 2.dp)) {
        Text(text, style = EmberTheme.type.overline, color = EmberTheme.colors.onAccent)
    }
}

@Composable
private fun ConversationRow(match: Match, hideDemo: Boolean, online: Boolean) {
    val colors = EmberTheme.colors
    val last = match.lastMessage
    val theirTurn = last != null && last.senderId == match.otherUid
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Box(Modifier.size(54.dp).clip(CircleShape)) {
                    EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = match.otherName, modifier = Modifier.fillMaxSize())
                }
                if (online) OnlineDot(Modifier.align(Alignment.BottomEnd).offset(x = 4.dp, y = 4.dp), size = 11.dp, ring = colors.surface)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        match.otherName,
                        style = EmberTheme.type.titleSmall,
                        color = colors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (match.otherIsDemo && !hideDemo) {
                        Spacer(Modifier.width(8.dp))
                        DemoBadge()
                    }
                    if (theirTurn) {
                        Spacer(Modifier.width(8.dp))
                        Badge("Your turn")
                    }
                    Spacer(Modifier.weight(1f))
                    Text(relativeTime(last?.sentAt), style = EmberTheme.type.caption, color = colors.textTertiary)
                }
                val preview = when (last?.type) {
                    MessageType.Image -> "📷 " + last.text
                    MessageType.Game -> "🎲 Truth or Dare"
                    MessageType.Voice -> "🎤 Voice note"
                    MessageType.Date -> "📅 " + last.text
                    else -> last?.text.orEmpty()
                }
                Text(
                    (if (last != null && !theirTurn) "You: " else "") + preview,
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

/**
 * "Unmatched Ana · Undo", with a bar that drains over the Undo window. Shown by the main
 * scaffold so it outlives the chat or profile screen the unmatch came from.
 */
@Composable
fun UnmatchSnackbar(viewModel: MatchesViewModel, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val pending by viewModel.pendingUnmatch.collectAsStateWithLifecycle()
    var failed by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(viewModel) { viewModel.unmatchFailures.collect { failed = it } }
    failed?.let {
        LaunchedEffect(it) {
            kotlinx.coroutines.delay(3_000)
            failed = null
        }
    }
    // Keeps the last one on screen while it animates away.
    val last = remember { arrayOfNulls<PendingUnmatch>(1) }
    pending?.let { last[0] = it }
    AnimatedVisibility(
        visible = pending != null || failed != null,
        enter = slideInVertically { it } + fadeIn(),
        exit = slideOutVertically { it } + fadeOut(),
        modifier = modifier,
    ) {
        val shown = last[0]
        GlassSurface(Modifier.padding(horizontal = 20.dp), shape = EmberShapes.tile, style = GlassDefaults.chrome(), elevated = true) {
            Column {
                Row(Modifier.padding(start = 18.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        failed?.let { "Couldn't unmatch $it. Try again." } ?: "Unmatched ${shown?.name.orEmpty()}",
                        style = EmberTheme.type.bodySmall,
                        color = if (failed != null) colors.danger else colors.textPrimary,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (failed == null && shown != null) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Undo",
                            style = EmberTheme.type.label,
                            color = colors.accent,
                            modifier = Modifier.pressable(onClick = { viewModel.undoUnmatch(shown.matchId) }).padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
                if (failed == null && shown != null) {
                    val drain = remember(shown.matchId) { Animatable(remainingFraction(shown)) }
                    LaunchedEffect(shown.matchId) {
                        drain.animateTo(0f, tween((drain.value * UnmatchQueue.WINDOW_MS).toInt(), easing = LinearEasing))
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(2.dp)
                            .graphicsLayer {
                                scaleX = drain.value
                                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
                            }
                            .background(colors.accentHorizontal),
                    )
                }
            }
        }
    }
}

private fun remainingFraction(pending: PendingUnmatch): Float =
    ((pending.deadline - SystemClock.elapsedRealtime()).toFloat() / UnmatchQueue.WINDOW_MS).coerceIn(0f, 1f)

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
