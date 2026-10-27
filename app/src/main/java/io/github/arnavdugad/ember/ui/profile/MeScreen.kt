package io.github.arnavdugad.ember.ui.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.ConfettiBurst
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.ui.main.BottomBarSpace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import javax.inject.Inject

@HiltViewModel
class MeViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val profile: StateFlow<Profile?> = profiles.observeMyProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val afterDark: StateFlow<Boolean> = settings.settings.map { it.afterDark }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _confetti = MutableStateFlow(0L)

    /** Non-zero (a fresh key) when the profile has just reached 100% for the first time. */
    val confetti: StateFlow<Long> = _confetti.asStateFlow()

    fun setAfterDark(enabled: Boolean) {
        viewModelScope.launch { settings.setAfterDark(enabled) }
    }

    fun setFreeTonight(enabled: Boolean) {
        viewModelScope.launch { runCatching { profiles.setFreeTonight(enabled) } }
    }

    private var checking = false

    /** Fires the confetti once per account, ever, the first time the Me screen sees 100%. */
    fun checkComplete(profile: Profile) {
        if (checking || ProfileStrength.completeness(profile) < 1f) return
        checking = true
        viewModelScope.launch {
            if (!settings.hasCelebratedComplete(profile.uid)) {
                settings.setCelebratedComplete(profile.uid)
                _confetti.value = System.nanoTime()
            }
            checking = false
        }
    }
}

@Composable
fun MeScreen(
    onEdit: (EditSection?) -> Unit,
    onSettings: () -> Unit,
    onAlbum: () -> Unit,
    onPreview: (uid: String) -> Unit,
    viewModel: MeViewModel = hiltViewModel(),
) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val afterDark by viewModel.afterDark.collectAsStateWithLifecycle()
    val confetti by viewModel.confetti.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    LaunchedEffect(profile) { profile?.let(viewModel::checkComplete) }
    LaunchedEffect(confetti) { if (confetti != 0L) haptics.celebrate() }
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow() }) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 16.dp, bottom = BottomBarSpace + 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val p = profile
            Box(Modifier.fillMaxWidth().aspectRatio(0.9f).clip(EmberShapes.card)) {
                if (p == null) {
                    Box(Modifier.fillMaxSize().shimmer(EmberShapes.card))
                } else {
                    EmberImage(p.firstPhoto?.url, p.firstPhoto?.blurHash, contentDescription = "Your photo", modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.75f))),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(22.dp)) {
                        Text("${p.name}, ${p.age}", style = EmberTheme.type.headline, color = Color.White)
                        Text(p.city, style = EmberTheme.type.bodySmall, color = Color.White.copy(alpha = 0.85f))
                        Spacer(Modifier.height(12.dp))
                        val complete by animateFloatAsState(ProfileStrength.completeness(p), spring(dampingRatio = 0.8f, stiffness = 120f), label = "complete")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(120.dp).height(6.dp).background(Color.White.copy(alpha = 0.3f), PillShape)) {
                                Box(Modifier.fillMaxWidth(complete).height(6.dp).background(colors.accentHorizontal, PillShape))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("${(ProfileStrength.completeness(p) * 100).toInt()}% complete", style = EmberTheme.type.caption, color = Color.White)
                        }
                    }
                }
            }
            val tip = p?.let(ProfileStrength::nextTip)
            AnimatedVisibility(tip != null, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                if (tip != null) TipCard(tip, onClick = { onEdit(tip.section) })
            }
            MenuItem(Icons.Rounded.Edit, "Edit profile", "Photos, prompts, voice intro and more") { onEdit(null) }
            p?.let { MenuItem(Icons.Rounded.Visibility, "Preview my profile", "See exactly what others see", onClick = { onPreview(it.uid) }) }
            MenuItem(
                Icons.Rounded.Lock,
                "Private album",
                "${p?.privateAlbumCount ?: 0} photos · only people you approve can see them",
                onAlbum,
            )
            val freeUntil = p?.freeTonightUntil?.takeIf { p.isFreeTonight() }
            ToggleRow(
                icon = Icons.Rounded.Bedtime,
                title = "Free tonight",
                subtitle = freeUntil?.let { "On until ${DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).format(it.atZone(ZoneId.systemDefault()))}. A 🌙 badge shows on your card." }
                    ?: "Show a 🌙 badge on your card for the next 6 hours.",
                checked = freeUntil != null,
                enabled = p != null,
                onChange = {
                    haptics.confirm()
                    viewModel.setFreeTonight(it)
                },
            )
            ToggleRow(
                icon = Icons.Rounded.NightsStay,
                title = "After Dark",
                subtitle = "Moodier theme, casual and adventurous people.",
                checked = afterDark,
                onChange = {
                    haptics.confirm()
                    viewModel.setAfterDark(it)
                },
            )
            MenuItem(Icons.Rounded.Settings, "Settings", "Appearance, safety, updates, account", onSettings)
        }
        if (confetti != 0L) ConfettiBurst(key = confetti, modifier = Modifier.fillMaxSize())
    }
}

/** The single most valuable next step for a stronger profile, with a button straight to it. */
@Composable
private fun TipCard(tip: StrengthTip, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text(tip.text, style = EmberTheme.type.bodySmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(12.dp))
            Text(
                tip.action,
                style = EmberTheme.type.caption,
                color = colors.onAccent,
                modifier = Modifier
                    .pressable(onClick = onClick, pressedScale = 0.94f)
                    .background(colors.accentHorizontal, PillShape)
                    .padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun ToggleRow(icon: ImageVector, title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(subtitle, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Switch(
                checked = checked,
                onCheckedChange = onChange,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
private fun IconTile(icon: ImageVector) {
    Box(Modifier.size(42.dp).background(EmberTheme.colors.accentBrush, EmberShapes.small), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.panel) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            IconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(subtitle, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
        }
    }
}
