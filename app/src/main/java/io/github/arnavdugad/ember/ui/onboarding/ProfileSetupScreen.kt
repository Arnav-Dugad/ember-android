package io.github.arnavdugad.ember.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.ScreenTitle

/** The multi-step profile wizard. Each step slides in; the gradient bar springs to the progress. */
@Composable
fun ProfileSetupScreen(viewModel: ProfileEditorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var stepError by rememberSaveable { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val steps = SetupStep.entries
    val step = steps[stepIndex]
    val last = stepIndex == steps.lastIndex
    BackHandler(enabled = stepIndex > 0) { stepIndex-- }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow() }) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                if (stepIndex > 0) {
                    GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = { stepIndex-- })
                } else {
                    GlassIconButton(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out", onClick = viewModel::signOut)
                }
                Spacer(Modifier.weight(1f))
                Text("STEP 2 OF 2 · ${stepIndex + 1}/${steps.size}", style = EmberTheme.type.overline, color = colors.textTertiary)
            }
            val progress by animateFloatAsState((stepIndex + 1f) / steps.size, spring(dampingRatio = 0.7f, stiffness = 200f), label = "progress")
            Box(
                Modifier
                    .padding(horizontal = 24.dp)
                    .fillMaxWidth()
                    .height(5.dp)
                    .background(colors.surfaceHigh, PillShape),
            ) {
                Box(Modifier.fillMaxWidth(progress).height(5.dp).background(colors.accentHorizontal, PillShape))
            }
            AnimatedContent(
                targetState = stepIndex,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally(spring(dampingRatio = 0.85f, stiffness = 300f)) { if (forward) it / 2 else -it / 2 } + fadeIn()) togetherWith
                        (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                modifier = Modifier.weight(1f),
                label = "setupStep",
            ) { index ->
                val current = steps[index]
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                ) {
                    ScreenTitle(current.title, subtitle = current.subtitle)
                    Spacer(Modifier.height(26.dp))
                    when (current) {
                        SetupStep.Basics -> BasicsSection(state, viewModel)
                        SetupStep.Photos -> PhotosSection(state, viewModel)
                        SetupStep.About -> AboutSection(state, viewModel)
                        SetupStep.Prompts -> PromptsSection(state, viewModel)
                        SetupStep.Vibes -> VibesSection(state, viewModel)
                        SetupStep.Intent -> IntentSection(state, viewModel)
                        SetupStep.Location -> LocationSection(state, viewModel)
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
            Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val message = stepError ?: state.error
                if (message != null) Text(message, style = EmberTheme.type.bodySmall, color = colors.danger)
                EmberButton(
                    text = if (last) "Start exploring" else "Continue",
                    loading = state.saving,
                    onClick = {
                        val problem = viewModel.stepProblem(step)
                        if (problem != null) {
                            haptics.reject()
                            stepError = problem
                        } else {
                            stepError = null
                            if (last) viewModel.save(finishOnboarding = true) else stepIndex++
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
