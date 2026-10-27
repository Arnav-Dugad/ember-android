package io.github.arnavdugad.ember.ui.settings

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.BuildConfig
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberChip
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import io.github.arnavdugad.ember.data.settings.AppSettings
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.data.settings.ThemeMode
import io.github.arnavdugad.ember.ui.legal.LegalDoc
import io.github.arnavdugad.ember.ui.legal.LegalSheet
import io.github.arnavdugad.ember.update.UpdateViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountActionState(val deleting: Boolean = false, val error: String? = null)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val auth: AuthRepository,
    private val safety: SafetyRepository,
    val mode: BackendMode,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    private val _account = MutableStateFlow(AccountActionState())
    val account: StateFlow<AccountActionState> = _account.asStateFlow()

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settingsRepo.setThemeMode(mode) }
    }

    fun setAfterDark(enabled: Boolean) {
        viewModelScope.launch { settingsRepo.setAfterDark(enabled) }
    }

    fun signOut() {
        viewModelScope.launch { auth.signOut() }
    }

    val signInProvider: String? get() = auth.signInProvider()

    /**
     * Confirms identity first (Firebase refuses to delete a login that wasn't used recently),
     * then deletes the profile, photos, matches, messages and albums, then the login itself.
     */
    fun deleteAccount(activity: android.app.Activity, password: String) {
        viewModelScope.launch {
            _account.update { it.copy(deleting = true, error = null) }
            val result = runCatching {
                auth.reauthenticate(activity, password)
                safety.deleteAccount()
            }
            _account.update { AccountActionState(deleting = false, error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't delete your account. Try again." }) }
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as ComponentActivity
    val updates: UpdateViewModel = hiltViewModel(activity)
    val updateState by updates.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    var deleteOpen by remember { mutableStateOf(false) }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Text("Settings", style = EmberTheme.type.title, color = colors.textPrimary)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SectionHeader("Appearance")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeMode.entries.forEach { mode ->
                        EmberChip(mode.name, selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                    }
                }
                GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("After Dark mode", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                            Text("Always dark, and discovery shows casual and adventurous people.", style = EmberTheme.type.caption, color = colors.textSecondary)
                        }
                        Switch(settings.afterDark, viewModel::setAfterDark, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White))
                    }
                }

                Spacer(Modifier.height(8.dp))
                SectionHeader("Safety")
                SettingsRow(label = "Safety tips") { legal = LegalDoc.Safety }
                SettingsRow(label = "Community Guidelines") { legal = LegalDoc.Guidelines }
                SettingsRow(label = "Terms of Service") { legal = LegalDoc.Terms }

                Spacer(Modifier.height(8.dp))
                SectionHeader("App")
                SettingsRow(
                    label = if (updateState.checking) "Checking for updates…" else "Check for updates",
                    detail = "Version ${BuildConfig.VERSION_NAME}",
                ) { updates.check(manual = true) }
                updateState.message?.let { message ->
                    LaunchedEffect(message) {
                        kotlinx.coroutines.delay(4_000)
                        updates.consumeMessage()
                    }
                    Text(message, style = EmberTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
                }
                if (viewModel.mode == BackendMode.Preview) {
                    Text(
                        "Running in Preview mode (no Firebase project in this build). Everything stays on this device and resets when the app closes.",
                        style = EmberTheme.type.caption,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }

                Spacer(Modifier.height(8.dp))
                SectionHeader("Account")
                GlassButton("Sign out", onClick = viewModel::signOut, modifier = Modifier.fillMaxWidth())
                GlassButton("Delete account", onClick = { deleteOpen = true }, contentColor = colors.danger, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(24.dp))
            }
        }
        LegalSheet(legal, onDismiss = { legal = null })
        DeleteAccountSheet(
            visible = deleteOpen,
            deleting = account.deleting,
            error = account.error,
            provider = viewModel.signInProvider,
            onDelete = { password -> viewModel.deleteAccount(activity, password) },
            onDismiss = { deleteOpen = false },
        )
    }
}

@Composable
private fun SettingsRow(label: String, detail: String? = null, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, style = EmberTheme.type.body, color = colors.textPrimary)
                if (detail != null) Text(detail, style = EmberTheme.type.caption, color = colors.textTertiary)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = colors.textTertiary)
        }
    }
}

@Composable
private fun DeleteAccountSheet(
    visible: Boolean,
    deleting: Boolean,
    error: String?,
    provider: String?,
    onDelete: (password: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmText by remember(visible) { mutableStateOf("") }
    var password by remember(visible) { mutableStateOf("") }
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = visible, onDismiss = onDismiss, dismissible = !deleting) {
        Text("Delete your account?", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "This permanently deletes your profile, photos, private album, matches and messages. It can't be undone.",
            style = EmberTheme.type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        EmberTextField(confirmText, { confirmText = it }, label = "Type DELETE to confirm", placeholder = "DELETE")
        when (provider) {
            "password" -> {
                Spacer(Modifier.height(12.dp))
                EmberTextField(password, { password = it }, label = "Your password", placeholder = "Password", isPassword = true)
            }
            "google.com" -> Text(
                "You'll confirm with your Google account first.",
                style = EmberTheme.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 10.dp),
            )
        }
        error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        EmberButton(
            "Delete everything",
            onClick = { onDelete(password) },
            enabled = confirmText.trim() == "DELETE" && (provider != "password" || password.isNotBlank()),
            loading = deleting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        GlassButton("Keep my account", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
