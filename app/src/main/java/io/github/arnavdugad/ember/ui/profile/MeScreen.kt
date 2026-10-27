package io.github.arnavdugad.ember.ui.profile

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MeViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val settings: SettingsRepository,
) : ViewModel() {
    val profile: StateFlow<Profile?> = profiles.observeMyProfile().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val afterDark: StateFlow<Boolean> = settings.settings.map { it.afterDark }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun setAfterDark(enabled: Boolean) {
        viewModelScope.launch { settings.setAfterDark(enabled) }
    }
}

/** How complete a profile is, for the progress ring nudge. */
private fun Profile.completeness(): Float {
    val checks = listOf(
        photos.size >= 3,
        bio.length >= 40,
        prompts.isNotEmpty(),
        prompts.size >= 2,
        vibes.size >= 2,
        interests.size >= 3,
        heightCm != null,
        city.isNotBlank(),
    )
    return checks.count { it } / checks.size.toFloat()
}

@Composable
fun MeScreen(onEdit: () -> Unit, onSettings: () -> Unit, onAlbum: () -> Unit, viewModel: MeViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val afterDark by viewModel.afterDark.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
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
                        val complete = p.completeness()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(120.dp).height(6.dp).background(Color.White.copy(alpha = 0.3f), PillShape)) {
                                Box(Modifier.fillMaxWidth(complete).height(6.dp).background(colors.accentHorizontal, PillShape))
                            }
                            Spacer(Modifier.width(10.dp))
                            Text("${(complete * 100).toInt()}% complete", style = EmberTheme.type.caption, color = Color.White)
                        }
                    }
                }
            }
            MenuItem(Icons.Rounded.Edit, "Edit profile", "Photos, prompts, vibes and more", onEdit)
            MenuItem(
                Icons.Rounded.Lock,
                "Private album",
                "${p?.privateAlbumCount ?: 0} photos · only people you approve can see them",
                onAlbum,
            )
            GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconTile(Icons.Rounded.NightsStay)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("After Dark", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                        Text("Moodier theme, casual and adventurous people.", style = EmberTheme.type.caption, color = colors.textSecondary)
                    }
                    Switch(
                        checked = afterDark,
                        onCheckedChange = {
                            haptics.confirm()
                            viewModel.setAfterDark(it)
                        },
                        colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
                    )
                }
            }
            MenuItem(Icons.Rounded.Settings, "Settings", "Appearance, safety, updates, account", onSettings)
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
