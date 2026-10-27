package io.github.arnavdugad.ember.ui.chat

import android.net.Uri
import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
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
import io.github.arnavdugad.ember.core.designsystem.rememberReduceMotion
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.ai.AiConfig
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.ui.main.sharedPhoto
import io.github.arnavdugad.ember.ui.safety.SafetySheet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

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
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val wingman by viewModel.wingman.collectAsStateWithLifecycle()
    val prefs by viewModel.prefs.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current
    var draft by rememberSaveable { mutableStateOf("") }
    var gameOpen by remember { mutableStateOf(false) }
    var dateOpen by remember { mutableStateOf(false) }
    var safetyOpen by remember { mutableStateOf(false) }
    var optionsOpen by remember { mutableStateOf(false) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchIndex by remember { mutableIntStateOf(0) }
    var pendingPhoto by rememberSaveable { mutableStateOf<Uri?>(null) }
    val revealed = remember { mutableStateListOf<String>() }
    var toast by remember { mutableStateOf<String?>(null) }
    var reacting by remember { mutableStateOf<ReactionTarget?>(null) }
    var viewing by remember { mutableStateOf<Message?>(null) }
    var info by remember { mutableStateOf<Message?>(null) }
    var highlightId by remember { mutableStateOf<String?>(null) }
    var bannerDismissedFor by rememberSaveable { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> pendingPhoto = uri }
    val voice = rememberVoicePlayer(onError = viewModel::showError)
    LaunchedEffect(ui.removed) { if (ui.removed) onRemoved() }

    val m = match
    // Event rows only show while screenshot alerts are on: the switch works both ways.
    val list = remember(messages, prefs.screenshotAlerts) {
        messages.orEmpty().let { all -> if (prefs.screenshotAlerts) all else all.filter { it.type != MessageType.Event } }
    }
    val otherUid = m?.otherUid
    val myUid = m?.myUid.orEmpty()
    val arrivals = remember { BubbleArrivals() }
    val reduceMotion = rememberReduceMotion()
    // Noted during composition, so a new bubble already knows to pop on its first frame.
    remember(messages, list) { messages?.let { arrivals.observe(list.map(Message::id), SystemClock.uptimeMillis()) } }

    // Follow new messages while at the bottom, and always after sending one.
    val newest = list.firstOrNull()
    LaunchedEffect(newest?.id) {
        val sentByMe = newest != null && newest.senderId != otherUid && newest.type != MessageType.Event
        if (listState.firstVisibleItemIndex <= 2 || sentByMe) listState.animateScrollToItem(0)
    }

    // Typing dots -> message: remember when the dots were last up, and let the next incoming
    // message that lands within the window grow out of them instead of popping in.
    var typingSeen by remember { mutableStateOf(false) }
    var typingSeenAt by remember { mutableLongStateOf(0L) }
    var typingSince by remember { mutableLongStateOf(0L) }
    LaunchedEffect(typing) {
        if (typing) {
            typingSeen = true
            typingSince = SystemClock.uptimeMillis()
        }
        if (typingSeen) typingSeenAt = SystemClock.uptimeMillis()
    }
    var morphId by remember { mutableStateOf<String?>(null) }
    val newestIncoming = newest?.takeIf { it.senderId == otherUid && it.type != MessageType.Event }?.id
    LaunchedEffect(newestIncoming) {
        if (newestIncoming != null && typingSeen && (typing || SystemClock.uptimeMillis() - typingSeenAt < MORPH_WINDOW_MS)) {
            morphId = newestIncoming
            delay(1_000)
            morphId = null
        }
    }
    val showTyping = typing && (morphId == null || newest?.id != morphId)

    // Safety nudge: the newest scam-shaped message from them, unless dismissed.
    val risky = remember(list, otherUid) {
        list.asSequence().take(NUDGE_SCAN).firstOrNull { it.senderId == otherUid && !it.unsent && SafetyNudges.isRisky(it.text) }?.id
    }

    val unsentIds = remember(list) { list.filter { it.unsent }.mapTo(HashSet()) { it.id } }
    val replyingTo = ui.replyTo?.id
    LaunchedEffect(unsentIds, replyingTo) { if (replyingTo != null && replyingTo in unsentIds) viewModel.cancelReply() }
    // Photos from someone I haven't answered yet arrive blurred (all of them, with the switch on).
    val firstReply = remember(list, myUid) { firstReplyAt(list, myUid) }

    // Scrolling to a message: quotes, search results. Item 0 is the typing bubble while it shows.
    val currentList by rememberUpdatedState(list)
    val typingShown by rememberUpdatedState(showTyping)
    val jumpTo: (String) -> Unit = remember {
        { id ->
            val index = currentList.indexOfFirst { it.id == id }
            if (index < 0) {
                toast = "That message is too far back"
            } else {
                scope.launch {
                    listState.animateScrollToItem(index + if (typingShown) 1 else 0)
                    highlightId = id
                    delay(800)
                    highlightId = null
                }
            }
        }
    }

    // In-chat search: newest match first; the arrows walk back and forth through the rest.
    val found = remember(list, query, searchOpen) { if (searchOpen) ChatSearch.matches(list, query) else emptyList() }
    val currentFound by rememberUpdatedState(found)
    LaunchedEffect(query, searchOpen) {
        searchIndex = 0
        if (!searchOpen) return@LaunchedEffect
        delay(250)
        currentFound.firstOrNull()?.let(jumpTo)
    }
    val closeSearch = {
        searchOpen = false
        query = ""
    }
    BackHandler(enabled = searchOpen, onBack = closeSearch)
    fun stepSearch(by: Int) {
        if (found.isEmpty()) return
        searchIndex = (searchIndex + by).coerceIn(0, found.lastIndex)
        jumpTo(found[searchIndex])
    }

    // Scrolled up: a button back to the latest, counting what arrived since.
    val scrolledUp by remember { derivedStateOf { listState.firstVisibleItemIndex > 2 } }
    var anchorId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(scrolledUp) { anchorId = if (scrolledUp) currentList.firstOrNull()?.id else null }
    val unseen = if (scrolledUp) unseenSince(list, anchorId, otherUid) else 0

    ScreenshotWatcher(enabled = prefs.screenshotAlerts && m != null, onScreenshot = { viewModel.screenshotTaken() })

    val actions = remember(viewModel) {
        BubbleActions(
            onReveal = { revealed += it.id },
            onLongPress = { message, bounds ->
                val mine = message.senderId != viewModel.match.value?.otherUid
                reacting = ReactionTarget(message, bounds, mine)
            },
            onReply = viewModel::replyTo,
            onReact = viewModel::react,
            onHeart = viewModel::heart,
            onQuoteClick = jumpTo,
            onOpenViewOnce = { viewing = it },
            onPlayVoice = { message -> voice.toggle(message.id) { viewModel.voiceFile(message) } },
            onQuickReply = viewModel::quickReply,
        )
    }

    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.5f) }) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 110.dp, bottom = 12.dp),
                    // reverseLayout needs bottom alignment, or a short thread hugs the top.
                    verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.Bottom),
                ) {
                    if (showTyping) {
                        // Vanishes at once: when a message replaces it, that message grows out of the dots.
                        item(key = "typing") {
                            TypingBubble(Modifier.animateItem(fadeOutSpec = null).padding(top = 8.dp), startedAt = typingSince, still = reduceMotion)
                        }
                    }
                    val lastMineId = list.firstOrNull { it.senderId != otherUid && it.type != MessageType.Event && !it.unsent }?.id
                    itemsIndexed(list, key = { _, it -> it.id }) { index, message ->
                        val mine = message.senderId != otherUid
                        // The list is newest-first, so the message shown above this one is index + 1.
                        val previous = list.getOrNull(index + 1)
                        val gap = if (previous?.createdAt != null && message.createdAt != null) {
                            java.time.Duration.between(previous.createdAt, message.createdAt)
                        } else {
                            null
                        }
                        val newDay = startsNewDay(previous?.createdAt, message.createdAt, zone)
                        val showTime = !newDay && (previous == null || (gap != null && gap.toMinutes() >= 60))
                        val grouped = !newDay && !showTime && previous != null && previous.senderId == message.senderId &&
                            previous.type != MessageType.Event && message.type != MessageType.Event && (gap == null || gap.toMinutes() < 3)
                        MessageBubble(
                            message = message,
                            mine = mine,
                            myUid = myUid,
                            otherName = m?.otherName.orEmpty(),
                            revealed = mine || message.id in revealed || !veilsPhoto(message.createdAt, firstReply, prefs.blurAllPhotos),
                            receipt = when {
                                mine && message.pending && message.type != MessageType.Event -> "Sending…"
                                message.id == lastMineId && m != null -> receiptFor(message, m)
                                else -> null
                            },
                            voice = voice,
                            actions = actions,
                            dayLabel = if (newDay) message.createdAt?.let { "${dayLabel(it.atZone(zone).toLocalDate(), today)} · ${clockTime(it)}" } else null,
                            timeLabel = if (showTime) message.createdAt?.let(::clockTime) else null,
                            topGap = if (grouped) 0.dp else 8.dp,
                            morphFromTyping = message.id == morphId,
                            highlighted = message.id == highlightId,
                            // Offer the quick answers until I've said anything after their suggestion.
                            showDateReplies = !mine && message.type == MessageType.Date && list.subList(0, index).none { it.senderId != otherUid },
                            // New arrivals pop, unless theirs is about to grow out of the typing dots.
                            popIn = !reduceMotion && remember(message.id) {
                                arrivals.consume(message.id) && (mine || !typingSeen || !typing && SystemClock.uptimeMillis() - typingSeenAt >= MORPH_WINDOW_MS)
                            },
                            searchQuery = if (searchOpen) query else null,
                            quoteUnsent = message.replyTo?.let { it.id in unsentIds } == true,
                            reduceMotion = reduceMotion,
                            modifier = Modifier.animateItem(
                                placementSpec = spring(dampingRatio = 0.85f, stiffness = 420f, visibilityThreshold = IntOffset.VisibilityThreshold),
                            ),
                        )
                    }
                    if (messages == null) {
                        item(key = "loading") { LoadingBubbles() }
                    } else if (list.isEmpty() && m != null) {
                        item { ChatIntro(m, ai, onPlay = { gameOpen = true }, onAiSettings = onAiSettings) }
                    }
                }
                ScrollToLatest(
                    visible = scrolledUp,
                    unseen = unseen,
                    onClick = { scope.launch { listState.animateScrollToItem(0) } },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 10.dp),
                )
            }
            if (ai.usable && m != null) {
                WingmanChip(onClick = viewModel::suggestReplies)
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

        // Faded rather than removed while searching, so its photo keeps its shared-element link.
        val topBarAlpha by animateFloatAsState(if (searchOpen) 0f else 1f, label = "topBar")
        Box(Modifier.graphicsLayer { alpha = topBarAlpha }.then(if (searchOpen) Modifier.clearAndSetSemantics {} else Modifier)) {
            ChatTopBar(
                m,
                ai,
                typing,
                onBack = onBack,
                onProfile = { m?.let { onOpenProfile(it.otherUid) } },
                onSearch = { searchOpen = true },
                onMenu = { optionsOpen = true },
            )
        }
        AnimatedVisibility(
            visible = searchOpen,
            enter = fadeIn() + slideInVertically { -it / 3 },
            exit = fadeOut() + slideOutVertically { -it / 3 },
        ) {
            ChatSearchBar(
                query = query,
                onQuery = { query = it },
                found = found.size,
                current = searchIndex,
                onOlder = { stepSearch(1) },
                onNewer = { stepSearch(-1) },
                onClose = closeSearch,
            )
        }

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
            val message = target.message
            val close = { reacting = null }
            val menu = buildList {
                add(
                    MenuAction("Reply", Icons.AutoMirrored.Rounded.Reply) {
                        viewModel.replyTo(message)
                        close()
                    },
                )
                copyText(message)?.let { text ->
                    add(
                        MenuAction("Copy", Icons.Rounded.ContentCopy) {
                            scope.launch { clipboard.setClipEntry(android.content.ClipData.newPlainText("message", text).toClipEntry()) }
                            toast = "Copied"
                            close()
                        },
                    )
                }
                add(
                    MenuAction("Message info", Icons.Rounded.Info) {
                        info = message
                        close()
                    },
                )
                if (viewModel.canUnsend(message)) {
                    add(
                        MenuAction("Unsend", Icons.Rounded.DeleteOutline, destructive = true) {
                            viewModel.unsend(message)
                            close()
                        },
                    )
                }
            }
            ReactionOverlay(
                target = target,
                myReaction = message.reactions[myUid],
                onReact = { emoji ->
                    viewModel.react(message, emoji)
                    close()
                },
                menu = menu,
                onDismiss = close,
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
        ChatOptionsSheet(
            visible = optionsOpen,
            name = m?.otherName ?: "your match",
            prefs = prefs,
            receiptsOn = matchId !in prefs.receiptsOff,
            onSearch = {
                optionsOpen = false
                searchOpen = true
            },
            onReadReceipts = viewModel::setReadReceipts,
            onBlurAllPhotos = viewModel::setBlurAllPhotos,
            onScreenshotAlerts = viewModel::setScreenshotAlerts,
            onSafety = {
                optionsOpen = false
                safetyOpen = true
            },
            onDismiss = { optionsOpen = false },
        )
        MessageInfoSheet(
            message = info,
            myUid = myUid,
            otherName = m?.otherName ?: "your match",
            otherLastRead = m?.otherLastRead,
            onDismiss = { info = null },
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
        WingmanSheet(
            state = wingman,
            name = m?.otherName.orEmpty(),
            onPick = { idea ->
                draft = idea
                viewModel.onTyping(idea)
                viewModel.closeWingman()
            },
            onRetry = viewModel::suggestReplies,
            onDismiss = viewModel::closeWingman,
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

/** A round "back to the latest" button, with how many of their messages arrived since. */
@Composable
private fun ScrollToLatest(visible: Boolean, unseen: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    AnimatedVisibility(visible, modifier, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) {
        Box {
            GlassSurface(
                Modifier
                    .size(44.dp)
                    .pressable(onClick = onClick, pressedScale = 0.88f)
                    .semantics { contentDescription = if (unseen > 0) "$unseen new, jump to the latest message" else "Jump to the latest message" },
                shape = CircleShape,
                style = GlassDefaults.chrome(),
                elevated = true,
            ) {
                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = null, tint = colors.textPrimary, modifier = Modifier.align(Alignment.Center).size(24.dp))
            }
            if (unseen > 0) {
                Text(
                    if (unseen > 99) "99+" else "$unseen",
                    style = EmberTheme.type.caption,
                    color = colors.onAccent,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-6).dp)
                        .background(colors.accentBrush, PillShape)
                        .padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
        }
    }
}

/** Over the top bar while searching: the field, "2 of 5", and arrows to older and newer matches. */
@Composable
private fun ChatSearchBar(query: String, onQuery: (String) -> Unit, found: Int, current: Int, onOlder: () -> Unit, onNewer: () -> Unit, onClose: () -> Unit) {
    val colors = EmberTheme.colors
    val focus = remember { FocusRequester() }
    // Opens with the keyboard up; harmless if the field isn't attached yet.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Row(
        Modifier
            .fillMaxWidth()
            .glass(RectangleShape, GlassDefaults.chrome(), LocalHazeState.current)
            .statusBarsPadding()
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Close search", onClick = onClose)
        Spacer(Modifier.width(10.dp))
        Row(
            Modifier
                .weight(1f)
                .height(42.dp)
                .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = EmberTheme.type.body.copy(color = colors.textPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onOlder() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    Box {
                        if (query.isEmpty()) Text("Search this chat", style = EmberTheme.type.body, color = colors.textTertiary)
                        inner()
                    }
                },
            )
            if (query.isNotBlank()) {
                Text(
                    if (found == 0) "No results" else "${current + 1} of $found",
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        GlassIconButton(Icons.Rounded.KeyboardArrowUp, contentDescription = "Older match", onClick = onOlder, size = 40.dp)
        Spacer(Modifier.width(4.dp))
        GlassIconButton(Icons.Rounded.KeyboardArrowDown, contentDescription = "Newer match", onClick = onNewer, size = 40.dp)
    }
}

/** Placeholder bubbles while the thread loads. */
@Composable
private fun LoadingBubbles() {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics { contentDescription = "Loading messages" }) {
        listOf(160 to false, 210 to true, 120 to false, 180 to true).forEach { (width, mine) ->
            Box(Modifier.fillMaxWidth().padding(vertical = 4.dp), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(width = width.dp, height = 40.dp).shimmer(EmberShapes.bubble))
            }
        }
    }
}

@Composable
private fun ChatTopBar(match: Match?, ai: AiConfig, typing: Boolean, onBack: () -> Unit, onProfile: () -> Unit, onSearch: () -> Unit, onMenu: () -> Unit) {
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
                    if (match?.otherIsDemo == true && !ai.hidesDemo) {
                        Spacer(Modifier.width(8.dp))
                        DemoBadge()
                    }
                }
                Text(
                    when {
                        match?.otherIsDemo != true || ai.hidesDemo -> if (typing) "typing…" else "Tap to view profile"
                        ai.ready -> "Demo profile · replies with AI"
                        else -> "Demo profile · won't reply"
                    },
                    style = EmberTheme.type.caption,
                    color = colors.textTertiary,
                )
            }
        }
        GlassIconButton(Icons.Rounded.Search, contentDescription = "Search in chat", onClick = onSearch)
        Spacer(Modifier.width(8.dp))
        GlassIconButton(Icons.Rounded.MoreHoriz, contentDescription = "Chat options", onClick = onMenu)
    }
}

@Composable
private fun ChatIntro(match: Match, ai: AiConfig, onPlay: () -> Unit, onAiSettings: () -> Unit) {
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
                !match.otherIsDemo || ai.hidesDemo -> "Skip the \"hey\". Ask about a prompt, or let the game do the flirting."
                ai.ready -> "${match.otherName} is a demo profile. They'll reply in character with your AI key."
                else -> "${match.otherName} is a demo profile and won't reply. Add an AI key and they'll text back in character."
            },
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        GlassButton("Play Truth or Dare", onClick = onPlay, icon = Icons.Rounded.Casino)
        if (match.otherIsDemo && !ai.ready) {
            Spacer(Modifier.height(10.dp))
            GlassButton("Set up AI replies", onClick = onAiSettings, icon = Icons.Rounded.AutoAwesome, contentColor = colors.accent)
        }
    }
}

/** "9:41 PM": the day itself is on the separator above. */
private fun clockTime(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("h:mm a"))
