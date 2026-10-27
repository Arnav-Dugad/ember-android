package io.github.arnavdugad.ember.ui.chat

import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GradientText
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.core.image.rememberBlurHashPainter
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.ui.safety.SafetySheet

@Composable
fun ChatScreen(matchId: String, onBack: () -> Unit, onOpenProfile: (String) -> Unit, onRemoved: () -> Unit) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = matchId) { it.create(matchId) }
    val match by viewModel.match.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val typing by viewModel.otherTyping.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var draft by rememberSaveable { mutableStateOf("") }
    var gameOpen by remember { mutableStateOf(false) }
    var safetyOpen by remember { mutableStateOf(false) }
    val revealed = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(viewModel::sendImage) }
    LaunchedEffect(ui.removed) { if (ui.removed) onRemoved() }
    LaunchedEffect(messages?.firstOrNull()?.id) { if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }

    val m = match
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.5f) }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 110.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (typing) item(key = "typing") { TypingBubble() }
                val list = messages.orEmpty()
                val lastMineId = list.firstOrNull { it.senderId != m?.otherUid }?.id
                items(list, key = { it.id }) { message ->
                    val mine = message.senderId != m?.otherUid
                    MessageBubble(
                        message = message,
                        mine = mine,
                        revealed = mine || message.id in revealed,
                        onReveal = { revealed += message.id },
                        receipt = if (message.id == lastMineId && m != null) receiptFor(message, m) else null,
                        modifier = Modifier.animateItem(),
                    )
                }
                if (messages != null && list.isEmpty() && m != null) {
                    item { ChatIntro(m, onPlay = { gameOpen = true }) }
                }
            }
            Composer(
                text = draft,
                onText = {
                    draft = it
                    viewModel.onTyping(it)
                },
                uploading = ui.uploading,
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onGame = { gameOpen = true },
            )
        }

        ChatTopBar(m, onBack = onBack, onProfile = { m?.let { onOpenProfile(it.otherUid) } }, onMenu = { safetyOpen = true })

        ui.error?.let { error ->
            LaunchedEffect(error) {
                kotlinx.coroutines.delay(3_000)
                viewModel.clearError()
            }
            GlassSurface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 80.dp),
                shape = PillShape,
                style = GlassDefaults.chrome(),
                elevated = true,
            ) {
                Text(error, style = EmberTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }

        TruthOrDareSheet(
            visible = gameOpen,
            draw = viewModel::drawCard,
            onSend = {
                viewModel.sendCard(it)
                gameOpen = false
            },
            onDismiss = { gameOpen = false },
        )
        SafetySheet(
            visible = safetyOpen,
            name = m?.otherName.orEmpty(),
            matched = true,
            busy = ui.busy,
            onDismiss = { safetyOpen = false },
            onReport = { reason, details, block ->
                viewModel.report(reason, details, block)
                safetyOpen = false
            },
            onBlock = viewModel::block,
            onUnmatch = viewModel::unmatch,
        )
    }
}

private fun receiptFor(message: Message, match: Match): String = when {
    message.pending -> "Sending…"
    match.otherIsDemo -> "Delivered"
    message.createdAt != null && match.otherLastRead != null && !match.otherLastRead.isBefore(message.createdAt) -> "Read"
    else -> "Delivered"
}

@Composable
private fun ChatTopBar(match: Match?, onBack: () -> Unit, onProfile: () -> Unit, onMenu: () -> Unit) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .glass(androidx.compose.ui.graphics.RectangleShape, GlassDefaults.chrome(), LocalHazeState.current)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).pressable(onClick = onProfile, pressedScale = 0.98f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape)) {
                EmberImage(match?.otherPhotoUrl, match?.otherBlurHash, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(match?.otherName.orEmpty(), style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                    if (match?.otherIsDemo == true) {
                        Spacer(Modifier.width(8.dp))
                        DemoBadge()
                    }
                }
                Text(
                    if (match?.otherIsDemo == true) "Demo profile · won't reply" else "Tap to view profile",
                    style = EmberTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }
        GlassIconButton(Icons.Rounded.MoreHoriz, contentDescription = "Safety options", onClick = onMenu)
    }
}

@Composable
private fun MessageBubble(
    message: Message,
    mine: Boolean,
    revealed: Boolean,
    onReveal: () -> Unit,
    receipt: String?,
    modifier: Modifier = Modifier,
) {
    val colors = EmberTheme.colors
    Column(modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        when (message.type) {
            MessageType.Game -> GameBubble(message.game ?: GameCard(GameMode.Truth, message.text), mine)
            MessageType.Image -> ImageBubble(message, revealed, onReveal)
            MessageType.Text -> Box(
                Modifier
                    .widthIn(max = 290.dp)
                    .then(
                        if (mine) {
                            Modifier.background(colors.accentBrush, EmberShapes.bubble)
                        } else {
                            Modifier.glass(EmberShapes.bubble, GlassDefaults.surface(), LocalHazeState.current)
                        },
                    )
                    .padding(horizontal = 16.dp, vertical = 11.dp),
            ) {
                Text(message.text, style = EmberTheme.type.body, color = if (mine) Color.White else colors.textPrimary)
            }
        }
        if (receipt != null) {
            Text(receipt, style = EmberTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 3.dp, end = 6.dp))
        }
    }
}

/**
 * Images arrive veiled: a BlurHash (no real pixels) until the recipient chooses to look. Below
 * Android 12, where a real-time blur isn't available, a hash-less image stays fully hidden.
 */
@Composable
private fun ImageBubble(message: Message, revealed: Boolean, onReveal: () -> Unit) {
    val colors = EmberTheme.colors
    val placeholder = rememberBlurHashPainter(message.imageBlurHash)
    Box(
        Modifier
            .size(width = 220.dp, height = 280.dp)
            .clip(EmberShapes.bubble)
            .background(colors.surfaceHigh)
            .pressable(onClick = onReveal, enabled = !revealed, pressedScale = 0.97f),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = revealed, transitionSpec = { fadeIn(tween(400)) togetherWith fadeOut(tween(200)) }, label = "reveal") { show ->
            when {
                show -> AsyncImage(message.imageUrl, contentDescription = "Photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                placeholder != null -> Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && message.imageUrl != null -> AsyncImage(
                    message.imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize().blur(48.dp),
                )
                else -> Box(Modifier.fillMaxSize().background(colors.accentBrush, alpha = 0.5f))
            }
        }
        if (!revealed) {
            Row(
                Modifier.glass(PillShape, GlassDefaults.overPhoto(), LocalHazeState.current).padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Tap to reveal", style = EmberTheme.type.caption, color = Color.White)
            }
        }
        if (message.imageUrl == null && revealed) CircularProgressIndicator(color = colors.accent)
    }
}

@Composable
private fun GameBubble(card: GameCard, mine: Boolean) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.widthIn(max = 300.dp), shape = EmberShapes.panel, style = GlassDefaults.chrome()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Casino, contentDescription = null, tint = colors.accent, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                GradientText(card.mode.label.uppercase(), style = EmberTheme.type.overline)
            }
            Spacer(Modifier.height(8.dp))
            Text(card.prompt, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.height(8.dp))
            Text(
                if (mine) "Waiting for their answer…" else "Your turn. Answer below, or pass with a wink.",
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
            )
        }
    }
}

@Composable
private fun TypingBubble() {
    val colors = EmberTheme.colors
    val transition = rememberInfiniteTransition(label = "typing")
    Row(
        Modifier
            .glass(EmberShapes.bubble, GlassDefaults.surface(), LocalHazeState.current)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(420, delayMillis = i * 140), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        translationY = -lift * 5.dp.toPx()
                        alpha = 0.45f + lift * 0.55f
                    }
                    .background(colors.textSecondary, CircleShape),
            )
        }
    }
}

@Composable
private fun ChatIntro(match: Match, onPlay: () -> Unit) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(110.dp).clip(CircleShape)) {
            EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("You matched with ${match.otherName}", style = EmberTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            if (match.otherIsDemo) "${match.otherName} is a demo profile and won't reply, but everything here works for testing."
            else "Skip the \"hey\". Ask about a prompt, or let the game do the flirting.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        GlassButton("Play Truth or Dare", onClick = onPlay, icon = Icons.Rounded.Casino)
    }
}

@Composable
private fun Composer(text: String, onText: (String) -> Unit, uploading: Boolean, onSend: () -> Unit, onPhoto: () -> Unit, onGame: () -> Unit) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp)
            .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ComposerIcon(Icons.Rounded.Casino, "Truth or Dare", onGame)
        if (uploading) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        } else {
            ComposerIcon(Icons.Rounded.Image, "Send a photo", onPhoto)
        }
        BasicTextField(
            value = text,
            onValueChange = onText,
            textStyle = EmberTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            maxLines = 5,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
            decorationBox = { inner ->
                Box {
                    if (text.isEmpty()) Text("Message", style = EmberTheme.type.body, color = colors.textTertiary)
                    inner()
                }
            },
        )
        AnimatedVisibility(text.isNotBlank(), enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
            Box(
                Modifier
                    .size(42.dp)
                    .pressable(onClick = onSend, pressedScale = 0.85f)
                    .background(colors.accentBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ComposerIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).pressable(onClick = onClick, pressedScale = 0.85f), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = EmberTheme.colors.textSecondary, modifier = Modifier.size(22.dp))
    }
}

/** Truth or Dare: pick a side, get a card, shuffle until it feels right, send. */
@Composable
private fun TruthOrDareSheet(visible: Boolean, draw: (GameMode) -> GameCard, onSend: (GameCard) -> Unit, onDismiss: () -> Unit) {
    var card by remember(visible) { mutableStateOf<GameCard?>(null) }
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Truth or Dare", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text("Flirty, never pushy. Either of you can pass.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            GameMode.entries.forEach { mode ->
                val selected = card?.mode == mode
                Box(
                    Modifier
                        .weight(1f)
                        .height(92.dp)
                        .pressable(onClick = {
                            haptics.tick()
                            card = draw(mode)
                        })
                        .glass(EmberShapes.panel, GlassDefaults.surface(), LocalHazeState.current)
                        .background(colors.accentBrush, EmberShapes.panel, alpha = if (selected) 1f else 0f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(mode.label, style = EmberTheme.type.title, color = if (selected) Color.White else colors.textPrimary)
                }
            }
        }
        AnimatedContent(
            targetState = card,
            transitionSpec = { (slideInVertically { it / 3 } + fadeIn()) togetherWith fadeOut() },
            label = "card",
        ) { current ->
            if (current == null) {
                Spacer(Modifier.height(8.dp))
            } else {
                Column {
                    Spacer(Modifier.height(16.dp))
                    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                        Text(current.prompt, style = EmberTheme.type.title, color = colors.textPrimary, modifier = Modifier.padding(20.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        GlassButton("Shuffle", onClick = { card = draw(current.mode) }, icon = Icons.Rounded.Shuffle, modifier = Modifier.weight(1f))
                        EmberButton("Send", onClick = { onSend(current) }, icon = Icons.AutoMirrored.Rounded.Send, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
