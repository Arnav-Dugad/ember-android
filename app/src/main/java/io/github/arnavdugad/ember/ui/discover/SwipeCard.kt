package io.github.arnavdugad.ember.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.GlassTag
import io.github.arnavdugad.ember.core.designsystem.PaginationBars
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.ui.main.sharedPhoto
import io.github.arnavdugad.ember.ui.profile.VoicePill
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/** Drag state of the top card. Everything visual (rotation, stamps, the cards behind) derives from [offset]. */
@Stable
class CardSwipeState {
    val offset = Animatable(Offset.Zero, Offset.VectorConverter)
    var width by mutableFloatStateOf(1f)
    var height by mutableFloatStateOf(1f)
    var grabbedTop by mutableStateOf(true)

    /** -1 (full nope) .. 1 (full like). */
    val horizontal: Float get() = (offset.value.x / (width * COMMIT_FRACTION)).coerceIn(-1f, 1f)

    /** 0 .. 1 (full super like, dragging up). */
    val vertical: Float get() = (-offset.value.y / (height * SUPER_FRACTION)).coerceIn(0f, 1f)

    val progress: Float get() = max(abs(horizontal), vertical)

    /** Which way the card would go if released now, by position alone. */
    val intent: SwipeDirection?
        get() = when {
            vertical >= 1f && abs(horizontal) < 0.8f -> SwipeDirection.Super
            horizontal >= 1f -> SwipeDirection.Like
            horizontal <= -1f -> SwipeDirection.Nope
            else -> null
        }

    fun decide(velocity: Offset): SwipeDirection? {
        intent?.let { return it }
        return when {
            velocity.y < -FLING_VELOCITY && abs(velocity.y) > abs(velocity.x) * 1.4f -> SwipeDirection.Super
            velocity.x > FLING_VELOCITY && offset.value.x > 0f -> SwipeDirection.Like
            velocity.x < -FLING_VELOCITY && offset.value.x < 0f -> SwipeDirection.Nope
            else -> null
        }
    }

    /** Throws the card off-screen, carrying the release velocity so it never "catches". */
    suspend fun flingAway(direction: SwipeDirection, velocity: Offset = Offset.Zero) {
        val start = offset.value
        val target = when (direction) {
            SwipeDirection.Like -> Offset(width * 1.6f, start.y + velocity.y * 0.08f)
            SwipeDirection.Nope -> Offset(-width * 1.6f, start.y + velocity.y * 0.08f)
            SwipeDirection.Super -> Offset(start.x + velocity.x * 0.05f, -height * 1.4f)
        }
        val speed = max(velocity.getDistance(), 1500f)
        val distance = (target - start).getDistance()
        val duration = (distance / speed * 1000f).toInt().coerceIn(170, 360)
        offset.animateTo(target, tween(duration, easing = FastOutLinearInEasing))
    }

    suspend fun reset() {
        offset.animateTo(Offset.Zero, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow))
    }

    companion object {
        const val COMMIT_FRACTION = 0.32f
        const val SUPER_FRACTION = 0.22f
        const val FLING_VELOCITY = 1300f
    }
}

/** Lets the action buttons drive the top card. */
@Stable
class DeckController {
    var top: CardSwipeState? by mutableStateOf(null)
        internal set
    internal var onCommit: ((SwipeDirection) -> Unit)? = null

    suspend fun swipe(direction: SwipeDirection) {
        val state = top ?: return
        if (state.offset.isRunning) return
        state.flingAway(direction)
        onCommit?.invoke(direction)
    }
}

/**
 * The deck: up to three cards, the ones behind scaling and rising toward the front as the top
 * card leaves. Only the top card takes gestures.
 */
@Composable
fun SwipeDeck(
    profiles: List<Profile>,
    controller: DeckController,
    onSwiped: (Profile, SwipeDirection) -> Unit,
    onOpen: (Profile, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = profiles.take(3)
    val topState = key(visible.firstOrNull()?.uid) { remember { CardSwipeState() } }
    val top = visible.firstOrNull()
    controller.top = if (top != null) topState else null
    controller.onCommit = { dir -> top?.let { onSwiped(it, dir) } }
    val haptics = EmberTheme.haptics
    LaunchedEffect(topState) {
        snapshotFlow { topState.intent }.collect { if (it != null) haptics.threshold() }
    }
    Box(modifier.testTag("swipe_deck")) {
        visible.asReversed().forEachIndexed { reversedIndex, profile ->
            val depth = visible.size - 1 - reversedIndex
            key(profile.uid) {
                if (depth == 0) {
                    TopCard(profile, topState, onSwiped = { onSwiped(profile, it) }, onOpen = { onOpen(profile, it) })
                } else {
                    val progress by remember(topState) { derivedStateOf { topState.progress } }
                    val scale = lerp(1f - depth * 0.05f, 1f - (depth - 1) * 0.05f, progress)
                    val lift = lerp(depth * 16f, (depth - 1) * 16f, progress)
                    ProfileCard(
                        profile = profile,
                        photoIndex = 0,
                        onTapLeft = {},
                        onTapRight = {},
                        onOpen = {},
                        modifier = Modifier.fillMaxSize().graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationY = lift.dp.toPx()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopCard(profile: Profile, state: CardSwipeState, onSwiped: (SwipeDirection) -> Unit, onOpen: (Int) -> Unit) {
    val scope = rememberCoroutineScope()
    val haptics = EmberTheme.haptics
    var photoIndex by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // Tapping past the first or last photo tilts the card in 3D and springs back: a physical
    // "that's all" instead of a dead tap.
    val nudge = remember { Animatable(0f) }
    fun edge(direction: Float) {
        haptics.reject()
        scope.launch {
            nudge.animateTo(direction * 7f, tween(70))
            nudge.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 500f))
        }
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        with(density) {
            state.width = maxWidth.toPx()
            state.height = maxHeight.toPx()
        }
        ProfileCard(
            profile = profile,
            photoIndex = photoIndex,
            onTapLeft = {
                if (photoIndex > 0) {
                    photoIndex--
                    haptics.tick()
                } else {
                    edge(-1f)
                }
            },
            onTapRight = {
                if (photoIndex < profile.photos.lastIndex) {
                    photoIndex++
                    haptics.tick()
                } else {
                    edge(1f)
                }
            },
            onOpen = { onOpen(photoIndex) },
            stamps = { CardStamps(state) },
            shared = true,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(state) {
                    val tracker = VelocityTracker()
                    detectDragGestures(
                        onDragStart = { start ->
                            tracker.resetTracking()
                            state.grabbedTop = start.y < size.height / 2f
                        },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity()
                            val v = Offset(velocity.x, velocity.y)
                            scope.launch {
                                val direction = state.decide(v)
                                if (direction != null) {
                                    state.flingAway(direction, v)
                                    onSwiped(direction)
                                } else {
                                    state.reset()
                                }
                            }
                        },
                        onDragCancel = { scope.launch { state.reset() } },
                        onDrag = { change, drag ->
                            change.consume()
                            tracker.addPointerInputChange(change)
                            scope.launch { state.offset.snapTo(state.offset.value + drag) }
                        },
                    )
                }
                .graphicsLayer {
                    val o = state.offset.value
                    translationX = o.x
                    translationY = o.y
                    // Rotate around a point below the card when grabbed at the top (and above
                    // it when grabbed at the bottom), the way a held photo pivots in your hand.
                    val sign = if (state.grabbedTop) 1f else -1f
                    rotationZ = (o.x / state.width) * MAX_ROTATION * sign
                    rotationY = nudge.value
                    cameraDistance = 14f * this.density
                    transformOrigin = TransformOrigin(0.5f, if (state.grabbedTop) 1.25f else -0.25f)
                },
        )
    }
}

@Composable
private fun CardStamps(state: CardSwipeState) {
    val colors = EmberTheme.colors
    Box(Modifier.fillMaxSize().padding(28.dp)) {
        Stamp("LIKE", colors.like, -14f, alpha = state.horizontal.coerceAtLeast(0f), modifier = Modifier.align(Alignment.TopStart).padding(top = 24.dp))
        Stamp("NOPE", colors.nope, 14f, alpha = (-state.horizontal).coerceAtLeast(0f), modifier = Modifier.align(Alignment.TopEnd).padding(top = 24.dp))
        Stamp("SUPER", colors.superLike, -6f, alpha = state.vertical, modifier = Modifier.align(Alignment.Center).padding(bottom = 80.dp))
    }
}

@Composable
private fun Stamp(text: String, color: Color, angle: Float, alpha: Float, modifier: Modifier = Modifier) {
    Text(
        text,
        style = EmberTheme.type.headline.copy(letterSpacing = EmberTheme.type.overline.letterSpacing),
        color = color,
        modifier = modifier
            .graphicsLayer {
                this.alpha = alpha
                // Stamps punch in: large and soft, landing at full size as the drag commits.
                val s = lerp(1.5f, 1f, alpha)
                scaleX = s
                scaleY = s
                rotationZ = angle
            }
            .border(4.dp, color, EmberShapes.small)
            .background(Color.Black.copy(alpha = 0.18f), EmberShapes.small)
            .padding(horizontal = 14.dp, vertical = 4.dp),
    )
}

/** A profile card: photo pager, pagination bars, name and tags over a soft scrim. */
@Composable
fun ProfileCard(
    profile: Profile,
    photoIndex: Int,
    onTapLeft: () -> Unit,
    onTapRight: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    stamps: @Composable () -> Unit = {},
    shared: Boolean = false,
    /** The top card: its overlays (voice pill) are interactive. */
    live: Boolean = shared,
) {
    val colors = EmberTheme.colors
    Box(
        modifier
            .dropShadow(EmberShapes.card, Shadow(radius = 28.dp, color = Color.Black.copy(alpha = 0.35f), offset = DpOffset(0.dp, 14.dp)))
            .clip(EmberShapes.card)
            .background(colors.surface),
    ) {
        val photo = profile.photos.getOrNull(photoIndex) ?: profile.firstPhoto
        AnimatedContent(
            targetState = photo,
            transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(180)) },
            label = "cardPhoto",
            modifier = Modifier.fillMaxSize(),
        ) { p ->
            EmberImage(
                p?.url,
                p?.blurHash,
                contentDescription = "${profile.name}'s photo",
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (shared && p == profile.firstPhoto) Modifier.sharedPhoto("photo-${profile.uid}") else Modifier),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                // One tap handler for the whole card, so a tap can never both page and open:
                // the info panel at the bottom opens the profile, the left third pages back,
                // the rest pages forward.
                .pointerInput(profile.uid, photoIndex) {
                    detectTapGestures { pos ->
                        when {
                            pos.y > size.height * INFO_FRACTION -> onOpen()
                            pos.x < size.width / 3f -> onTapLeft()
                            else -> onTapRight()
                        }
                    }
                },
        )
        Box(
            Modifier
                .fillMaxWidth()
                .height(110.dp)
                .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent))),
        )
        PaginationBars(profile.photos.size, photoIndex, Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
        if (live) VoicePill(profile, Modifier.align(Alignment.TopEnd).padding(top = 26.dp, end = 14.dp))
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.55f), Color.Black.copy(alpha = 0.85f))))
                .padding(start = 20.dp, end = 20.dp, top = 80.dp, bottom = 22.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Name, age and badge share the space left of the info button; the name only
                // truncates when it genuinely doesn't fit.
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${profile.name}  ",
                        style = EmberTheme.type.headline,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("${profile.age}", style = EmberTheme.type.title.copy(fontWeight = EmberTheme.type.body.fontWeight), color = Color.White.copy(alpha = 0.9f))
                    if (profile.isDemo) {
                        Spacer(Modifier.width(10.dp))
                        DemoBadge()
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(40.dp).glass(CircleShape, GlassDefaults.overPhoto(), LocalHazeState.current),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Open ${profile.name}'s profile", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
            val place = listOfNotNull(Geo.distanceLabel(profile.distanceKm), profile.city.ifBlank { null }).joinToString(" · ")
            if (place.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                    Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(place, style = EmberTheme.type.bodySmall, color = Color.White.copy(alpha = 0.85f))
                }
            }
            if (profile.bio.isNotBlank()) {
                Text(
                    profile.bio,
                    style = EmberTheme.type.bodySmall,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxLines = 1) {
                val overPhoto = GlassDefaults.overPhoto()
                GlassTag("${profile.lookingFor.emoji} ${profile.lookingFor.label}", style = overPhoto, textColor = Color.White)
                profile.vibes.take(3).mapNotNull(Catalog::vibe).forEach { GlassTag("${it.emoji} ${it.label}", style = overPhoto, textColor = Color.White) }
            }
        }
        stamps()
    }
}

private const val MAX_ROTATION = 16f
private const val INFO_FRACTION = 0.7f
