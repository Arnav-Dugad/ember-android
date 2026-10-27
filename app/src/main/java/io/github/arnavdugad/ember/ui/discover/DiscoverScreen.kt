package io.github.arnavdugad.ember.ui.discover

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FlightTakeoff
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberChip
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.EmberWordmark
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.OnlineDot
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.rememberReduceMotion
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.Place
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.ui.main.BottomBarSpace
import io.github.arnavdugad.ember.ui.onboarding.CityPickerSheet
import io.github.arnavdugad.ember.ui.profile.SpotlightButton
import io.github.arnavdugad.ember.ui.profile.SpotlightSheet
import io.github.arnavdugad.ember.ui.profile.SpotlightViewModel
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenProfile: (uid: String, photoIndex: Int) -> Unit,
    onOpenChat: (matchId: String) -> Unit,
    onOpenLikes: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val myPhoto by viewModel.myPhotoUrl.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val likesCount by viewModel.likesYouCount.collectAsStateWithLifecycle()
    val spotlightVm: SpotlightViewModel = hiltViewModel()
    val spotlight by spotlightVm.state.collectAsStateWithLifecycle()
    var spotlightOpen by remember { mutableStateOf(false) }
    // Hiding my own activity hides everyone's: no dots, no "Online now" chip.
    val hidesActivity = me?.hideActivity == true
    val reduceMotion = rememberReduceMotion()
    val controller = remember { DeckController() }
    val scope = rememberCoroutineScope()
    var filtersOpen by remember { mutableStateOf(false) }
    var picksOpen by remember { mutableStateOf(false) }
    var travelPickerOpen by remember { mutableStateOf(false) }
    var travelDraft by remember { mutableStateOf<Place?>(null) }
    var superBurst by remember { mutableLongStateOf(0L) }
    var heartBurst by remember { mutableLongStateOf(0L) }
    val nopeShake = remember { Animatable(0f) }
    var likeCenter by remember { mutableStateOf(Offset.Unspecified) }
    var deckCenter by remember { mutableStateOf(Offset.Unspecified) }
    var overlayOrigin by remember { mutableStateOf(Offset.Zero) }
    val pullState = rememberPullToRefreshState()
    val haptics = EmberTheme.haptics

    fun openFilters() {
        travelDraft = state.filters.browseFrom
        filtersOpen = true
    }

    fun shakeNope() {
        scope.launch { nopeShake.shake(amplitude = 9f) }
    }

    // Feedback the moment a direction is decided: hearts for a like, a "no" shake for a nope.
    fun committed(direction: SwipeDirection) {
        when (direction) {
            SwipeDirection.Like -> heartBurst = System.nanoTime()
            SwipeDirection.Nope -> shakeNope()
            SwipeDirection.Super -> superBurst = System.nanoTime()
        }
    }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(tint = state.glow) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(bottom = BottomBarSpace)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EmberWordmark()
                Spacer(Modifier.weight(1f))
                AfterDarkPill(state.afterDark, onToggle = {
                    haptics.confirm()
                    viewModel.setAfterDark(!state.afterDark)
                })
                Spacer(Modifier.width(8.dp))
                SpotlightButton(spotlight, onClick = {
                    haptics.tick()
                    spotlightVm.clearError()
                    spotlightVm.refresh()
                    spotlightOpen = true
                })
                Spacer(Modifier.width(8.dp))
                GlassIconButton(Icons.Rounded.Tune, contentDescription = "Filters", onClick = ::openFilters)
            }
            // The chips and the deck scroll together inside the pull-to-refresh box: pulling down
            // on the chips (or an empty deck) refreshes, while a drag on a card still moves the card.
            PullToRefreshBox(
                isRefreshing = state.refreshing,
                onRefresh = {
                    haptics.confirm()
                    viewModel.refresh()
                },
                state = pullState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                indicator = { FlameRefreshIndicator(pullState, state.refreshing) },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val height = maxHeight
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Column(Modifier.fillMaxWidth().height(height)) {
                            QuickChips(
                                travel = state.filters.browseFrom,
                                freeTonightUntil = me?.freeTonightUntil?.takeIf { me?.isFreeTonight() == true },
                                onlineOnly = state.filters.onlineOnly.takeUnless { hidesActivity },
                                picks = state.picks.size,
                                likes = likesCount,
                                onStopTravel = {
                                    haptics.tick()
                                    viewModel.stopTravel()
                                },
                                onToggleFreeTonight = {
                                    haptics.confirm()
                                    viewModel.setFreeTonight(me?.isFreeTonight() != true)
                                },
                                onToggleOnline = {
                                    haptics.confirm()
                                    viewModel.setOnlineOnly(!state.filters.onlineOnly)
                                },
                                onOpenPicks = { picksOpen = true },
                                onOpenLikes = onOpenLikes,
                            )
                            Box(
                                Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                                    .onGloballyPositioned { deckCenter = it.boundsInRoot().center },
                            ) {
                                val phase = when {
                                    state.deck.isNotEmpty() -> DeckPhase.Deck
                                    state.loading -> DeckPhase.Loading
                                    else -> DeckPhase.Empty
                                }
                                // The deck springs up out of the skeleton instead of popping in.
                                AnimatedContent(
                                    targetState = phase,
                                    transitionSpec = {
                                        (fadeIn(tween(260)) + scaleIn(spring(dampingRatio = 0.7f, stiffness = 300f), initialScale = 0.92f)) togetherWith fadeOut(tween(160))
                                    },
                                    label = "deckPhase",
                                ) { current ->
                                    when (current) {
                                        DeckPhase.Deck -> SwipeDeck(
                                            profiles = state.deck,
                                            controller = controller,
                                            onSwiped = { profile, dir -> viewModel.onSwiped(profile, dir) },
                                            onOpen = { profile, index -> onOpenProfile(profile.uid, index) },
                                            modifier = Modifier.fillMaxSize(),
                                            dealKey = state.dealKey,
                                            onDragCommit = ::committed,
                                            viewerHidesActivity = hidesActivity,
                                            // Pulling to refresh fans the stack out a little, like
                                            // riffling a deck; it stays fanned while the new one loads.
                                            fan = {
                                                when {
                                                    reduceMotion -> 0f
                                                    state.refreshing -> 1f
                                                    else -> pullState.distanceFraction.coerceIn(0f, 1f)
                                                }
                                            },
                                        )
                                        DeckPhase.Loading -> DeckSkeleton()
                                        DeckPhase.Empty -> EmptyDeck(
                                            error = state.error,
                                            afterDark = state.afterDark,
                                            narrowedBy = when {
                                                state.filters.onlineOnly && !hidesActivity -> "online right now"
                                                state.filters.freeTonightOnly -> "free tonight"
                                                else -> null
                                            },
                                            onShowEveryone = {
                                                viewModel.applyFilters(state.filters.copy(onlineOnly = false, freeTonightOnly = false))
                                            },
                                            onRetry = viewModel::reload,
                                            onWiden = ::openFilters,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            ActionRow(
                controller = controller,
                canRewind = state.lastSwipe != null && !state.rewinding,
                enabled = state.deck.isNotEmpty(),
                nopeShake = nopeShake.value,
                onLikePositioned = { likeCenter = it },
                onRewind = {
                    haptics.tick()
                    viewModel.rewind()
                },
                onSwipe = { dir ->
                    when (dir) {
                        SwipeDirection.Nope -> haptics.reject()
                        else -> haptics.confirm()
                    }
                    if (controller.top?.offset?.isRunning != true) committed(dir)
                    scope.launch { controller.swipe(dir) }
                },
            )
        }

        if (heartBurst != 0L && likeCenter.isSpecified && deckCenter.isSpecified) {
            HeartBurst(
                key = heartBurst,
                from = likeCenter - overlayOrigin,
                to = deckCenter - overlayOrigin,
                modifier = Modifier.fillMaxSize().onGloballyPositioned { overlayOrigin = it.positionInRoot() },
            )
        }

        val error = state.error
        AnimatedVisibility(
            visible = error != null && state.deck.isNotEmpty(),
            enter = fadeIn() + scaleIn(initialScale = 0.9f),
            exit = fadeOut() + scaleOut(targetScale = 0.9f),
            modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp),
        ) {
            LaunchedEffect(error) {
                kotlinx.coroutines.delay(3_000)
                viewModel.clearError()
            }
            GlassSurface(shape = PillShape, style = GlassDefaults.chrome(), elevated = true) {
                Text(error.orEmpty(), style = EmberTheme.type.bodySmall, color = EmberTheme.colors.textPrimary, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp))
            }
        }

        if (superBurst != 0L) SuperLikeBurst(superBurst)

        FiltersSheet(
            visible = filtersOpen,
            filters = state.filters,
            afterDark = state.afterDark,
            browseFrom = travelDraft,
            onPickCity = { travelPickerOpen = true },
            onClearCity = { travelDraft = null },
            onApply = { filters, afterDark ->
                viewModel.applyFilters(filters)
                if (afterDark != state.afterDark) viewModel.setAfterDark(afterDark)
                filtersOpen = false
            },
            onDismiss = { filtersOpen = false },
        )

        CityPickerSheet(
            visible = travelPickerOpen,
            title = "Browse from",
            subtitle = "See people around another city, nearest first. Your own profile stays where you are.",
            onPick = {
                travelDraft = it
                travelPickerOpen = false
            },
            onDismiss = { travelPickerOpen = false },
            search = viewModel::searchCities,
        )

        PicksSheet(
            visible = picksOpen,
            picks = state.picks,
            fresh = state.picksFresh,
            onOpen = { profile ->
                picksOpen = false
                onOpenProfile(profile.uid, 0)
            },
            onDismiss = { picksOpen = false },
        )

        SpotlightSheet(
            visible = spotlightOpen,
            state = spotlight,
            onStart = {
                haptics.celebrate()
                spotlightVm.start()
            },
            onDismiss = { spotlightOpen = false },
        )

        val celebration = state.celebration
        val ai by viewModel.ai.collectAsStateWithLifecycle()
        AnimatedVisibility(celebration != null, enter = fadeIn(), exit = fadeOut()) {
            if (celebration != null) {
                MatchCelebration(
                    celebration = celebration,
                    myPhotoUrl = myPhoto,
                    ai = ai,
                    onMessage = {
                        viewModel.dismissCelebration()
                        onOpenChat(celebration.match.id)
                    },
                    onDismiss = viewModel::dismissCelebration,
                )
            }
        }
    }
}

private enum class DeckPhase { Loading, Deck, Empty }

/**
 * The row under the top bar: travel mode (when on), Free tonight, Online now, Today's picks and
 * Likes you. It scrolls sideways on narrow phones. [onlineOnly] is null when I hide my activity
 * (then I can't see anyone's, so there's no chip).
 */
@Composable
private fun QuickChips(
    travel: Place?,
    freeTonightUntil: Instant?,
    onlineOnly: Boolean?,
    picks: Int,
    likes: Int,
    onStopTravel: () -> Unit,
    onToggleFreeTonight: () -> Unit,
    onToggleOnline: () -> Unit,
    onOpenPicks: () -> Unit,
    onOpenLikes: () -> Unit,
) {
    val colors = EmberTheme.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (travel != null) {
            QuickChip(
                "Browsing ${travel.name}",
                active = true,
                icon = Icons.Rounded.FlightTakeoff,
                trailing = Icons.Rounded.Close,
                a11y = "Browsing from ${travel.name}. Tap to turn travel mode off.",
                onClick = onStopTravel,
            )
        }
        val until = freeTonightUntil?.let { DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault())) }
        QuickChip(
            if (until != null) "Free till $until" else "Free tonight",
            active = until != null,
            emoji = "🌙",
            a11y = if (until != null) "Free tonight is on until $until. Tap to turn it off." else "Tell people you're free tonight",
            onClick = onToggleFreeTonight,
        )
        if (onlineOnly != null) {
            QuickChip(
                "Online now",
                active = onlineOnly,
                leading = { OnlineDot(size = 8.dp, color = if (onlineOnly) colors.onAccent else colors.like, contentDescription = null) },
                a11y = if (onlineOnly) "Showing only people online now. Tap to show everyone." else "Show only people online now",
                onClick = onToggleOnline,
            )
        }
        QuickChip(
            if (picks > 0) "Today's picks · $picks" else "Today's picks",
            active = false,
            emoji = "✨",
            a11y = if (picks > 0) "Today's picks, $picks people" else "Today's picks",
            onClick = onOpenPicks,
        )
        QuickChip(
            if (likes > 0) "Likes you · $likes" else "Likes you",
            active = false,
            icon = Icons.Rounded.Favorite,
            iconTint = colors.like,
            a11y = when (likes) {
                0 -> "Likes you"
                1 -> "Likes you, 1 person"
                else -> "Likes you, $likes people"
            },
            onClick = onOpenLikes,
        )
    }
}

@Composable
private fun QuickChip(
    text: String,
    active: Boolean,
    onClick: () -> Unit,
    emoji: String? = null,
    icon: ImageVector? = null,
    iconTint: Color? = null,
    trailing: ImageVector? = null,
    a11y: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = EmberTheme.colors
    val fill by animateFloatAsState(if (active) 1f else 0f, label = "chipFill")
    val content by animateColorAsState(if (active) colors.onAccent else colors.textPrimary, label = "chipText")
    Row(
        Modifier
            .pressable(onClick = onClick, pressedScale = 0.94f, haptic = false)
            .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
            .background(colors.accentHorizontal, PillShape, alpha = fill)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .then(if (a11y != null) Modifier.semantics(mergeDescendants = true) { contentDescription = a11y } else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(4.dp))
        }
        if (emoji != null) {
            Text(emoji, style = EmberTheme.type.caption)
            Spacer(Modifier.width(6.dp))
        }
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = if (active) content else iconTint ?: content, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = EmberTheme.type.caption, color = content)
        if (trailing != null) {
            Spacer(Modifier.width(6.dp))
            Icon(trailing, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        }
    }
}

/** On narrow phones (with Spotlight beside it) the pill drops its label and keeps the moon. */
@Composable
private fun AfterDarkPill(enabled: Boolean, onToggle: () -> Unit) {
    val colors = EmberTheme.colors
    val fill by animateFloatAsState(if (enabled) 1f else 0f, label = "afterDark")
    val compact = LocalConfiguration.current.screenWidthDp < 380
    Row(
        Modifier
            .pressable(onClick = onToggle, role = Role.Switch)
            .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
            .background(colors.accentHorizontal, PillShape, alpha = fill)
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = 10.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = "After Dark"
                stateDescription = if (enabled) "On" else "Off"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = if (enabled) Color.White else colors.textPrimary, modifier = Modifier.size(18.dp))
        if (!compact) {
            Spacer(Modifier.width(6.dp))
            Text("After Dark", style = EmberTheme.type.caption, color = if (enabled) Color.White else colors.textPrimary)
        }
    }
}

@Composable
private fun ActionRow(
    controller: DeckController,
    canRewind: Boolean,
    enabled: Boolean,
    nopeShake: Float,
    onLikePositioned: (Offset) -> Unit,
    onRewind: () -> Unit,
    onSwipe: (SwipeDirection) -> Unit,
) {
    val colors = EmberTheme.colors
    val top = controller.top
    // The buttons answer the drag: the one you're heading toward swells and fills.
    val like = top?.horizontal?.coerceAtLeast(0f) ?: 0f
    val nope = top?.horizontal?.let { -it }?.coerceAtLeast(0f) ?: 0f
    val superLike = top?.vertical ?: 0f
    Row(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionButton(Icons.Rounded.Replay, "Rewind", colors.accentWarm, 50.dp, 0f, canRewind, onClick = onRewind)
        ActionButton(Icons.Rounded.Close, "Nope", colors.nope, 66.dp, nope, enabled, shakeDp = nopeShake) { onSwipe(SwipeDirection.Nope) }
        ActionButton(Icons.Rounded.Star, "Super like", colors.superLike, 50.dp, superLike, enabled) { onSwipe(SwipeDirection.Super) }
        ActionButton(
            Icons.Rounded.Favorite,
            "Like",
            colors.like,
            66.dp,
            like,
            enabled,
            modifier = Modifier.onGloballyPositioned { onLikePositioned(it.boundsInRoot().center) },
        ) { onSwipe(SwipeDirection.Like) }
    }
}

@Composable
private fun ActionButton(
    icon: ImageVector,
    label: String,
    tint: Color,
    size: Dp,
    activation: Float,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    shakeDp: Float = 0f,
    onClick: () -> Unit,
) {
    // Disabled buttons dim their icon and drop their shadow; fading the whole layer would clip
    // the shadow into a visible square.
    val iconTint by animateColorAsState(
        when {
            !enabled -> tint.copy(alpha = 0.35f)
            activation > 0.5f -> Color.White
            else -> tint
        },
        label = "iconTint",
    )
    Box(
        modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + activation * 0.16f
                scaleX = s
                scaleY = s
                translationX = shakeDp * density
            }
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.86f, haptic = false)
            .glass(CircleShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = enabled)
            .background(tint.copy(alpha = activation), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(size * 0.46f))
    }
}

/**
 * Nobody left to show. When a quick filter ([narrowedBy], e.g. "online right now") is what emptied
 * the deck, the first button simply turns it off.
 */
@Composable
private fun EmptyDeck(
    error: String?,
    afterDark: Boolean,
    narrowedBy: String?,
    onShowEveryone: () -> Unit,
    onRetry: () -> Unit,
    onWiden: () -> Unit,
) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxSize(), shape = EmberShapes.card) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmberFlame(size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    error != null -> "Hmm, that didn't load"
                    narrowedBy != null -> "Nobody's $narrowedBy"
                    else -> "You've seen everyone nearby"
                },
                style = EmberTheme.type.title,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    error != null -> error
                    narrowedBy != null -> "Check back in a bit, or see everyone in the meantime."
                    afterDark -> "After Dark only shows people open to casual or adventurous connections. Widen your filters or check back later."
                    else -> "Widen your distance or age range, or check back later. New people join every day."
                },
                style = EmberTheme.type.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            if (error == null && narrowedBy != null) {
                EmberButton("Show everyone", onClick = onShowEveryone, modifier = Modifier.fillMaxWidth())
            } else {
                EmberButton(if (error != null) "Try again" else "Refresh", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            }
            Spacer(Modifier.height(10.dp))
            GlassButton("Adjust filters", onClick = onWiden, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** The loading deck: a card-shaped shimmer with the name, place and tags sketched in. */
@Composable
private fun DeckSkeleton() {
    Box(
        Modifier
            .fillMaxSize()
            .shimmer(EmberShapes.card)
            .semantics { contentDescription = "Loading people near you" },
    ) {
        Column(Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(Modifier.width(170.dp).height(28.dp).shimmer(EmberShapes.small))
            Box(Modifier.width(120.dp).height(14.dp).shimmer(EmberShapes.small))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { Box(Modifier.width(78.dp).height(28.dp).shimmer(PillShape)) }
            }
        }
    }
}

@Composable
private fun FiltersSheet(
    visible: Boolean,
    filters: DiscoveryFilters,
    afterDark: Boolean,
    browseFrom: Place?,
    onPickCity: () -> Unit,
    onClearCity: () -> Unit,
    onApply: (DiscoveryFilters, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var distance by remember(filters, visible) { mutableStateOf(filters.maxDistanceKm.toFloat()) }
    var ages by remember(filters, visible) { mutableStateOf(filters.minAge.toFloat()..filters.maxAge.toFloat()) }
    var lookingFor by remember(filters, visible) { mutableStateOf(filters.lookingFor) }
    var freeOnly by remember(filters, visible) { mutableStateOf(filters.freeTonightOnly) }
    var dark by remember(afterDark, visible) { mutableStateOf(afterDark) }
    val colors = EmberTheme.colors
    val sliderColors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.surfaceHigh)
    val switchColors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White)
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Discovery", style = EmberTheme.type.headline, color = colors.textPrimary)
            Spacer(Modifier.height(18.dp))
            SectionHeader("Browse from")
            Spacer(Modifier.height(10.dp))
            GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onPickCity, pressedScale = 0.98f), shape = EmberShapes.tile) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.FlightTakeoff, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(browseFrom?.name ?: "My location", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                        Text(
                            if (browseFrom != null) "Travel mode: distances start from here" else "Tap to browse from another city",
                            style = EmberTheme.type.caption,
                            color = colors.textSecondary,
                        )
                    }
                    if (browseFrom != null) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Back to my location",
                            tint = colors.textSecondary,
                            modifier = Modifier.size(32.dp).pressable(onClick = onClearCity).padding(6.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row {
                SectionHeader("Maximum distance", Modifier.weight(1f))
                Text(if (distance >= 160f) "Anywhere" else "${distance.roundToInt()} km", style = EmberTheme.type.label, color = colors.accent)
            }
            Slider(value = distance, onValueChange = { distance = it }, valueRange = 5f..160f, colors = sliderColors)
            Spacer(Modifier.height(10.dp))
            Row {
                SectionHeader("Age range", Modifier.weight(1f))
                Text("${ages.start.roundToInt()} – ${ages.endInclusive.roundToInt()}", style = EmberTheme.type.label, color = colors.accent)
            }
            RangeSlider(value = ages, onValueChange = { ages = it }, valueRange = 18f..80f, colors = sliderColors)
            Spacer(Modifier.height(14.dp))
            SectionHeader("Looking for")
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LookingFor.entries.forEach { option ->
                    EmberChip(option.label, selected = option in lookingFor, emoji = option.emoji, onClick = {
                        lookingFor = if (option in lookingFor) lookingFor - option else lookingFor + option
                    })
                }
            }
            Spacer(Modifier.height(18.dp))
            GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("🌙 Free tonight only", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                        Text("Just the people who said they're free in the next few hours.", style = EmberTheme.type.caption, color = colors.textSecondary)
                    }
                    Switch(freeOnly, { freeOnly = it }, colors = switchColors)
                }
            }
            Spacer(Modifier.height(10.dp))
            GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("After Dark", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                        Text("Darker theme, and only people open to casual or adventurous.", style = EmberTheme.type.caption, color = colors.textSecondary)
                    }
                    Switch(dark, { dark = it }, colors = switchColors)
                }
            }
            Spacer(Modifier.height(20.dp))
            EmberButton(
                "Show people",
                onClick = {
                    onApply(
                        DiscoveryFilters(
                            maxDistanceKm = distance.roundToInt(),
                            minAge = ages.start.roundToInt(),
                            maxAge = ages.endInclusive.roundToInt(),
                            lookingFor = lookingFor,
                            freeTonightOnly = freeOnly,
                            // Set by its own chip; the sheet leaves it as it was.
                            onlineOnly = filters.onlineOnly,
                            browseFrom = browseFrom,
                        ),
                        dark,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A blue star that pops and fades over the deck when someone is super-liked. */
@Composable
private fun SuperLikeBurst(key: Long) {
    val colors = EmberTheme.colors
    val progress = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { progress.animateTo(1f, tween(650)) }
    val t = progress.value
    if (t >= 1f) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(
            Icons.Rounded.Star,
            contentDescription = null,
            tint = colors.superLike,
            modifier = Modifier
                .size(160.dp)
                .graphicsLayer {
                    val s = 0.4f + t * 1.1f
                    scaleX = s
                    scaleY = s
                    alpha = (1f - t) * (1f - t)
                    rotationZ = t * 40f
                },
        )
    }
}
