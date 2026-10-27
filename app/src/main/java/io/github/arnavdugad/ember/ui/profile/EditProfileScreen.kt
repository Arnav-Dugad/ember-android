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
import io.github.arnavdugad.ember.ui.onboarding.ProfileSheets
import io.github.arnavdugad.ember.ui.onboarding.ProfileSectionSheets
import io.github.arnavdugad.ember.ui.onboarding.LocalProfileSheets
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
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
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Every onboarding section on one scrolling page. [initialSection] (from a profile-strength tip)
 * scrolls straight to that section once it has been laid out.
 */
@Composable
fun EditProfileScreen(onBack: () -> Unit, initialSection: EditSection? = null, viewModel: ProfileEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val scroll = rememberScrollState()
    val bounds = remember { mutableStateMapOf<SetupStep, Pair<Float, Float>>() }
    var viewport by remember { mutableIntStateOf(0) }
    var scrolled by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(state.saved) { if (state.saved) onBack() }
    LaunchedEffect(state.loaded, bounds.size, viewport) {
        val section = initialSection ?: return@LaunchedEffect
        if (scrolled || !state.loaded || viewport == 0) return@LaunchedEffect
        val (top, height) = bounds[section.step] ?: return@LaunchedEffect
        scrolled = true
        // The voice intro sits at the end of "About you": bring the bottom of that section into view.
        val target = if (section == EditSection.Voice) (top + height - viewport * 0.9f).coerceAtLeast(top) else top
        delay(200)
        scroll.animateScrollTo(target.roundToInt())
    }
    val sheets = remember { ProfileSheets() }
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        CompositionLocalProvider(LocalProfileSheets provides sheets) {
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
                    Modifier.weight(1f).onSizeChanged { viewport = it.height }.verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(30.dp),
                ) {
                    SetupStep.entries.forEach { step ->
                        Column(Modifier.onGloballyPositioned { bounds[step] = it.positionInParent().y to it.size.height.toFloat() }) {
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
        ProfileSectionSheets(state, viewModel, sheets)
    }
}
