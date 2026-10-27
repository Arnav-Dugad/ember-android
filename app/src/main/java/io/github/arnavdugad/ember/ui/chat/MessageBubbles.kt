package io.github.arnavdugad.ember.ui.chat

import android.os.Build
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Screenshot
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GradientText
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.rememberBlurHashPainter
import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Reactions
import io.github.arnavdugad.ember.data.model.ReplyRef
import kotlinx.coroutines.launch
import kotlin.random.Random

/** Everything a bubble can ask the chat screen to do. Built once per screen, so bubbles skip recomposition. */
@Stable
internal class BubbleActions(
    val onReveal: (Message) -> Unit,
    val onLongPress: (Message, Rect) -> Unit,
    val onReply: (Message) -> Unit,
    val onReact: (Message, String) -> Unit,
    /** Double-tap: react with a heart. */
    val onHeart: (Message) -> Unit,
    val onQuoteClick: (String) -> Unit,
    val onOpenViewOnce: (Message) -> Unit,
    val onPlayVoice: (Message) -> Unit,
    val onQuickReply: (String) -> Unit,
)

/**
 * Decides which bubbles pop in: only messages that land at the newest end of the thread while
 * it's open. Whatever the first [SETTLE_MS] of loading brings (cache, then server) is history,
 * and older pages appended at the far end never count.
 */
internal class BubbleArrivals {
    private val seen = HashSet<String>()
    private val fresh = HashSet<String>()
    private var openedAt = -1L

    /** [ids] newest first, as the list shows them. Returns how many of them just arrived. */
    fun observe(ids: List<String>, now: Long): Int {
        if (openedAt < 0) openedAt = now
        var arrived = 0
        if (now - openedAt >= SETTLE_MS) {
            for (id in ids) {
                if (id in seen) break
                fresh += id
                arrived++
            }
        }
        seen += ids
        return arrived
    }

    /** True once for a message that should pop in; later calls (scrolled back into view) are false. */
    fun consume(id: String): Boolean = fresh.remove(id)

    companion object {
        const val SETTLE_MS = 800L
    }
}

/** The answers a date card offers its recipient. Sent as ordinary text messages. */
internal val DateQuickReplies = listOf("I'm in 🔥", "Suggest another?")

@Composable
internal fun MessageBubble(
    message: Message,
    mine: Boolean,
    myUid: String,
    otherName: String,
    revealed: Boolean,
    receipt: String?,
    voice: VoicePlayer,
    actions: BubbleActions,
    modifier: Modifier = Modifier,
    /** "Today · 9:41 PM": the first message of a day carries the day separator. */
    dayLabel: String? = null,
    timeLabel: String? = null,
    topGap: Dp = 8.dp,
    morphFromTyping: Boolean = false,
    highlighted: Boolean = false,
    showDateReplies: Boolean = false,
    popIn: Boolean = false,
    /** In-chat search: occurrences in text bubbles are highlighted. */
    searchQuery: String? = null,
    /** The message this one quotes has since been unsent. */
    quoteUnsent: Boolean = false,
    reduceMotion: Boolean = false,
) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val latestMessage by rememberUpdatedState(message)
    // Read on long-press only, so it lives outside snapshot state.
    val bounds = remember { arrayOf(Rect.Zero) }
    val drag = remember { Animatable(0f) }
    val pulse = remember { Animatable(1f) }
    LaunchedEffect(highlighted) {
        if (highlighted) {
            pulse.animateTo(1.07f, tween(140))
            pulse.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 400f))
        }
    }
    // A new arrival pops from its tail corner with a little overshoot.
    val pop = remember { Animatable(if (popIn) 0f else 1f) }
    LaunchedEffect(Unit) { if (popIn) pop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 420f)) }
    val trigger = with(density) { 60.dp.toPx() }
    val longPress = { haptics.confirm(); actions.onLongPress(latestMessage, bounds[0]) }
    // Double-tap: a heart springs up over the bubble and fades as the reaction chip lands.
    val heartScale = remember { Animatable(0f) }
    val heartAlpha = remember { Animatable(0f) }
    var hearting by remember { mutableStateOf(false) }
    val doubleTap = {
        haptics.confirm()
        actions.onHeart(latestMessage)
        if (!reduceMotion) {
            scope.launch {
                hearting = true
                heartAlpha.snapTo(1f)
                heartScale.snapTo(0.3f)
                heartScale.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = 520f))
                heartAlpha.animateTo(0f, tween(260, delayMillis = 120))
                hearting = false
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .then(
                if (popIn) {
                    Modifier.graphicsLayer {
                        val s = 0.85f + 0.15f * pop.value
                        scaleX = s
                        scaleY = s
                        alpha = (pop.value * 1.6f).coerceIn(0f, 1f)
                        transformOrigin = TransformOrigin(if (mine) 1f else 0f, 1f)
                    }
                } else {
                    Modifier
                },
            )
            .padding(top = topGap),
        horizontalAlignment = if (mine) Alignment.End else Alignment.Start,
    ) {
        when {
            dayLabel != null -> DaySeparator(dayLabel)
            timeLabel != null -> Text(
                timeLabel,
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 10.dp),
            )
        }
        if (message.type == MessageType.Event) {
            EventRow(message, mine, otherName)
            return@Column
        }
        if (message.unsent) {
            UnsentBubble(mine)
            return@Column
        }
        message.replyTo?.let { reply ->
            QuotedMessage(
                reply = reply,
                author = if (reply.senderId == myUid) "You" else otherName,
                unsent = quoteUnsent,
                onClick = { actions.onQuoteClick(reply.id) },
            )
        }
        // Swipe right to reply: the arrow fades in behind the bubble and a haptic marks the point
        // past which letting go replies.
        Box(contentAlignment = Alignment.CenterStart) {
            Icon(
                Icons.AutoMirrored.Rounded.Reply,
                contentDescription = null,
                tint = colors.accent,
                modifier = Modifier
                    .padding(start = 4.dp)
                    .size(22.dp)
                    .graphicsLayer {
                        val progress = (drag.value / trigger).coerceIn(0f, 1f)
                        alpha = progress
                        scaleX = 0.6f + 0.4f * progress
                        scaleY = 0.6f + 0.4f * progress
                    },
            )
            Box(
                Modifier
                    .graphicsLayer {
                        translationX = drag.value
                        scaleX = pulse.value
                        scaleY = pulse.value
                    }
                    .onGloballyPositioned { bounds[0] = it.boundsInRoot() }
                    // The gestures, spelled out for TalkBack.
                    .semantics(mergeDescendants = true) {
                        contentDescription = "${if (mine) "You" else otherName}: ${message.summary}"
                        customActions = listOf(
                            CustomAccessibilityAction("Reply") { actions.onReply(latestMessage); true },
                            CustomAccessibilityAction("React with a heart") { actions.onHeart(latestMessage); true },
                            CustomAccessibilityAction("More options") { longPress(); true },
                        )
                    }
                    .pointerInput(message.id) {
                        var total = 0f
                        var armed = false
                        detectHorizontalDragGestures(
                            onDragStart = {
                                total = 0f
                                armed = false
                            },
                            onDragEnd = {
                                if (armed) actions.onReply(latestMessage)
                                scope.launch { drag.animateTo(0f, spring(dampingRatio = 0.6f, stiffness = 500f)) }
                            },
                            onDragCancel = { scope.launch { drag.animateTo(0f) } },
                        ) { change, amount ->
                            total = (total + amount).coerceAtLeast(0f)
                            val nowArmed = total >= trigger
                            if (nowArmed && !armed) haptics.threshold()
                            armed = nowArmed
                            change.consume()
                            // Rubber-band past the trigger.
                            val shown = if (total <= trigger) total else trigger + (total - trigger) * 0.25f
                            scope.launch { drag.snapTo(shown) }
                        }
                    }
                    .then(
                        // Photos handle their own taps, alongside the one that reveals them.
                        if (message.type == MessageType.Image) {
                            Modifier
                        } else {
                            Modifier.pointerInput(message.id) { detectTapGestures(onDoubleTap = { doubleTap() }, onLongPress = { longPress() }) }
                        },
                    ),
            ) {
                when (message.type) {
                    MessageType.Text -> TextBubble(message.text, mine, morphFromTyping, searchQuery)
                    MessageType.Image -> if (message.viewOnce) {
                        ViewOnceBubble(message, mine, onOpen = { actions.onOpenViewOnce(message) }, onLongPress = longPress)
                    } else {
                        ImageBubble(message, revealed, onReveal = { actions.onReveal(message) }, onDoubleTap = doubleTap, onLongPress = longPress)
                    }
                    MessageType.Game -> GameBubble(message.game ?: GameCard(GameMode.Truth, message.text), mine)
                    MessageType.Voice -> VoiceBubble(message, mine, voice, onPlay = { actions.onPlayVoice(message) })
                    MessageType.Date -> DateBubble(message.date ?: DateIdea(message.text, "", "📅"), mine, otherName)
                    // Handled above; listed so the when stays exhaustive.
                    MessageType.Event -> Unit
                }
                if (hearting) {
                    Text(
                        "❤️",
                        fontSize = 44.sp,
                        modifier = Modifier.align(Alignment.Center).graphicsLayer {
                            scaleX = heartScale.value
                            scaleY = heartScale.value
                            alpha = heartAlpha.value
                        },
                    )
                }
            }
        }
        if (message.reactions.isNotEmpty()) {
            ReactionChips(
                reactions = message.reactions,
                myUid = myUid,
                onToggle = { emoji -> actions.onReact(message, emoji) },
                modifier = Modifier.offset(y = (-6).dp).padding(horizontal = 10.dp),
            )
        }
        if (showDateReplies) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DateQuickReplies.forEachIndexed { index, reply ->
                    QuickReplyPill(reply, primary = index == 0) { actions.onQuickReply(reply) }
                }
            }
        }
        if (receipt != null) {
            Text(receipt, style = EmberTheme.type.caption, color = colors.textTertiary, modifier = Modifier.padding(top = 3.dp, end = 6.dp))
        }
    }
}

/** Gradient for my messages, glass for theirs. */
@Composable
private fun Modifier.bubble(mine: Boolean): Modifier = if (mine) {
    background(EmberTheme.colors.accentBrush, EmberShapes.bubble)
} else {
    glass(EmberShapes.bubble, GlassDefaults.surface(), LocalHazeState.current)
}

/**
 * A text bubble. When it replaces the other person's typing indicator it starts out as the dots
 * and grows into the text ([SizeTransform]), so the indicator appears to become the message.
 */
@Composable
private fun TextBubble(text: String, mine: Boolean, morphFromTyping: Boolean, searchQuery: String?) {
    val color = if (mine) Color.White else EmberTheme.colors.textPrimary
    val mark = if (mine) Color.White.copy(alpha = 0.32f) else EmberTheme.colors.accent.copy(alpha = 0.32f)
    Box(Modifier.widthIn(max = 290.dp).bubble(mine).padding(horizontal = 16.dp, vertical = 11.dp)) {
        if (!morphFromTyping) {
            val found = if (searchQuery.isNullOrBlank()) emptyList() else ChatSearch.ranges(text, searchQuery)
            if (found.isEmpty()) {
                Text(text, style = EmberTheme.type.body, color = color)
            } else {
                val marked = remember(text, found, mark) {
                    buildAnnotatedString {
                        append(text)
                        found.forEach { addStyle(SpanStyle(background = mark, fontWeight = FontWeight.SemiBold), it.first, it.last + 1) }
                    }
                }
                Text(marked, style = EmberTheme.type.body, color = color)
            }
        } else {
            var grown by remember { mutableStateOf(false) }
            LaunchedEffect(Unit) { grown = true }
            AnimatedContent(
                targetState = grown,
                transitionSpec = {
                    fadeIn(tween(220, delayMillis = 120)) togetherWith fadeOut(tween(100)) using
                        SizeTransform(clip = false) { _, _ -> spring(dampingRatio = 0.78f, stiffness = 420f) }
                },
                label = "typingMorph",
            ) { show ->
                if (show) Text(text, style = EmberTheme.type.body, color = color) else TypingDots(Modifier.padding(vertical = 3.dp))
            }
        }
    }
}

/** "Today · 9:41 PM", centred in a small glass pill. */
@Composable
private fun DaySeparator(label: String) {
    Box(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = EmberTheme.type.caption,
            color = EmberTheme.colors.textSecondary,
            modifier = Modifier
                .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current)
                .padding(horizontal = 12.dp, vertical = 5.dp)
                .semantics { heading() },
        )
    }
}

/** A centred system row: "Arjun took a screenshot". An unknown event code shows its fallback text. */
@Composable
private fun EventRow(message: Message, mine: Boolean, otherName: String) {
    val colors = EmberTheme.colors
    val text = when (message.event) {
        ChatEvent.Screenshot -> if (mine) "You took a screenshot" else "$otherName took a screenshot"
        null -> message.text
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (message.event == ChatEvent.Screenshot) Icons.Rounded.Screenshot else Icons.Rounded.Info,
            contentDescription = null,
            tint = colors.textTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(text, style = EmberTheme.type.caption, color = colors.textSecondary)
    }
}

/** What's left of an unsent message: an outline, on both sides. */
@Composable
private fun UnsentBubble(mine: Boolean) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .border(1.dp, colors.divider, EmberShapes.bubble)
            .padding(horizontal = 14.dp, vertical = 9.dp)
            .semantics(mergeDescendants = true) {},
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Block, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            if (mine) "You unsent a message" else Message.UNSENT_TEXT,
            style = EmberTheme.type.bodySmall.copy(fontStyle = FontStyle.Italic),
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun QuotedMessage(reply: ReplyRef, author: String, unsent: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .padding(bottom = 3.dp)
            .widthIn(max = 260.dp)
            .height(IntrinsicSize.Min)
            .pressable(onClick = onClick, pressedScale = 0.97f)
            .background(colors.textPrimary.copy(alpha = 0.07f), EmberShapes.small)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accentBrush, PillShape))
        Spacer(Modifier.width(8.dp))
        Column {
            Text(author, style = EmberTheme.type.caption, color = colors.accent, maxLines = 1)
            if (unsent) {
                Text(Message.UNSENT_TEXT, style = EmberTheme.type.bodySmall.copy(fontStyle = FontStyle.Italic), color = colors.textTertiary, maxLines = 1)
            } else {
                Text(quoteText(reply), style = EmberTheme.type.bodySmall, color = colors.textSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A quote's one-line text, with an icon-ish prefix for anything that isn't plain text. */
internal fun quoteText(reply: ReplyRef): String = when (reply.type) {
    MessageType.Text, MessageType.Event -> reply.text
    MessageType.Image -> "📷 " + reply.text
    MessageType.Game -> "🎲 " + reply.text
    MessageType.Voice -> "🎤 " + reply.text
    MessageType.Date -> "📅 " + reply.text
}

@Composable
private fun ReactionChips(reactions: Map<String, String>, myUid: String, onToggle: (String) -> Unit, modifier: Modifier = Modifier) {
    val counts = Reactions.all.mapNotNull { emoji -> reactions.values.count { it == emoji }.takeIf { it > 0 }?.let { emoji to it } }
    val mine = reactions[myUid]
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        counts.forEach { (emoji, count) ->
            key(emoji) { ReactionChip(emoji, count, isMine = emoji == mine, onClick = { onToggle(emoji) }) }
        }
    }
}

/** Pops in with an overshooting spring the first time it appears. */
@Composable
private fun ReactionChip(emoji: String, count: Int, isMine: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val scale = remember { Animatable(0.2f) }
    LaunchedEffect(Unit) { scale.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 520f)) }
    Row(
        Modifier
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            }
            .pressable(onClick = onClick, pressedScale = 0.88f)
            .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current)
            .then(if (isMine) Modifier.border(1.dp, colors.accent, PillShape) else Modifier)
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(emoji, fontSize = 14.sp)
        if (count > 1) {
            Spacer(Modifier.width(3.dp))
            Text("$count", style = EmberTheme.type.caption, color = colors.textSecondary)
        }
    }
}

@Composable
private fun QuickReplyPill(text: String, primary: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Box(
        Modifier
            .pressable(onClick = onClick, pressedScale = 0.92f)
            .then(
                if (primary) {
                    Modifier.background(colors.accentHorizontal, PillShape)
                } else {
                    Modifier.glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
                },
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, style = EmberTheme.type.bodySmall, color = if (primary) colors.onAccent else colors.textPrimary)
    }
}

/**
 * A veiled image shows a BlurHash (no real pixels) until the recipient chooses to look. Below
 * Android 12, where a real-time blur isn't available, a hash-less image stays fully hidden.
 * Which photos arrive veiled is the chat's call: see [veilsPhoto].
 */
@Composable
private fun ImageBubble(message: Message, revealed: Boolean, onReveal: () -> Unit, onDoubleTap: () -> Unit, onLongPress: () -> Unit) {
    val colors = EmberTheme.colors
    val placeholder = rememberBlurHashPainter(message.imageBlurHash)
    Box(
        Modifier
            .size(width = 220.dp, height = 280.dp)
            .clip(EmberShapes.bubble)
            .background(colors.surfaceHigh)
            .combinedClickable(
                interactionSource = null,
                indication = null,
                onClickLabel = if (revealed) null else "Show photo",
                onLongClick = onLongPress,
                onDoubleClick = onDoubleTap,
                onClick = { if (!revealed) onReveal() },
            ),
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
            OverPhotoLabel(Icons.Rounded.VisibilityOff, "May be sensitive", subtitle = "Tap to view")
        }
        if (message.imageUrl == null && revealed) CircularProgressIndicator(color = colors.accent)
    }
}

/**
 * View-once photos. The recipient gets a frosted tile (only the BlurHash, never pixels) until
 * they open it, once; the sender only ever sees whether it has been opened.
 */
@Composable
private fun ViewOnceBubble(message: Message, mine: Boolean, onOpen: () -> Unit, onLongPress: () -> Unit) {
    val colors = EmberTheme.colors
    val opened = message.viewedAt != null
    if (mine || opened) {
        val tint = if (mine) Color.White else colors.textSecondary
        Row(
            Modifier
                .bubble(mine)
                .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongPress, onClick = {})
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (opened) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Column {
                Text("View-once photo", style = EmberTheme.type.body, color = if (mine) Color.White else colors.textPrimary)
                Text(
                    when {
                        opened -> "Opened"
                        message.pending -> "Sending…"
                        else -> "Not opened yet"
                    },
                    style = EmberTheme.type.caption,
                    color = tint,
                )
            }
        }
        return
    }
    val placeholder = rememberBlurHashPainter(message.imageBlurHash)
    Box(
        Modifier
            .size(width = 220.dp, height = 160.dp)
            .clip(EmberShapes.bubble)
            .background(colors.accentBrush, alpha = 0.55f)
            .combinedClickable(interactionSource = null, indication = null, onLongClick = onLongPress, onClick = onOpen),
        contentAlignment = Alignment.Center,
    ) {
        if (placeholder != null) Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        OverPhotoLabel(Icons.Rounded.Visibility, "Tap to view once")
    }
}

@Composable
private fun OverPhotoLabel(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, subtitle: String? = null) {
    Row(
        Modifier.glass(PillShape, GlassDefaults.overPhoto(), LocalHazeState.current).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Column {
            Text(text, style = EmberTheme.type.caption, color = Color.White)
            if (subtitle != null) Text(subtitle, style = EmberTheme.type.caption, color = Color.White.copy(alpha = 0.75f))
        }
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
private fun DateBubble(idea: DateIdea, mine: Boolean, otherName: String) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.widthIn(max = 300.dp), shape = EmberShapes.panel, style = GlassDefaults.chrome()) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(colors.accentBrush, CircleShape), contentAlignment = Alignment.Center) {
                    Text(idea.emoji, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    GradientText("DATE IDEA", style = EmberTheme.type.overline)
                    Text(if (mine) "You suggested" else "$otherName suggested", style = EmberTheme.type.caption, color = colors.textTertiary)
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(idea.title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            if (idea.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(idea.detail, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
            }
        }
    }
}

/** Play/pause, a waveform-ish progress bar and the duration. */
@Composable
private fun VoiceBubble(message: Message, mine: Boolean, player: VoicePlayer, onPlay: () -> Unit) {
    val colors = EmberTheme.colors
    val active = player.activeId == message.id
    val progress = player.progressOf(message.id)
    val tint = if (mine) Color.White else colors.textPrimary
    Row(
        Modifier.width(240.dp).bubble(mine).padding(start = 8.dp, end = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .pressable(onClick = onPlay, pressedScale = 0.88f)
                .then(if (mine) Modifier.background(Color.White.copy(alpha = 0.22f), CircleShape) else Modifier.background(colors.accentBrush, CircleShape)),
            contentAlignment = Alignment.Center,
        ) {
            if (active && player.isLoading) {
                CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    if (active && player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (active && player.isPlaying) "Pause voice note" else "Play voice note",
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Waveform(seed = message.id, progress = progress, played = tint, unplayed = tint.copy(alpha = 0.35f), modifier = Modifier.weight(1f).height(28.dp))
        Spacer(Modifier.width(10.dp))
        val shownMs = if (active && progress > 0f) (message.durationMs * progress).toLong() else message.durationMs
        Text(formatDuration(shownMs), style = EmberTheme.type.caption, color = tint)
    }
}

/** Bars of pseudo-random heights (stable per message), filled up to [progress]. */
@Composable
private fun Waveform(seed: String, progress: Float, played: Color, unplayed: Color, modifier: Modifier = Modifier) {
    val bars = remember(seed) {
        val random = Random(seed.hashCode())
        List(BARS) { 0.25f + 0.75f * random.nextFloat() }
    }
    Canvas(modifier) {
        val slot = size.width / BARS
        val barWidth = slot * 0.55f
        bars.forEachIndexed { i, height ->
            val h = size.height * height
            drawRoundRect(
                color = if ((i + 0.5f) / BARS <= progress) played else unplayed,
                topLeft = Offset(i * slot + (slot - barWidth) / 2, (size.height - h) / 2),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

private const val BARS = 26

internal fun formatDuration(ms: Long): String {
    val seconds = (ms + 500) / 1000
    return "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** [startedAt] (uptime millis): when they started typing, so the dots can quicken as they go. */
@Composable
internal fun TypingBubble(modifier: Modifier = Modifier, startedAt: Long? = null, still: Boolean = false) {
    TypingDots(
        modifier
            .glass(EmberShapes.bubble, GlassDefaults.surface(), LocalHazeState.current)
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { contentDescription = "Typing" },
        startedAt = startedAt,
        still = still,
    )
}

/**
 * Three dots that breathe: each rises and settles in turn, and the rhythm speeds up the longer
 * the other person keeps typing ([typingCycleMs]). The phase is only read while drawing, so a
 * frame redraws three layers and recomposes nothing. [still]: reduced motion, the dots just sit.
 */
@Composable
private fun TypingDots(modifier: Modifier = Modifier, startedAt: Long? = null, still: Boolean = false) {
    val colors = EmberTheme.colors
    var phase by remember { mutableFloatStateOf(0f) }
    if (!still) {
        LaunchedEffect(startedAt) {
            var last = withFrameNanos { it }
            while (true) {
                withFrameNanos { now ->
                    val typingFor = if (startedAt == null) 0L else SystemClock.uptimeMillis() - startedAt
                    phase = (phase + (now - last) / 1_000_000f / typingCycleMs(typingFor)) % 1f
                    last = now
                }
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer {
                        val lift = if (still) 0.5f else dotLift(phase, i)
                        translationY = -lift * 5.dp.toPx()
                        alpha = 0.45f + lift * 0.55f
                    }
                    .background(colors.textSecondary, CircleShape),
            )
        }
    }
}
