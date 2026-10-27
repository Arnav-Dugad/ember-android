package io.github.arnavdugad.ember.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.ui.onboarding.AboutSection
import io.github.arnavdugad.ember.ui.onboarding.BasicsSection
import io.github.arnavdugad.ember.ui.onboarding.IntentSection
import io.github.arnavdugad.ember.ui.onboarding.LocationSection
import io.github.arnavdugad.ember.ui.onboarding.PhotosSection
import io.github.arnavdugad.ember.ui.onboarding.ProfileEditorViewModel
import io.github.arnavdugad.ember.ui.onboarding.PromptsSection
import io.github.arnavdugad.ember.ui.onboarding.SetupStep
import io.github.arnavdugad.ember.ui.onboarding.VibesSection

/** Every onboarding section on one scrolling page. */
@Composable
fun EditProfileScreen(onBack: () -> Unit, viewModel: ProfileEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Text("Edit profile", style = EmberTheme.type.title, color = colors.textPrimary)
            }
            if (!state.loaded) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = colors.accent) }
                return@Column
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(30.dp),
            ) {
                SetupStep.entries.forEach { step ->
                    Column {
                        SectionHeader(step.title)
                        Spacer(Modifier.padding(top = 12.dp))
                        when (step) {
                            SetupStep.Basics -> BasicsSection(state, viewModel)
                            SetupStep.Photos -> PhotosSection(state, viewModel)
                            SetupStep.About -> AboutSection(state, viewModel)
                            SetupStep.Prompts -> PromptsSection(state, viewModel)
                            SetupStep.Vibes -> VibesSection(state, viewModel)
                            SetupStep.Intent -> IntentSection(state, viewModel)
                            SetupStep.Location -> LocationSection(state, viewModel)
                        }
                    }
                }
            }
            Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
                state.error?.let { Text(it, style = EmberTheme.type.bodySmall, color = colors.danger, modifier = Modifier.padding(bottom = 8.dp)) }
                EmberButton("Save", onClick = { viewModel.save(finishOnboarding = false) }, loading = state.saving, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
