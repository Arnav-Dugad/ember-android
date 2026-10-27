package io.github.arnavdugad.ember.ui.profile

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.analytics.Analytics
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.Presence
import io.github.arnavdugad.ember.data.model.Spotlight
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SpotlightUsedException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

data class SpotlightState(
    /** When the current (or last) Spotlight ends. */
    val until: Instant? = null,
    /** Today's (UTC) boost is spent. */
    val usedToday: Boolean = false,
    val paused: Boolean = false,
    val starting: Boolean = false,
    val error: String? = null,
) {
    fun isActive(now: Instant = Instant.now()): Boolean = until?.isAfter(now) == true
}

/** Spotlight for the Me screen and Discover: one 30-minute boost per UTC day. */
@HiltViewModel
class SpotlightViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val analytics: Analytics,
) : ViewModel() {
    private val local = MutableStateFlow(SpotlightState())

    val state: StateFlow<SpotlightState> = combine(profiles.observeMyProfile().catch { emit(null) }, local) { me, s ->
        s.copy(until = listOfNotNull(me?.spotlightUntil, s.until).maxOrNull(), paused = me?.paused == true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SpotlightState())

    init {
        refresh()
    }

    /** Whether today's boost is spent comes from the server's counter, not this phone's memory. */
    fun refresh() {
        viewModelScope.launch {
            val used = runCatching { profiles.spotlightUsedToday() }.getOrNull() ?: return@launch
            local.update { it.copy(usedToday = used) }
        }
    }

    fun start() {
        if (local.value.starting) return
        viewModelScope.launch {
            local.update { it.copy(starting = true, error = null) }
            val result = runCatching { profiles.startSpotlight() }
            local.update { s ->
                result.fold(
                    onSuccess = { until -> s.copy(until = until, usedToday = true, starting = false) },
                    onFailure = { e -> s.copy(starting = false, usedToday = s.usedToday || e is SpotlightUsedException, error = e.message ?: "Couldn't start Spotlight. Try again.") },
                )
            }
            if (result.isSuccess) analytics.log("spotlight")
        }
    }

    fun clearError() = local.update { it.copy(error = null) }
}

/** The clock, ticking once a second while [until] is in the future (then it stops). */
@Composable
fun rememberCountdownNow(until: Instant?): Instant {
    val now by produceState(Instant.now(), until) {
        while (until != null && until.isAfter(value)) {
            delay(1_000L - System.currentTimeMillis() % 1_000L)
            value = Instant.now()
        }
    }
    return now
}

/** A ring that drains as the Spotlight runs, around [content]. */
@Composable
fun SpotlightRing(until: Instant?, modifier: Modifier = Modifier, stroke: Dp = 3.dp, content: @Composable () -> Unit) {
    val colors = EmberTheme.colors
    val now = rememberCountdownNow(until)
    val fraction = Spotlight.remainingFraction(until, now)
    Box(modifier, contentAlignment = Alignment.Center) {
        content()
        if (fraction > 0f) {
            Canvas(Modifier.fillMaxSize()) {
                val w = stroke.toPx()
                val inset = w / 2f
                val arcSize = Size(size.width - w, size.height - w)
                drawArc(colors.textTertiary.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(w))
                drawArc(
                    Brush.sweepGradient(colors.gradient + colors.gradient.first()),
                    startAngle = -90f,
                    sweepAngle = 360f * fraction,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(w, cap = StrokeCap.Round),
                )
            }
        }
    }
}

/** Discover's top-bar button: a bolt in glass, wearing the countdown ring while active. */
@Composable
fun SpotlightButton(state: SpotlightState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val now = rememberCountdownNow(state.until)
    val active = state.isActive(now)
    val label = if (active) "Spotlight, ${Spotlight.remainingLabel(state.until!!, now)} left" else "Spotlight"
    SpotlightRing(
        until = state.until,
        modifier = modifier
            .size(44.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = label
                role = Role.Button
            },
    ) {
        Box(
            Modifier
                .size(44.dp)
                .pressable(onClick = onClick, pressedScale = 0.9f)
                .glass(CircleShape, GlassDefaults.surface(), LocalHazeState.current),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Bolt, contentDescription = null, tint = if (active) colors.accentWarm else colors.textPrimary, modifier = Modifier.size(22.dp))
        }
    }
}

/** The Me screen's row: what Spotlight does, or how long is left. Tapping opens [SpotlightSheet]. */
@Composable
fun SpotlightCard(state: SpotlightState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val now = rememberCountdownNow(state.until)
    val active = state.isActive(now)
    GlassSurface(modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.panel) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            SpotlightRing(state.until, Modifier.size(42.dp), stroke = 2.5.dp) {
                Box(Modifier.size(if (active) 34.dp else 42.dp).background(colors.accentBrush, if (active) CircleShape else EmberShapes.small), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text("Spotlight", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(spotlightSubtitle(state, now), style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(active, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "spotlightTrailing") { on ->
                if (on) {
                    Text(Spotlight.remainingLabel(state.until!!, now), style = EmberTheme.type.label, color = colors.accent)
                } else if (!state.usedToday) {
                    Text(
                        "Boost",
                        style = EmberTheme.type.caption,
                        color = colors.onAccent,
                        modifier = Modifier.background(colors.accentHorizontal, PillShape).padding(horizontal = 14.dp, vertical = 9.dp),
                    )
                }
            }
        }
    }
}

/** Explains Spotlight and starts it (or shows the countdown). Starting is a deliberate tap here. */
@Composable
fun SpotlightSheet(visible: Boolean, state: SpotlightState, onStart: () -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    val now = rememberCountdownNow(state.until)
    val active = state.isActive(now)
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            SpotlightRing(state.until, Modifier.size(96.dp), stroke = 5.dp) {
                Box(Modifier.size(78.dp).background(colors.accentBrush, CircleShape), contentAlignment = Alignment.Center) {
                    if (active) {
                        Text(Spotlight.remainingLabel(state.until!!, now), style = EmberTheme.type.title, color = Color.White)
                    } else {
                        Icon(Icons.Rounded.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(40.dp))
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(if (active) "You're in the Spotlight" else "Spotlight", style = EmberTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(
                when {
                    active -> "For the next ${Duration.between(now, state.until).toMinutes().coerceAtLeast(1)} min you're among the first people anyone nearby sees in Discover."
                    else -> "Jump to the front of everyone's deck for 30 minutes. One boost a day, on us."
                },
                style = EmberTheme.type.bodySmall,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            when {
                active -> EmberButton("Done", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                state.paused -> Note("Your profile is paused, so nobody new can see you. Unpause it in Privacy first.")
                state.usedToday -> Note("You've used today's Spotlight. The next one is ready at ${nextBoostTime(now)}.")
                else -> EmberButton("Start Spotlight", onClick = onStart, loading = state.starting, icon = Icons.Rounded.Bolt, modifier = Modifier.fillMaxWidth())
            }
            state.error?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = EmberTheme.type.caption, color = colors.danger, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
        Text(text, style = EmberTheme.type.bodySmall, color = colors.textSecondary, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

private fun spotlightSubtitle(state: SpotlightState, now: Instant): String = when {
    state.isActive(now) -> "You're being shown first in Discover."
    state.paused -> "Unpause your profile to use it."
    state.usedToday -> "Used today. Next one at ${nextBoostTime(now)}."
    else -> "Be seen first for 30 minutes. Once a day."
}

/** Boosts reset at midnight UTC; this is that moment on the phone's clock. */
private fun nextBoostTime(now: Instant): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(Presence.dayStart(now).plus(Duration.ofDays(1)).atZone(ZoneId.systemDefault()))
