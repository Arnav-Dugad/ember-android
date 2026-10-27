package io.github.arnavdugad.ember.ui.chat

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.ai.AiProvider
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.ui.main.sharedPhoto
import io.github.arnavdugad.ember.ui.safety.SafetySheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How many messages count as "early on" for the phone-number hint. */
private const val EARLY_MESSAGES = 5

/** A message arriving this soon after the typing dots vanished takes over their bubble. */
private const val MORPH_WINDOW_MS = 2_000L

/** Only recent messages are scanned for the safety nudge. */
private const val NUDGE_SCAN = 30

@Composable
fun ChatScreen(matchId: String, onBack: () -> Unit, onOpenProfile: (String) -> Unit, onRemoved: () -> Unit, onAiSettings: () -> Unit) {
    val viewModel = hiltViewModel<ChatViewModel, ChatViewModel.Factory>(key = matchId) { it.create(matchId) }
    val match by viewModel.match.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val typing by viewModel.otherTyping.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val aiProvider by viewModel.aiProvider.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var draft by rememberSaveable { mutableStateOf("") }
    var gameOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var safetyOpen by remember { mutableStateOf(false) }
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    val revealed = remember { mutableStateListOf<String>() }
    var toast by remember { mutableStateOf<String?>(null) }
    var reacting by remember { mutableStateOf<ReactionTarget?>(null) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var bannerDismissedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pendingPhoto = uri }
    val voice = rememberVoicePlayer(onError = viewModel::showError)
    LaunchedEffect(ui.removed) { if (ui.removed) onRemoved() }
    LaunchedEffect(messages?.firstOrNull()?.id) { if (listState.firstVisibleItemIndex <= 2) listState.animateScrollToItem(0) }

    val m = match
    val list = messages.orEmpty()
    val otherUid = m?.otherUid
    val myUid = m?.myUid.orEmpty()

    // Typing dots -> message: remember when the dots were last up, and let the next incoming
    // message that lands within the window grow out of them instead of popping in.
    var typingSeen by remember { mutableStateOf(false) }
    var typingSeenAt by remember { mutableLongStateOf(0L) }
    LaunchedEffect(typing) {
        if (typing) typingSeen = true
        if (typingSeen) typingSeenAt = SystemClock.uptimeMillis()
    }
    var morphId by remember { mutableStateOf<String?>(null) }
    val newestIncoming = list.firstOrNull()?.takeIf { it.senderId == otherUid }?.id
    LaunchedEffect(newestIncoming) {
        if (newestIncoming != null && typingSeen && (typing || SystemClock.uptimeMillis() - typingSeenAt < MORPH_WINDOW_MS)) {
            morphId = newestIncoming
            delay(1_000)
            morphId = null
        }
    }
    val showTyping = typing && (morphId == null || list.firstOrNull()?.id != morphId)

    // Safety nudge: the newest scam-shaped message from them, unless dismissed.
    val risky = remember(list, otherUid) {
        list.asSequence().take(NUDGE_SCAN).firstOrNull { it.senderId == otherUid && SafetyNudges.isRisky(it.text) }?.id
    }

    val actions = remember(viewModel) {
        BubbleActions(
            onReveal = { revealed += it.id },
            onLongPress = { message, bounds ->
                val mine = message.senderId != viewModel.match.value?.otherUid
                reacting = ReactionTarget(message, bounds, mine)
            },
            onReply = viewModel::replyTo,
            onReact = viewModel::react,
            onQuoteClick = { id ->
                val all = viewModel.messages.value.orEmpty()
                val index = all.indexOfFirst { it.id == id }
                if (index < 0) {
                    toast = "That message is too far back"
                } else {
                    scope.launch {
                        // Item 0 is the typing bubble while it shows.
                        val offset = if (listState.layoutInfo.visibleItemsInfo.any { it.key == "typing" }) 1 else 0
                        listState.animateScrollToItem(index + offset)
                        highlightId = id
                        delay(800)
                        highlightId = null
                    }
                }
            },
            onOpenViewOnce = { viewing = it },
            onPlayVoice = { message -> voice.toggle(message.id) { viewModel.voiceFile(message) } },
            onQuickReply = viewModel::quickReply,
        )
    }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.5f) }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            LazyColumn(
                state = listState,
                reverseLayout = true,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 110.dp, bottom = 12.dp),
                // reverseLayout needs bottom alignment, or a short thread hugs the top.
                verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
            ) {
                if (showTyping) {
                    // Vanishes at once: when a message replaces it, that message grows out of the dots.
                    item(key = "typing") { TypingBubble(Modifier.animateItem(fadeOutSpec = null)) }
                }
                val lastMineId = list.firstOrNull { it.senderId != otherUid }?.id
                itemsIndexed(list, key = { _, it -> it.id }) { index, message ->
                    val mine = message.senderId != otherUid
                    // The list is newest-first, so the message shown above this one is index + 1.
                    val previous = list.getOrNull(index + 1)
                    val gap = if (previous?.createdAt != null && message.createdAt != null) {
                        java.time.Duration.between(previous.createdAt, message.createdAt)
                    } else {
                        null
                    }
                    val showTime = previous == null || (gap != null && gap.toMinutes() >= 60)
                    val grouped = !showTime && previous.senderId == message.senderId && (gap == null || gap.toMinutes() < 3)
                    MessageBubble(
                        message = message,
                        mine = mine,
                        myUid = myUid,
                        otherName = m?.otherName.orEmpty(),
                        revealed = mine || message.id in revealed,
                        receipt = when {
                            mine && message.pending -> "Sending…"
                            message.id == lastMineId && m != null -> receiptFor(message, m)
                            else -> null
                        },
                        voice = voice,
                        actions = actions,
                        timeLabel = if (showTime) message.createdAt?.let(::chatTime) else null,
                        topGap = if (grouped) 0.dp else 8.dp,
                        morphFromTyping = message.id == morphId,
                        highlighted = message.id == highlightId,
                        // Offer the quick answers until I've said anything after their suggestion.
                        showDateReplies = !mine && message.type == MessageType.Date && list.subList(0, index).none { it.senderId != otherUid },
                        modifier = Modifier.animateItem(
                            placementSpec = spring(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold),
                        ),
                    )
                }
                if (messages != null && list.isEmpty() && m != null) {
                    item { ChatIntro(m, aiProvider, onPlay = { gameOpen = true }, onAiSettings = onAiSettings) }
                }
            }
            Composer(
                text = draft,
                onText = {
                    draft = it
                    viewModel.onTyping(it)
                },
                uploading = ui.uploading,
                extras = ComposerExtras(
                    replyTo = ui.replyTo,
                    replyAuthor = if (ui.replyTo?.senderId == otherUid) m?.otherName.orEmpty() else "yourself",
                    onCancelReply = viewModel::cancelReply,
                    safetyBanner = risky != null && risky != bannerDismissedFor,
                    onDismissBanner = { bannerDismissedFor = risky },
                    onReport = { safetyOpen = true },
                    phoneHint = list.size < EARLY_MESSAGES && SafetyNudges.containsPhoneNumber(draft),
                ),
                onSend = {
                    viewModel.send(draft)
                    draft = ""
                },
                onPhoto = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onGame = { gameOpen = true },
                onPlanDate = { dateOpen = true },
                onVoice = viewModel::sendVoice,
                onNotice = { toast = it },
            )
        }

        ChatTopBar(m, aiProvider, onBack = onBack, onProfile = { m?.let { onOpenProfile(it.otherUid) } }, onMenu = { safetyOpen = true })

        ui.error?.let { error ->
            LaunchedEffect(error) {
                delay(3_000)
                viewModel.clearError()
            }
            Notice(error, colors.danger, Modifier.align(Alignment.TopCenter))
        }

        toast?.let { text ->
            LaunchedEffect(text) {
                delay(1_600)
                toast = null
            }
            Notice(text, colors.textPrimary, Modifier.align(Alignment.TopCenter))
        }

        reacting?.let { target ->
            val text = target.message.text.takeIf { target.message.type == MessageType.Text }
            ReactionOverlay(
                target = target,
                myReaction = target.message.reactions[myUid],
                onReact = { emoji ->
                    viewModel.react(target.message, emoji)
                    reacting = null
                },
                onReply = {
                    viewModel.replyTo(target.message)
                    reacting = null
                },
                onCopy = text?.let {
                    {
                        scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("message", it).toClipEntry()) }
                        toast = "Copied"
                        reacting = null
                    }
                },
                onDismiss = { reacting = null },
            )
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
        DatePlannerSheet(
            visible = dateOpen,
            otherName = m?.otherName ?: "your match",
            suggest = viewModel::suggestDates,
            onSend = {
                viewModel.sendDate(it)
                dateOpen = false
            },
            onDismiss = { dateOpen = false },
        )
        PhotoSendSheet(
            uri = pendingPhoto,
            onSend = { uri, viewOnce ->
                viewModel.sendImage(uri, viewOnce)
                pendingPhoto = null
            },
            onDismiss = { pendingPhoto = null },
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

        viewing?.let { message ->
            ViewOnceViewer(message, onClose = { viewing = null }, onGone = { shown -> if (shown) viewModel.viewOnceClosed(message) })
        }
    }
}

@Composable
private fun Notice(text: String, color: androidx.compose.ui.graphics.Color, modifier: Modifier = Modifier) {
    GlassSurface(
        modifier.statusBarsPadding().padding(top = 80.dp),
        shape = PillShape,
        style = GlassDefaults.chrome(),
        elevated = true,
    ) {
        Text(text, style = EmberTheme.type.bodySmall, color = color, modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp))
    }
}

private fun receiptFor(message: Message, match: Match): String = when {
    message.pending -> "Sending…"
    message.createdAt != null && match.otherLastRead != null && !match.otherLastRead.isBefore(message.createdAt) -> "Read"
    else -> "Delivered"
}

@Composable
private fun ChatTopBar(match: Match?, aiProvider: AiProvider?, onBack: () -> Unit, onProfile: () -> Unit, onMenu: () -> Unit) {
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
        Spacer(Modifier.width(10.dp))
        Row(Modifier.weight(1f).pressable(onClick = onProfile, pressedScale = 0.98f), verticalAlignment = Alignment.CenterVertically) {
            // Flies into the profile's hero photo (same key as the profile screen and the deck).
            Box(
                Modifier
                    .size(42.dp)
                    .then(if (match != null) Modifier.sharedPhoto("photo-${match.otherUid}") else Modifier)
                    .clip(CircleShape),
            ) {
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
                    when {
                        match?.otherIsDemo != true -> "Tap to view profile"
                        aiProvider != null -> "Demo profile · AI replies with ${aiProvider.label}"
                        else -> "Demo profile · won't reply"
                    },
                    style = EmberTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }
        GlassIconButton(Icons.Rounded.MoreHoriz, contentDescription = "Safety options", onClick = onMenu)
    }
}

@Composable
private fun ChatIntro(match: Match, aiProvider: AiProvider?, onPlay: () -> Unit, onAiSettings: () -> Unit) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(vertical = 40.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(110.dp).clip(CircleShape)) {
            EmberImage(match.otherPhotoUrl, match.otherBlurHash, contentDescription = null, modifier = Modifier.fillMaxSize())
        }
        Spacer(Modifier.height(16.dp))
        Text("You matched with ${match.otherName}", style = EmberTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                !match.otherIsDemo -> "Skip the \"hey\". Ask about a prompt, or let the game do the flirting."
                aiProvider != null -> "${match.otherName} is a demo profile. ${aiProvider.label} replies in character, using your own key."
                else -> "${match.otherName} is a demo profile and won't reply. Add an AI key and they'll text back in character."
            },
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        GlassButton("Play Truth or Dare", onClick = onPlay, icon = Icons.Rounded.Casino)
        if (match.otherIsDemo && aiProvider == null) {
            Spacer(Modifier.height(10.dp))
            GlassButton("Set up AI replies", onClick = onAiSettings, icon = Icons.Rounded.AutoAwesome, contentColor = colors.accent)
        }
    }
}

/** "9:41 PM" today, "Tue 9:41 PM" this week, "12 Oct, 9:41 PM" before that. */
private fun chatTime(instant: java.time.Instant): String {
    val zone = java.time.ZoneId.systemDefault()
    val at = instant.atZone(zone)
    val today = java.time.LocalDate.now(zone)
    val pattern = when {
        at.toLocalDate() == today -> "h:mm a"
        at.toLocalDate().isAfter(today.minusDays(6)) -> "EEE h:mm a"
        else -> "d MMM, h:mm a"
    }
    return at.format(java.time.format.DateTimeFormatter.ofPattern(pattern))
}
