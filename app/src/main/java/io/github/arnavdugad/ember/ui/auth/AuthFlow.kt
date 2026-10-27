package io.github.arnavdugad.ember.ui.auth

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.DpOffset
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.image.EmberImage
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberChip
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GradientText
import io.github.arnavdugad.ember.core.designsystem.RisingEmbers
import io.github.arnavdugad.ember.core.designsystem.ScreenTitle
import io.github.arnavdugad.ember.core.designsystem.pressable

private enum class AuthStep { Welcome, Email }

/** Sign-in: a welcome hero, then Google or email. Preview builds skip straight to the demo. */
@Composable
fun AuthFlow(viewModel: AuthViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableStateOf(AuthStep.Welcome) }
    BackHandler(enabled = step != AuthStep.Welcome) {
        viewModel.reset()
        step = AuthStep.Welcome
    }
    GlassScene(
        modifier = Modifier.fillMaxSize(),
        backdrop = {
            AmbientGlow(intensity = 1.3f)
            RisingEmbers(Modifier.fillMaxSize())
        },
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "authStep",
        ) { current ->
            when (current) {
                AuthStep.Welcome -> Welcome(
                    preview = viewModel.isPreview,
                    heroPhotos = viewModel.heroPhotos,
                    state = state,
                    onPreview = viewModel::enterPreview,
                    onGoogle = viewModel::signInWithGoogle,
                    onEmail = { step = AuthStep.Email },
                )
                AuthStep.Email -> EmailAuth(state, viewModel, onBack = { step = AuthStep.Welcome })
            }
        }
    }
}

@Composable
private fun Welcome(
    preview: Boolean,
    heroPhotos: List<String>,
    state: AuthUiState,
    onPreview: () -> Unit,
    onGoogle: (android.app.Activity) -> Unit,
    onEmail: () -> Unit,
) {
    val colors = EmberTheme.colors
    val activity = LocalActivity.current
    val pulse = rememberInfiniteTransition(label = "flame")
    val scale by pulse.animateFloat(0.94f, 1.06f, infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "flameScale")
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 28.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Box(contentAlignment = Alignment.BottomCenter) {
            if (heroPhotos.size == 3) PortraitFan(heroPhotos, Modifier.padding(bottom = 44.dp))
            EmberFlame(
                size = if (heroPhotos.size == 3) 84.dp else 108.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                },
            )
        }
        Spacer(Modifier.height(24.dp))
        Text("Find your", style = EmberTheme.type.display, color = colors.textPrimary)
        GradientText("spark.", style = EmberTheme.type.display)
        Spacer(Modifier.height(14.dp))
        Text(
            "Flirty, fun and adventurous connections.\nFor grown-ups only.",
            style = EmberTheme.type.body,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1.2f))
        state.error?.let {
            Text(it, style = EmberTheme.type.bodySmall, color = colors.danger, textAlign = TextAlign.Center, modifier = Modifier.padding(bottom = 12.dp))
        }
        if (preview) {
            EmberButton(
                "Explore Ember",
                onClick = onPreview,
                loading = state.loading,
                icon = Icons.Rounded.Explore,
                modifier = Modifier.fillMaxWidth().testTag("preview_mode"),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                "Preview mode: this build has no Firebase project, so you'll meet demo profiles and nothing leaves your phone.",
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
                textAlign = TextAlign.Center,
            )
        } else {
            EmberButton(
                "Continue with Google",
                onClick = { activity?.let(onGoogle) },
                loading = state.loading,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            GlassButton("Continue with email", onClick = onEmail, icon = Icons.Rounded.AlternateEmail, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(20.dp))
        Text(
            "Ember is for adults 18+. By continuing you agree to our Terms and Community Guidelines.",
            style = EmberTheme.type.caption,
            color = colors.textTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun AuthPage(title: String, subtitle: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
        Spacer(Modifier.height(28.dp))
        ScreenTitle(title, subtitle = subtitle)
        Spacer(Modifier.height(28.dp))
        content()
    }
}

@Composable
private fun EmailAuth(state: AuthUiState, viewModel: AuthViewModel, onBack: () -> Unit) {
    var createAccount by rememberSaveable { mutableStateOf(false) }
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    val colors = EmberTheme.colors
    AuthPage(
        title = if (createAccount) "Create your account" else "Welcome back",
        subtitle = "Use the email you'll keep. We never show it on your profile.",
        onBack = onBack,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            EmberChip("Sign in", selected = !createAccount, onClick = { createAccount = false })
            EmberChip("New here", selected = createAccount, onClick = { createAccount = true })
        }
        Spacer(Modifier.height(20.dp))
        EmberTextField(email, { email = it }, label = "Email", placeholder = "you@example.com", keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(14.dp))
        EmberTextField(
            password,
            { password = it },
            label = "Password",
            placeholder = "At least 8 characters",
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            onImeAction = { viewModel.emailAuth(email, password, createAccount) },
        )
        Messages(state)
        Spacer(Modifier.height(24.dp))
        EmberButton(
            if (createAccount) "Create account" else "Sign in",
            onClick = { viewModel.emailAuth(email, password, createAccount) },
            loading = state.loading,
            modifier = Modifier.fillMaxWidth(),
        )
        AnimatedVisibility(!createAccount) {
            Box(Modifier.fillMaxWidth().padding(top = 18.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Forgot password?",
                    style = EmberTheme.type.label,
                    color = colors.accent,
                    modifier = Modifier.pressable(onClick = { viewModel.resetPassword(email) }).padding(8.dp),
                )
            }
        }
    }
}

@Composable
private fun Messages(state: AuthUiState) {
    val colors = EmberTheme.colors
    val message = state.error ?: state.info ?: return
    Spacer(Modifier.size(14.dp))
    Text(message, style = EmberTheme.type.bodySmall, color = if (state.error != null) colors.danger else colors.like)
}

/**
 * Three portraits fanned like a hand of cards, each floating on its own slow sine so the stack
 * breathes. The portraits are AI-generated people who don't exist.
 */
@Composable
private fun PortraitFan(photos: List<String>, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "fan")
    val t by transition.animateFloat(0f, (2 * Math.PI).toFloat(), infiniteRepeatable(tween(7_000, easing = androidx.compose.animation.core.LinearEasing)), label = "fanTime")
    Box(modifier.size(width = 320.dp, height = 230.dp), contentAlignment = Alignment.Center) {
        val cards = listOf(Triple(0, -13f, -92), Triple(2, 13f, 92), Triple(1, 0f, 0))
        cards.forEach { (index, angle, x) ->
            Box(
                Modifier
                    .size(width = 138.dp, height = 184.dp)
                    .graphicsLayer {
                        val phase = t + index * 2.1f
                        translationX = x.dp.toPx()
                        translationY = (if (index == 1) -12f else 8f).dp.toPx() + kotlin.math.sin(phase) * 5.dp.toPx()
                        rotationZ = angle + kotlin.math.sin(phase) * 1.5f
                    }
                    .dropShadow(EmberShapes.panel, Shadow(radius = 22.dp, color = Color.Black.copy(alpha = 0.45f), offset = DpOffset(0.dp, 10.dp)))
                    .clip(EmberShapes.panel)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), EmberShapes.panel),
            ) {
                EmberImage(photos[index], null, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
        }
    }
}
