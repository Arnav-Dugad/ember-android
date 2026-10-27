package io.github.arnavdugad.ember.ui.chat

import android.net.Uri
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GlassTag
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.Reactions
import io.github.arnavdugad.ember.data.settings.ChatPrefs
import io.github.arnavdugad.ember.ui.album.SecureWindow
import io.github.arnavdugad.ember.ui.game.LocalGroupGameLauncher
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Truth or Dare: pick a side, get a card, shuffle until it feels right, send. */
@Composable
internal fun TruthOrDareSheet(visible: Boolean, draw: (GameMode) -> GameCard, onSend: (GameCard) -> Unit, onDismiss: () -> Unit) {
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
        // Group Truth or Dare, with this match already picked (provided by the nav host).
        LocalGroupGameLauncher.current?.let { openGroupGame ->
            Spacer(Modifier.height(12.dp))
            GlassButton(
                "Play with more people",
                onClick = {
                    onDismiss()
                    openGroupGame()
                },
                icon = Icons.Rounded.Groups,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Three to five date ideas from what you both like; shuffle for more, send one as a card. */
@Composable
internal fun DatePlannerSheet(visible: Boolean, otherName: String, suggest: () -> List<DateIdea>, onSend: (DateIdea) -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    var ideas by remember(visible) { mutableStateOf(if (visible) suggest() else emptyList()) }
    var selected by remember(visible) { mutableIntStateOf(0) }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Plan a date", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text("Ideas from what you and $otherName are into.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = ideas, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "ideas") { shown ->
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                shown.forEachIndexed { index, idea ->
                    DateIdeaRow(idea, selected = index == selected) {
                        haptics.tick()
                        selected = index
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(
                "Shuffle",
                onClick = {
                    ideas = suggest()
                    selected = 0
                },
                icon = Icons.Rounded.Shuffle,
                modifier = Modifier.weight(1f),
            )
            EmberButton(
                "Send",
                onClick = { ideas.getOrNull(selected)?.let(onSend) },
                enabled = ideas.isNotEmpty(),
                icon = Icons.AutoMirrored.Rounded.Send,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun DateIdeaRow(idea: DateIdea, selected: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val border by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "idea")
    GlassSurface(
        Modifier.fillMaxWidth().border(1.5.dp, border, EmberShapes.tile).pressable(onClick = onClick, pressedScale = 0.98f, haptic = false),
        shape = EmberShapes.tile,
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(idea.emoji, fontSize = 26.sp)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(idea.title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(idea.detail, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
}

/** After picking a photo: send it normally, or as a view-once photo. */
@Composable
internal fun PhotoSendSheet(uri: Uri?, onSend: (Uri, viewOnce: Boolean) -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    // Keeps the photo on screen while the sheet animates away.
    val last = remember { arrayOfNulls<Uri>(1) }
    uri?.let { last[0] = it }
    GlassBottomSheet(visible = uri != null, onDismiss = onDismiss) {
        val shown = last[0] ?: return@GlassBottomSheet
        Text("Send a photo", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(14.dp))
        AsyncImage(
            shown,
            contentDescription = "Selected photo",
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxWidth().height(240.dp).clip(EmberShapes.panel).background(colors.surfaceHigh),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            "View once: they can open it a single time, with screenshots blocked. Then it's deleted.",
            style = EmberTheme.type.caption,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("View once", onClick = { onSend(shown, true) }, icon = Icons.Rounded.Visibility, modifier = Modifier.weight(1f))
            EmberButton("Send", onClick = { onSend(shown, false) }, icon = Icons.AutoMirrored.Rounded.Send, modifier = Modifier.weight(1f))
        }
    }
}

/** A long-pressed bubble and where it sits on screen. */
internal class ReactionTarget(val message: Message, val bounds: Rect, val mine: Boolean)

/** One row of the long-press menu. */
internal class MenuAction(val label: String, val icon: ImageVector, val destructive: Boolean = false, val onClick: () -> Unit)

/**
 * What pops up over a long-pressed bubble: the glass reaction bar above it and a short action
 * menu (reply, copy, info, unsend) below it. When there's no room on one side, both stack on
 * the other, or from the bottom of the screen for a very tall bubble.
 */
@Composable
internal fun ReactionOverlay(
    target: ReactionTarget,
    myReaction: String?,
    onReact: (String) -> Unit,
    menu: List<MenuAction>,
    onDismiss: () -> Unit,
) {
    val colors = EmberTheme.colors
    BackHandler(onBack = onDismiss)
    val appear = remember(target) { Animatable(0.6f) }
    LaunchedEffect(target) { appear.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 600f)) }
    val pop = Modifier.graphicsLayer {
        scaleX = appear.value
        scaleY = appear.value
        alpha = ((appear.value - 0.6f) / 0.4f).coerceIn(0f, 1f)
        transformOrigin = TransformOrigin(if (target.mine) 1f else 0f, 0.5f)
    }
    Layout(
        content = {
            Row(
                pop
                    .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Reactions.all.forEachIndexed { index, emoji ->
                    ReactionOption(emoji, index, selected = emoji == myReaction, onClick = { onReact(emoji) })
                }
            }
            Column(
                pop
                    .width(IntrinsicSize.Max)
                    .widthIn(min = 210.dp)
                    .glass(EmberShapes.tile, GlassDefaults.chrome(), LocalHazeState.current, elevated = true)
                    .padding(vertical = 4.dp),
            ) {
                menu.forEachIndexed { index, action ->
                    if (index > 0) Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).height(1.dp).background(colors.divider))
                    MenuRow(action)
                }
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.18f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bar = measurables[0].measure(loose)
        val list = measurables[1].measure(loose)
        val margin = 12.dp.roundToPx()
        val gap = 8.dp.roundToPx()
        val topLimit = 110.dp.roundToPx()
        val bottomLimit = constraints.maxHeight - margin
        val top = target.bounds.top.roundToInt()
        val bottom = target.bounds.bottom.roundToInt()
        fun xFor(width: Int) = (if (target.mine) target.bounds.right.roundToInt() - width else target.bounds.left.roundToInt())
            .coerceIn(margin, (constraints.maxWidth - width - margin).coerceAtLeast(margin))
        var barY: Int
        var listY: Int
        if (top - gap - bar.height >= topLimit) {
            barY = top - gap - bar.height
            listY = bottom + gap
            if (listY + list.height > bottomLimit) listY = barY - gap - list.height
        } else {
            barY = bottom + gap
            listY = barY + bar.height + gap
        }
        if (listY < topLimit || listY + list.height > bottomLimit) {
            listY = bottomLimit - list.height
            barY = listY - gap - bar.height
        }
        layout(constraints.maxWidth, constraints.maxHeight) {
            bar.place(xFor(bar.width), barY)
            list.place(xFor(list.width), listY)
        }
    }
}

@Composable
private fun MenuRow(action: MenuAction) {
    val colors = EmberTheme.colors
    val tint = if (action.destructive) colors.danger else colors.textPrimary
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(onClick = action.onClick, pressedScale = 0.97f)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(action.label, style = EmberTheme.type.body, color = tint, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Icon(action.icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** The chat's ⋯ menu: search, this chat's privacy switches, and the safety tools. */
@Composable
internal fun ChatOptionsSheet(
    visible: Boolean,
    name: String,
    prefs: ChatPrefs,
    receiptsOn: Boolean,
    onSearch: () -> Unit,
    onReadReceipts: (Boolean) -> Unit,
    onBlurAllPhotos: (Boolean) -> Unit,
    onScreenshotAlerts: (Boolean) -> Unit,
    onSafety: () -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Chat options", style = EmberTheme.type.headline, color = colors.textPrimary)
            Spacer(Modifier.height(16.dp))
            OptionRow(Icons.Rounded.Search, "Search in chat", "Find a message on this phone", onSearch)
            Spacer(Modifier.height(18.dp))
            SectionHeader("Privacy")
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Read receipts",
                detail = "When off, $name won't see when you've read this chat. Your own unread badges still work. Saved on this phone.",
                checked = receiptsOn,
                onChange = onReadReceipts,
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Blur every photo",
                detail = "When off, only photos sent before you've replied arrive blurred. All chats.",
                checked = prefs.blurAllPhotos,
                onChange = onBlurAllPhotos,
            )
            Spacer(Modifier.height(10.dp))
            ToggleRow(
                title = "Screenshot alerts",
                detail = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    "Let your matches know when you screenshot a chat, and see when they do. All chats."
                } else {
                    "See when your matches screenshot a chat. Telling them about yours needs Android 14."
                },
                checked = prefs.screenshotAlerts,
                onChange = onScreenshotAlerts,
            )
            Spacer(Modifier.height(18.dp))
            OptionRow(Icons.Rounded.Shield, "Safety", "Report, block or unmatch $name", onSafety)
        }
    }
}

@Composable
private fun OptionRow(icon: ImageVector, title: String, detail: String, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(detail, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        shape = EmberShapes.tile,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(detail, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked, onCheckedChange = null, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White))
        }
    }
}

/**
 * When a message was sent and, for mine, whether it has been read. "Read" comes from the other
 * person's read marker, so it's the time they were last in the chat, not the exact moment.
 */
@Composable
internal fun MessageInfoSheet(message: Message?, myUid: String, otherName: String, otherLastRead: Instant?, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    // Keeps the details on screen while the sheet animates away.
    val last = remember { arrayOfNulls<Message>(1) }
    message?.let { last[0] = it }
    GlassBottomSheet(visible = message != null, onDismiss = onDismiss) {
        val shown = last[0] ?: return@GlassBottomSheet
        Text("Message info", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(12.dp))
        GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
            Text(
                shown.summary,
                style = EmberTheme.type.body,
                color = colors.textPrimary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(16.dp),
            )
        }
        Spacer(Modifier.height(14.dp))
        val sent = shown.createdAt
        if (shown.senderId == myUid) {
            InfoRow("Sent", if (shown.pending || sent == null) "Waiting for a connection" else fullTime(sent))
            val read = otherLastRead?.takeIf { sent != null && !shown.pending && !it.isBefore(sent) }
            InfoRow("Read", read?.let { "By ${fullTime(it)}" } ?: "Not yet")
            Spacer(Modifier.height(8.dp))
            Text(
                "\"Read\" comes from $otherName's read receipts. If they've turned them off, it stays \"Not yet\".",
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
            )
        } else {
            InfoRow("Received", sent?.let(::fullTime) ?: "Just now")
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}) {
        Text(label, style = EmberTheme.type.body, color = EmberTheme.colors.textSecondary, modifier = Modifier.weight(1f))
        Text(value, style = EmberTheme.type.body, color = EmberTheme.colors.textPrimary)
    }
}

/** "Tue 14 Oct, 9:41 PM", in the phone's time zone. */
private fun fullTime(instant: Instant): String =
    instant.atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("EEE d MMM, h:mm a"))

/** One emoji in the bar. They pop in one after another. */
@Composable
private fun ReactionOption(emoji: String, index: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val pop = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(index * 28L)
        pop.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = 520f))
    }
    Box(
        Modifier
            .size(44.dp)
            .graphicsLayer {
                scaleX = pop.value
                scaleY = pop.value
            }
            .pressable(onClick = onClick, pressedScale = 0.8f)
            .then(if (selected) Modifier.background(colors.accent.copy(alpha = 0.28f), CircleShape) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(emoji, fontSize = 24.sp)
    }
}

/**
 * Full-screen view of a view-once photo. FLAG_SECURE blocks screenshots and recording while it's
 * up, and nothing is cached in memory. [onGone] runs once, however the viewer goes away (close,
 * back, leaving the chat), saying whether the photo was actually shown: only then is it deleted
 * for good, so a failed load (say, offline) doesn't burn it.
 */
@Composable
internal fun ViewOnceViewer(message: Message, onClose: () -> Unit, onGone: (shown: Boolean) -> Unit) {
    SecureWindow()
    BackHandler(onBack = onClose)
    val gone by rememberUpdatedState(onGone)
    val shown = remember(message.id) { booleanArrayOf(false) }
    DisposableEffect(message.id) { onDispose { gone(shown[0]) } }
    val context = LocalContext.current
    val request = remember(message.imageUrl) {
        ImageRequest.Builder(context)
            .data(message.imageUrl)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .diskCachePolicy(CachePolicy.DISABLED)
            .build()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
    ) {
        AsyncImage(
            request,
            contentDescription = "View-once photo",
            contentScale = ContentScale.Fit,
            onSuccess = { shown[0] = true },
            modifier = Modifier.fillMaxSize(),
        )
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.Rounded.Close, contentDescription = "Close", onClick = onClose, style = GlassDefaults.overPhoto(), tint = Color.White)
            Spacer(Modifier.weight(1f))
            GlassTag("View once · closes for good", style = GlassDefaults.overPhoto(), textColor = Color.White)
        }
    }
}
