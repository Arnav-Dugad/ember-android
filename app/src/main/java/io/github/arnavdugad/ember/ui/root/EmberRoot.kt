package io.github.arnavdugad.ember.ui.root

import android.Manifest
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.Palette
import io.github.arnavdugad.ember.data.settings.ThemeMode
import io.github.arnavdugad.ember.ui.auth.AuthFlow
import io.github.arnavdugad.ember.ui.main.MainScaffold
import io.github.arnavdugad.ember.ui.onboarding.AgeGateScreen
import io.github.arnavdugad.ember.ui.onboarding.ProfileSetupScreen
import io.github.arnavdugad.ember.update.UpdateSheet
import io.github.arnavdugad.ember.update.UpdateViewModel

@Composable
fun EmberRoot(viewModel: RootViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as ComponentActivity
    val updates: UpdateViewModel = hiltViewModel(activity)
    val updateState by updates.state.collectAsStateWithLifecycle()
    val launch by updates.launch.collectAsStateWithLifecycle()
    val deepLinks = viewModel.deepLinks
    val pendingLink by deepLinks.pending.collectAsStateWithLifecycle()

    val systemDark = isSystemInDarkTheme()
    val palette = when {
        state.settings.afterDark -> Palette.AfterDark
        state.settings.themeMode == ThemeMode.Light -> Palette.Day
        state.settings.themeMode == ThemeMode.Dark -> Palette.Night
        systemDark -> Palette.Night
        else -> Palette.Day
    }

    val view = LocalView.current
    SideEffect {
        val controller = WindowCompat.getInsetsController(activity.window, view)
        controller.isAppearanceLightStatusBars = palette == Palette.Day
        controller.isAppearanceLightNavigationBars = palette == Palette.Day
    }

    LaunchedEffect(launch) {
        launch?.let {
            runCatching { activity.startActivity(it) }
            updates.consumeLaunch()
        }
    }
    LaunchedEffect(pendingLink) {
        if (pendingLink == DeepLink.Update) {
            updates.check(manual = true)
            deepLinks.consume()
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.onResume() }

    EmberTheme(palette) {
        GlassScene(
            // Exposes test tags to UiAutomator for the Baseline Profile generator.
            modifier = Modifier.fillMaxSize().semantics { testTagsAsResourceId = true },
            backdrop = {
                AnimatedContent(
                    targetState = state.session,
                    transitionSpec = { fadeIn(tween(450)) togetherWith fadeOut(tween(300)) },
                    label = "session",
                ) { session ->
                    when (session) {
                        Session.Loading -> Splash()
                        Session.SignedOut -> AuthFlow()
                        Session.Blocked -> BlockedScreen()
                        Session.NeedsAgeGate -> AgeGateScreen()
                        Session.NeedsProfile -> ProfileSetupScreen()
                        Session.Ready -> {
                            NotificationPermission(prompted = state.settings.notificationsPrompted, onPrompted = viewModel::onNotificationsPrompted)
                            MainScaffold(deepLinks)
                        }
                    }
                }
            },
        ) {
            UpdateSheet(
                state = updateState,
                onUpdate = updates::startUpdate,
                onRemindLater = updates::remindLater,
                onGrantPermission = updates::openInstallPermission,
            )
        }
    }
}

@Composable
private fun Splash() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AmbientGlow()
        EmberFlame(size = 96.dp)
    }
}

/** Shown on a device where the age gate has rejected someone. */
@Composable
private fun BlockedScreen() {
    val colors = EmberTheme.colors
    Box(Modifier.fillMaxSize()) {
        AmbientGlow(intensity = 0.5f)
        Column(
            Modifier.fillMaxSize().padding(36.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            EmberFlame(size = 72.dp)
            Spacer(Modifier.height(20.dp))
            Text("Ember is for adults", style = EmberTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                "You need to be 18 or older to use Ember, so we can't create an account here. Thanks for understanding.",
                style = EmberTheme.type.body,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Asks once, after onboarding, on Android 13+ where notifications need permission. */
@Composable
private fun NotificationPermission(prompted: Boolean, onPrompted: () -> Unit) {
    if (prompted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onPrompted() }
    LaunchedEffect(Unit) { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }
}
