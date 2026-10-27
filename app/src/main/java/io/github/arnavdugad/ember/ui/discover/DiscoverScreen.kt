package io.github.arnavdugad.ember.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
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
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.ui.main.BottomBarSpace
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun DiscoverScreen(
    viewModel: DiscoverViewModel,
    onOpenProfile: (uid: String, photoIndex: Int) -> Unit,
    onOpenChat: (matchId: String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val myPhoto by viewModel.myPhotoUrl.collectAsStateWithLifecycle()
    val controller = remember { DeckController() }
    val scope = rememberCoroutineScope()
    var filtersOpen by remember { mutableStateOf(false) }
    val haptics = EmberTheme.haptics

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
                Spacer(Modifier.width(10.dp))
                GlassIconButton(Icons.Rounded.Tune, contentDescription = "Filters", onClick = { filtersOpen = true })
            }
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                when {
                    state.deck.isNotEmpty() -> SwipeDeck(
                        profiles = state.deck,
                        controller = controller,
                        onSwiped = viewModel::onSwiped,
                        onOpen = { profile, index -> onOpenProfile(profile.uid, index) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    state.loading -> Box(Modifier.fillMaxSize().shimmer(EmberShapes.card))
                    else -> EmptyDeck(
                        error = state.error,
                        afterDark = state.afterDark,
                        onRetry = viewModel::reload,
                        onWiden = { filtersOpen = true },
                    )
                }
            }
            ActionRow(
                controller = controller,
                canRewind = state.lastSwipe != null && !state.rewinding,
                enabled = state.deck.isNotEmpty(),
                onRewind = {
                    haptics.tick()
                    viewModel.rewind()
                },
                onSwipe = { dir ->
                    when (dir) {
                        SwipeDirection.Nope -> haptics.reject()
                        else -> haptics.confirm()
                    }
                    scope.launch { controller.swipe(dir) }
                },
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

        FiltersSheet(
            visible = filtersOpen,
            filters = state.filters,
            afterDark = state.afterDark,
            onApply = { filters, afterDark ->
                viewModel.applyFilters(filters)
                if (afterDark != state.afterDark) viewModel.setAfterDark(afterDark)
                filtersOpen = false
            },
            onDismiss = { filtersOpen = false },
        )

        val celebration = state.celebration
        AnimatedVisibility(celebration != null, enter = fadeIn(), exit = fadeOut()) {
            if (celebration != null) {
                MatchCelebration(
                    celebration = celebration,
                    myPhotoUrl = myPhoto,
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

@Composable
private fun AfterDarkPill(enabled: Boolean, onToggle: () -> Unit) {
    val colors = EmberTheme.colors
    val fill by animateFloatAsState(if (enabled) 1f else 0f, label = "afterDark")
    Row(
        Modifier
            .pressable(onClick = onToggle)
            .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
            .background(colors.accentHorizontal, PillShape, alpha = fill)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.NightsStay, contentDescription = null, tint = if (enabled) Color.White else colors.textPrimary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("After Dark", style = EmberTheme.type.caption, color = if (enabled) Color.White else colors.textPrimary)
    }
}

@Composable
private fun ActionRow(
    controller: DeckController,
    canRewind: Boolean,
    enabled: Boolean,
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
        ActionButton(Icons.Rounded.Replay, "Rewind", colors.accentWarm, 50.dp, 0f, canRewind, onRewind)
        ActionButton(Icons.Rounded.Close, "Nope", colors.nope, 66.dp, nope, enabled) { onSwipe(SwipeDirection.Nope) }
        ActionButton(Icons.Rounded.Star, "Super like", colors.superLike, 50.dp, superLike, enabled) { onSwipe(SwipeDirection.Super) }
        ActionButton(Icons.Rounded.Favorite, "Like", colors.like, 66.dp, like, enabled) { onSwipe(SwipeDirection.Like) }
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
    onClick: () -> Unit,
) {
    val iconTint by animateColorAsState(if (activation > 0.5f) Color.White else tint, label = "iconTint")
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                val s = 1f + activation * 0.16f
                scaleX = s
                scaleY = s
            }
            .alpha(if (enabled) 1f else 0.4f)
            .pressable(onClick = onClick, enabled = enabled, pressedScale = 0.86f, haptic = false)
            .glass(CircleShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true)
            .background(tint.copy(alpha = activation), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = iconTint, modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
private fun EmptyDeck(error: String?, afterDark: Boolean, onRetry: () -> Unit, onWiden: () -> Unit) {
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
                if (error != null) "Hmm, that didn't load" else "You've seen everyone nearby",
                style = EmberTheme.type.title,
                color = colors.textPrimary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                error ?: if (afterDark) "After Dark only shows people open to casual or adventurous connections. Widen your filters or check back later." else "Widen your distance or age range, or check back later. New people join every day.",
                style = EmberTheme.type.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            EmberButton(if (error != null) "Try again" else "Refresh", onClick = onRetry, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            GlassButton("Adjust filters", onClick = onWiden, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun FiltersSheet(
    visible: Boolean,
    filters: DiscoveryFilters,
    afterDark: Boolean,
    onApply: (DiscoveryFilters, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var distance by remember(filters, visible) { mutableStateOf(filters.maxDistanceKm.toFloat()) }
    var ages by remember(filters, visible) { mutableStateOf(filters.minAge.toFloat()..filters.maxAge.toFloat()) }
    var lookingFor by remember(filters, visible) { mutableStateOf(filters.lookingFor) }
    var dark by remember(afterDark, visible) { mutableStateOf(afterDark) }
    val colors = EmberTheme.colors
    val sliderColors = SliderDefaults.colors(thumbColor = colors.accent, activeTrackColor = colors.accent, inactiveTrackColor = colors.surfaceHigh)
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Text("Discovery", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(18.dp))
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
                    Text("After Dark", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                    Text("Darker theme, and only people open to casual or adventurous.", style = EmberTheme.type.caption, color = colors.textSecondary)
                }
                Switch(dark, { dark = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White))
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
                    ),
                    dark,
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
