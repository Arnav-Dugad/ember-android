package io.github.arnavdugad.ember.ui.game

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.rememberReduceMotion
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.GameMove
import io.github.arnavdugad.ember.data.model.GameMoveType
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.model.Reactions
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Group Truth or Dare. Invitees see the invite and nothing else; members see the table: who's
 * playing (first name and first photo only), whose turn it is, the card as it flips, every answer
 * with its reactions, and a light chat.
 */
@Composable
fun GroupGameScreen(gameId: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<GroupGameViewModel, GroupGameViewModel.Factory>(key = gameId) { it.create(gameId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val moves by viewModel.moves.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val myUid by viewModel.myUid.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    LaunchedEffect(ui.closed) { if (ui.closed) onBack() }

    val game = state.game
    val live = game?.isLive() == true
    val member = game != null && myUid in game.members
    LaunchedEffect(game?.id, live) { if (game != null && !live) viewModel.cleanUp() }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            GameTopBar(
                title = "Truth or Dare",
                subtitle = game?.let { subtitleFor(it) } ?: "Group game",
                onBack = onBack,
                trailing = if (live && member) {
                    { GlassIconButton(Icons.Rounded.MoreHoriz, contentDescription = "Game options", onClick = { menuOpen = true }) }
                } else {
                    null
                },
            )
            when {
                state.loading -> LoadingStage()
                game == null -> GameOver("This game is over", "The host ended it, or you're no longer in it.", onBack)
                !live -> GameOver(
                    if (game.ended) "${game.host.name} ended the game" else "This game went quiet",
                    "Games close after 24 hours without a move, and are then deleted.",
                    onBack,
                )
                myUid in game.invited -> InviteStage(game, myUid, ui.busy, onJoin = viewModel::accept, onDecline = viewModel::decline)
                else -> PlayStage(game, moves, myUid, ui.busy, viewModel)
            }
        }

        ui.error?.let { error ->
            LaunchedEffect(error) {
                delay(3_000)
                viewModel.clearError()
            }
            GlassSurface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 80.dp, start = 20.dp, end = 20.dp),
                shape = PillShape,
                style = GlassDefaults.chrome(),
                elevated = true,
            ) {
                Text(error, style = EmberTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }

        if (game != null) {
            GameMenuSheet(
                visible = menuOpen && live && member,
                game = game,
                myUid = myUid,
                onSkip = {
                    viewModel.skip()
                    menuOpen = false
                },
                onRemove = viewModel::remove,
                onLeave = viewModel::leave,
                onEnd = viewModel::end,
                onDismiss = { menuOpen = false },
            )
        }
    }
}

private fun subtitleFor(game: GroupGame): String {
    val playing = "${game.members.size} playing"
    return if (game.invited.isEmpty()) playing else "$playing · ${game.invited.size} invited"
}

/** Back, a title and subtitle, and an optional action, on chrome glass. */
@Composable
internal fun GameTopBar(title: String, subtitle: String, onBack: () -> Unit, trailing: (@Composable () -> Unit)? = null) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RectangleShape, GlassDefaults.chrome(), LocalHazeState.current)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Text(subtitle, style = EmberTheme.type.caption, color = colors.textTertiary)
        }
        trailing?.invoke()
    }
}

@Composable
private fun LoadingStage() {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.fillMaxWidth().height(80.dp).shimmer(EmberShapes.tile))
        Box(Modifier.fillMaxWidth().height(190.dp).shimmer(EmberShapes.panel))
    }
}

@Composable
private fun GameOver(title: String, body: String, onBack: () -> Unit) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmberFlame(size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text(title, style = EmberTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(body, style = EmberTheme.type.bodySmall, color = colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        GlassButton("Back", onClick = onBack)
    }
}

/** What an invitee sees: who asked, the others' first names, and what joining shares. Nothing more. */
@Composable
private fun InviteStage(game: GroupGame, myUid: String, busy: Boolean, onJoin: () -> Unit, onDecline: () -> Unit) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        PlayerAvatar(game.host, size = 104.dp)
        Spacer(Modifier.height(18.dp))
        Text(GroupGames.inviteTitle(game, myUid), style = EmberTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("With " + GroupGames.othersLine(game, myUid), style = EmberTheme.type.body, color = colors.textSecondary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(18.dp))
        Text(
            "If you join, the players see your first name and first photo. Nothing else from your profile is shared, " +
                "and you can leave at any time.",
            style = EmberTheme.type.bodySmall,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Not now", onClick = onDecline, enabled = !busy, modifier = Modifier.weight(1f))
            EmberButton("Join", onClick = onJoin, loading = busy, icon = Icons.Rounded.Casino, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PlayStage(game: GroupGame, moves: List<GameMove>, myUid: String, busy: Boolean, viewModel: GroupGameViewModel) {
    var reactingId by remember { mutableStateOf<String?>(null) }
    var chat by rememberSaveable { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "players") { PlayersRow(game, myUid) }
            item(key = "turn") { TurnBanner(game, myUid) }
            item(key = "stage") {
                CardStage(game, myUid, busy, onPick = viewModel::pick, onAnswer = viewModel::answer, onPass = viewModel::pass)
            }
            if (moves.isNotEmpty()) {
                item(key = "feedHeader") { SectionHeader("At the table", Modifier.padding(top = 10.dp)) }
            }
            items(moves, key = { it.id }) { move ->
                MoveItem(
                    move = move,
                    game = game,
                    myUid = myUid,
                    reacting = reactingId == move.id,
                    onLongPress = { reactingId = if (reactingId == move.id) null else move.id },
                    onReact = { emoji ->
                        viewModel.react(move, emoji)
                        reactingId = null
                    },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        ChatBar(
            text = chat,
            onText = { chat = it },
            onSend = {
                viewModel.sendChat(chat)
                chat = ""
            },
        )
    }
}

/** Everyone in or invited, in turn order. The current player wears a turning gradient ring. */
@Composable
private fun PlayersRow(game: GroupGame, myUid: String) {
    val colors = EmberTheme.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        (game.members + game.invited).forEach { uid ->
            val player = game.player(uid)
            val joined = uid in game.members
            val active = uid == game.turnUid
            val scale by animateFloatAsState(if (active) 1.08f else 1f, spring(dampingRatio = 0.55f, stiffness = 400f), label = "turnScale")
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(76.dp)) {
                Box(
                    Modifier
                        .size(64.dp)
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (active) TurnRing(Modifier.matchParentSize())
                    PlayerAvatar(player, size = 54.dp, modifier = Modifier.alpha(if (joined) 1f else 0.45f))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (uid == myUid) "You" else player.name,
                    style = EmberTheme.type.caption,
                    color = if (active) colors.textPrimary else colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        !joined -> "Invited"
                        uid == game.hostUid -> "Host"
                        else -> " "
                    },
                    style = EmberTheme.type.overline,
                    color = colors.textTertiary,
                )
            }
        }
    }
}

@Composable
private fun TurnRing(modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val reduceMotion = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "turnRing")
    val spin by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(2_400, easing = LinearEasing)), label = "spin")
    val brush = remember(colors) { Brush.sweepGradient(colors.gradient + colors.gradient.first()) }
    Box(
        modifier.drawBehind {
            rotate(if (reduceMotion) 0f else spin) {
                drawCircle(brush, radius = size.minDimension / 2f - 2.dp.toPx(), style = Stroke(width = 3.dp.toPx()))
            }
        },
    )
}

/** "Your turn" / "Maya's turn", sliding in when it changes; and a buzz when it becomes yours. */
@Composable
private fun TurnBanner(game: GroupGame, myUid: String) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val mine = game.turnUid == myUid
    LaunchedEffect(game.turnNo, mine) { if (mine && game.members.size > 1) haptics.confirm() }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            targetState = GroupGames.turnLabel(game, myUid),
            transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) },
            label = "turn",
        ) { label ->
            Box(
                Modifier
                    .then(if (mine) Modifier.background(colors.accentHorizontal, PillShape) else Modifier.glass(PillShape, GlassDefaults.surface(), LocalHazeState.current))
                    .padding(horizontal = 18.dp, vertical = 8.dp),
            ) {
                Text(label, style = EmberTheme.type.label, color = if (mine) colors.onAccent else colors.textPrimary)
            }
        }
        Spacer(Modifier.height(6.dp))
        val hoursLeft = GroupGames.timeLeft(game.updatedAt, Instant.now()).toHours()
        Text(
            "Turn ${game.turnNo + 1} · closes in ${if (hoursLeft < 1) "under an hour" else "$hoursLeft h"} if it goes quiet",
            style = EmberTheme.type.caption,
            color = colors.textTertiary,
        )
    }
}

/**
 * The card. On your turn with no card yet: Truth or Dare. Otherwise the card itself, face down
 * until it's drawn, then flipping over for everyone; on your turn, the answer box under it.
 */
@Composable
private fun CardStage(
    game: GroupGame,
    myUid: String,
    busy: Boolean,
    onPick: (GameMode) -> Unit,
    onAnswer: (String) -> Unit,
    onPass: () -> Unit,
) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val myTurn = game.turnUid == myUid
    val card = game.card
    val turnName = game.player(game.turnUid).name
    Column(Modifier.fillMaxWidth()) {
        if (card == null && myTurn) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GameMode.entries.forEach { mode ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(120.dp)
                            .pressable(onClick = {
                                haptics.tick()
                                onPick(mode)
                            }, enabled = !busy)
                            .glass(EmberShapes.panel, GlassDefaults.surface(), LocalHazeState.current),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(if (mode == GameMode.Truth) "🤫" else "🔥", fontSize = 30.sp)
                            Spacer(Modifier.height(6.dp))
                            Text(mode.label, style = EmberTheme.type.title, color = colors.textPrimary)
                        }
                    }
                }
            }
        } else {
            FlipCard(card = card, flipKey = game.turnNo, waitingLabel = "Waiting for $turnName to pick…")
            Spacer(Modifier.height(12.dp))
            when {
                card != null && myTurn -> AnswerBox(game.turnNo, card, busy, onAnswer, onPass)
                card != null -> Text(
                    "$turnName is answering…",
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
        }
    }
}

/** Face down (gradient back) until [card] arrives, then a 3D flip to the prompt. */
@Composable
private fun FlipCard(card: GameCard?, flipKey: Int, waitingLabel: String) {
    val colors = EmberTheme.colors
    val reduceMotion = rememberReduceMotion()
    val rotation = remember(flipKey) { Animatable(180f) }
    LaunchedEffect(flipKey, card != null) {
        when {
            card == null -> rotation.snapTo(180f)
            reduceMotion -> rotation.snapTo(0f)
            else -> rotation.animateTo(0f, spring(dampingRatio = 0.72f, stiffness = 140f))
        }
    }
    val faceUp by remember { derivedStateOf { rotation.value <= 90f } }
    Box(
        Modifier
            .fillMaxWidth()
            .height(190.dp)
            .graphicsLayer {
                rotationY = rotation.value
                cameraDistance = 16f * density
            },
    ) {
        if (faceUp && card != null) {
            GlassSurface(Modifier.fillMaxSize(), shape = EmberShapes.panel, elevated = true) {
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.Center) {
                    ModePill(card.mode)
                    Spacer(Modifier.height(12.dp))
                    Text(card.prompt, style = EmberTheme.type.title, color = colors.textPrimary)
                }
            }
        } else {
            // Seen through the flip, so it's mirrored back to read the right way round.
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { rotationY = 180f }
                    .background(colors.accentBrush, EmberShapes.panel),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Truth or Dare", style = EmberTheme.type.headline, color = colors.onAccent)
                    if (card == null) {
                        Spacer(Modifier.height(6.dp))
                        Text(waitingLabel, style = EmberTheme.type.caption, color = colors.onAccent.copy(alpha = 0.85f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ModePill(mode: GameMode) {
    Box(Modifier.background(EmberTheme.colors.accentHorizontal, PillShape).padding(horizontal = 10.dp, vertical = 3.dp)) {
        Text(mode.label.uppercase(), style = EmberTheme.type.overline, color = EmberTheme.colors.onAccent)
    }
}

@Composable
private fun AnswerBox(turnNo: Int, card: GameCard, busy: Boolean, onAnswer: (String) -> Unit, onPass: () -> Unit) {
    var text by rememberSaveable(turnNo) { mutableStateOf("") }
    Column {
        EmberTextField(
            value = text,
            onValueChange = { text = it },
            placeholder = if (card.mode == GameMode.Truth) "Tell the truth…" else "Do it, then tell them how it went…",
            singleLine = false,
            minLines = 2,
            maxLength = GroupGames.MAX_TEXT,
            imeAction = ImeAction.Default,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Pass", onClick = onPass, enabled = !busy, icon = Icons.Rounded.SkipNext, modifier = Modifier.weight(1f))
            EmberButton(
                "Answer",
                onClick = { onAnswer(text) },
                enabled = text.isNotBlank(),
                loading = busy,
                icon = Icons.AutoMirrored.Rounded.Send,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** An answer (with its card) or a chat line; long-press either to react. */
@Composable
private fun MoveItem(
    move: GameMove,
    game: GroupGame,
    myUid: String,
    reacting: Boolean,
    onLongPress: () -> Unit,
    onReact: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = EmberTheme.colors
    val sender = game.players[move.senderId]
    val name = when {
        move.senderId == myUid -> "You"
        sender != null -> sender.name
        else -> "Someone who left"
    }
    Column(modifier.fillMaxWidth().alpha(if (move.pending) 0.6f else 1f)) {
        val press = Modifier.pointerInput(move.id) { detectTapGestures(onLongPress = { onLongPress() }) }
        if (move.type == GameMoveType.Answer) {
            GlassSurface(Modifier.fillMaxWidth().then(press), shape = EmberShapes.tile) {
                Column(Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        sender?.let { PlayerAvatar(it, size = 26.dp) }
                        Spacer(Modifier.width(8.dp))
                        Text(name, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
                        move.card?.let { ModePill(it.mode) }
                    }
                    move.card?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it.prompt, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                    }
                    Spacer(Modifier.height(6.dp))
                    if (move.pass) {
                        Text("Passed 🙈", style = EmberTheme.type.body.copy(fontStyle = FontStyle.Italic), color = colors.textTertiary)
                    } else {
                        Text(move.text, style = EmberTheme.type.body, color = colors.textPrimary)
                    }
                    ReactionCounts(move.reactions, myUid)
                }
            }
        } else {
            val mine = move.senderId == myUid
            Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                if (!mine) Text(name, style = EmberTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(start = 12.dp, bottom = 2.dp))
                Box(
                    Modifier
                        .widthIn(max = 300.dp)
                        .then(press)
                        .then(if (mine) Modifier.background(colors.accentHorizontal, EmberShapes.bubble) else Modifier.background(colors.surfaceHigh, EmberShapes.bubble))
                        .padding(horizontal = 14.dp, vertical = 9.dp),
                ) {
                    Text(move.text, style = EmberTheme.type.body, color = if (mine) colors.onAccent else colors.textPrimary)
                }
                ReactionCounts(move.reactions, myUid)
            }
        }
        AnimatedVisibility(reacting, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            ReactionPicker(move.reactions[myUid], onReact)
        }
    }
}

/** "🔥 2  😂 1", with my own reaction highlighted. */
@Composable
private fun ReactionCounts(reactions: Map<String, String>, myUid: String) {
    if (reactions.isEmpty()) return
    val colors = EmberTheme.colors
    val mine = reactions[myUid]
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        reactions.values.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }.forEach { (emoji, count) ->
            Box(
                Modifier
                    .background(if (emoji == mine) colors.accent.copy(alpha = 0.28f) else colors.surfaceHigh, PillShape)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(if (count > 1) "$emoji $count" else emoji, style = EmberTheme.type.caption, color = colors.textPrimary)
            }
        }
    }
}

/** The same five reactions as the 1:1 chat, popping in one after another. */
@Composable
private fun ReactionPicker(current: String?, onReact: (String) -> Unit) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .padding(top = 6.dp)
            .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Reactions.all.forEachIndexed { index, emoji ->
            val pop = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(index * 28L)
                pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
            }
            Box(
                Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }
                    .pressable(onClick = { onReact(emoji) }, pressedScale = 0.8f)
                    .then(if (emoji == current) Modifier.background(colors.accent.copy(alpha = 0.28f), CircleShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 22.sp)
            }
        }
    }
}

@Composable
private fun ChatBar(text: String, onText: (String) -> Unit, onSend: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RectangleShape, GlassDefaults.chrome(), LocalHazeState.current)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EmberTextField(
            value = text,
            onValueChange = onText,
            placeholder = "Say something to the table…",
            maxLength = GroupGames.MAX_TEXT,
            imeAction = ImeAction.Send,
            onImeAction = { if (text.isNotBlank()) onSend() },
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        GlassIconButton(
            Icons.AutoMirrored.Rounded.Send,
            contentDescription = "Send",
            onClick = { if (text.isNotBlank()) onSend() },
            tint = if (text.isNotBlank()) EmberTheme.colors.accent else EmberTheme.colors.textTertiary,
        )
    }
}

/**
 * Game options. The host can skip a quiet player, take someone out, or end the game for everyone;
 * anyone else can leave. Ending and leaving ask for a second tap.
 */
@Composable
private fun GameMenuSheet(
    visible: Boolean,
    game: GroupGame,
    myUid: String,
    onSkip: () -> Unit,
    onRemove: (String) -> Unit,
    onLeave: () -> Unit,
    onEnd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EmberTheme.colors
    val host = game.hostUid == myUid
    var confirming by remember(visible) { mutableStateOf(false) }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Game options", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text(
            "Players see only each other's first name and first photo. A game closes after 24 hours without a move.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        if (host) {
            if (game.turnUid != myUid) {
                GlassButton(
                    "Skip ${game.player(game.turnUid).name}'s turn",
                    onClick = onSkip,
                    icon = Icons.Rounded.SkipNext,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
            }
            (game.members + game.invited).filter { it != myUid }.forEach { uid ->
                val player = game.player(uid)
                Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    PlayerAvatar(player, size = 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        player.name + if (uid in game.invited) " · invited" else "",
                        style = EmberTheme.type.body,
                        color = colors.textPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    SmallPill("Take out", filled = false, enabled = true, onClick = { onRemove(uid) })
                }
            }
            Spacer(Modifier.height(12.dp))
            GlassButton(
                if (confirming) "Tap again to end it for everyone" else "End game",
                onClick = { if (confirming) onEnd() else confirming = true },
                contentColor = colors.danger,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            GlassButton(
                if (confirming) "Tap again to leave" else "Leave game",
                onClick = { if (confirming) onLeave() else confirming = true },
                contentColor = colors.danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
