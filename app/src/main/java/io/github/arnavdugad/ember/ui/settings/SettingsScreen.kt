package io.github.arnavdugad.ember.ui.settings

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.AlternateEmail
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LockReset
import androidx.compose.material.icons.rounded.MarkEmailUnread
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.FirebaseTooManyRequestsException
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
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
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.PROVIDER_GOOGLE
import io.github.arnavdugad.ember.data.repo.PROVIDER_PASSWORD
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
import io.github.arnavdugad.ember.data.ai.AiConfig
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository
import androidx.compose.material.icons.rounded.AutoAwesome

data class AccountActionState(val deleting: Boolean = false, val error: String? = null)

/** Which sign-in method sheet is open. */
enum class LoginSheet { AddPassword, ChangePassword }

data class SignInState(
    val methods: Set<String> = emptySet(),
    val email: String? = null,
    val busy: Boolean = false,
    val linkingGoogle: Boolean = false,
    val sheet: LoginSheet? = null,
    /** Errors inside an open sheet. */
    val sheetError: String? = null,
    val info: String? = null,
    val error: String? = null,
) {
    val hasGoogle: Boolean get() = PROVIDER_GOOGLE in methods
    val hasPassword: Boolean get() = PROVIDER_PASSWORD in methods
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepo: SettingsRepository,
    private val auth: AuthRepository,
    private val safety: SafetyRepository,
    val mode: BackendMode,
    aiSettings: AiSettingsRepository,
) : ViewModel() {
    val settings: StateFlow<AppSettings> = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val ai: StateFlow<AiConfig?> = aiSettings.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _account = MutableStateFlow(AccountActionState())
    val account: StateFlow<AccountActionState> = _account.asStateFlow()

    private val _signIn = MutableStateFlow(SignInState(methods = auth.signInMethods(), email = auth.accountEmail()))
    val signIn: StateFlow<SignInState> = _signIn.asStateFlow()

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

    // --- Sign-in methods ---

    fun openSheet(sheet: LoginSheet) = _signIn.update { it.copy(sheet = sheet, sheetError = null, info = null, error = null) }

    fun closeSheet() = _signIn.update { if (it.busy) it else it.copy(sheet = null, sheetError = null) }

    /** Google login: confirm with Google, then add email + password. */
    fun addPassword(activity: Activity, password: String, confirm: String) {
        validateNewPassword(password, confirm)?.let { return sheetFail(it) }
        login(
            collision = "That email already belongs to a different Ember account.",
            success = "Password added. You can now sign in with ${_signIn.value.email ?: "your email"} too.",
        ) { auth.addPassword(activity, password) }
    }

    /** Email login: pick a Google account and link it. */
    fun linkGoogle(activity: Activity) = login(
        collision = "That Google account already has its own Ember account. Sign in with it separately, or use a different Google account.",
        success = "Google linked. You can now use Continue with Google too.",
        linkingGoogle = true,
    ) { auth.linkGoogle(activity) }

    fun changePassword(current: String, password: String, confirm: String) {
        if (current.isBlank()) return sheetFail("Enter your current password.")
        validateNewPassword(password, confirm)?.let { return sheetFail(it) }
        if (password == current) return sheetFail("Choose a password you haven't used here.")
        login(success = "Password changed.") { auth.changePassword(current, password) }
    }

    fun sendPasswordReset() {
        val email = _signIn.value.email ?: return
        login(success = "We emailed a reset link to $email.") { auth.sendPasswordReset(email) }
    }

    private fun validateNewPassword(password: String, confirm: String): String? = when {
        password.length < 8 -> "Passwords need at least 8 characters."
        password != confirm -> "The two passwords don't match."
        else -> null
    }

    private fun sheetFail(message: String) = _signIn.update { it.copy(sheetError = message) }

    private fun login(collision: String? = null, success: String, linkingGoogle: Boolean = false, block: suspend () -> Unit) {
        if (_signIn.value.busy) return
        viewModelScope.launch {
            _signIn.update { it.copy(busy = true, linkingGoogle = linkingGoogle, sheetError = null, info = null, error = null) }
            val error = runCatching { block() }.exceptionOrNull()
            val message = error?.let { friendly(it, collision) }
            _signIn.update { s ->
                val inSheet = s.sheet != null
                s.copy(
                    busy = false,
                    linkingGoogle = false,
                    methods = auth.signInMethods(),
                    email = auth.accountEmail(),
                    // Success closes the sheet; errors (and a cancelled Google picker) keep it open.
                    sheet = if (error == null) null else s.sheet,
                    sheetError = if (inSheet) message else null,
                    error = if (!inSheet) message else null,
                    info = if (error == null) success else null,
                )
            }
        }
    }

    /** Null means "say nothing" (the user closed the Google sheet). */
    private fun friendly(e: Throwable, collision: String?): String? = when (e) {
        is GetCredentialCancellationException -> null
        is NoCredentialException -> "Add a Google account to this phone first, then try again."
        is FirebaseAuthUserCollisionException -> collision ?: "That sign-in already belongs to a different Ember account."
        is FirebaseAuthWeakPasswordException -> "That password is too easy to guess. Try a longer one."
        is FirebaseAuthRecentLoginRequiredException -> "For your security, sign out and back in, then try again."
        is FirebaseAuthInvalidCredentialsException -> "That password isn't right."
        is FirebaseAuthInvalidUserException ->
            if (e.errorCode == "ERROR_USER_MISMATCH") "That's a different Google account from the one you use for Ember." else "We couldn't find your account. Sign in again."
        is FirebaseAuthException ->
            if (e.errorCode == "ERROR_PROVIDER_ALREADY_LINKED") "That's already linked to your account." else e.message ?: GENERIC
        is FirebaseTooManyRequestsException -> "Too many tries. Wait a few minutes, then try again."
        is FirebaseNetworkException -> "You're offline. Check your connection."
        else -> e.message ?: GENERIC
    }

    /**
     * Confirms identity first (Firebase refuses to delete a login that wasn't used recently),
     * then deletes the profile, photos, matches, messages and albums, then the login itself.
     */
    fun deleteAccount(activity: Activity, password: String) {
        viewModelScope.launch {
            _account.update { it.copy(deleting = true, error = null) }
            val result = runCatching {
                auth.reauthenticate(activity, password)
                safety.deleteAccount()
            }
            _account.update { AccountActionState(deleting = false, error = result.exceptionOrNull()?.let { e -> friendly(e, null) }) }
        }
    }

    private companion object {
        const val GENERIC = "Something went wrong. Please try again."
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit, onPrivacy: () -> Unit, onAiChat: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val ai by viewModel.ai.collectAsStateWithLifecycle()
    val account by viewModel.account.collectAsStateWithLifecycle()
    val signIn by viewModel.signIn.collectAsStateWithLifecycle()
    val activity = LocalActivity.current as ComponentActivity
    val context = LocalContext.current
    val updates: UpdateViewModel = hiltViewModel(activity)
    val updateState by updates.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val preview = viewModel.mode == BackendMode.Preview
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
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                SettingsGroup("Account") {
                    SettingsRow(
                        icon = Icons.Rounded.AlternateEmail,
                        title = "Signed in as",
                        subtitle = when {
                            preview -> "Preview mode: no login, everything stays on this phone"
                            else -> signIn.email ?: "No email on this login"
                        },
                    )
                    SettingsRow(Icons.AutoMirrored.Rounded.Logout, "Sign out", onClick = viewModel::signOut)
                }

                SettingsGroup("Sign-in methods") {
                    SettingsRow(
                        icon = Icons.Rounded.AlternateEmail,
                        title = "Google",
                        subtitle = if (signIn.hasGoogle) "Continue with Google works for this account" else "Also sign in with Continue with Google",
                        leading = { GoogleTile() },
                        onClick = if (signIn.hasGoogle) null else ({ viewModel.linkGoogle(activity) }),
                        enabled = !signIn.busy,
                        trailing = {
                            when {
                                signIn.hasGoogle -> LinkedBadge()
                                signIn.linkingGoogle -> TrailingSpinner()
                                else -> TrailingLabel("Link", colors.accent)
                            }
                        },
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Password,
                        title = "Email & password",
                        subtitle = if (signIn.hasPassword) signIn.email else "Also sign in with ${signIn.email ?: "your email"} and a password",
                        onClick = if (signIn.hasPassword) null else ({ viewModel.openSheet(LoginSheet.AddPassword) }),
                        enabled = !signIn.busy,
                        trailing = { if (signIn.hasPassword) LinkedBadge() else TrailingLabel("Add", colors.accent) },
                    )
                    if (signIn.hasPassword) {
                        SettingsRow(Icons.Rounded.LockReset, "Change password", onClick = { viewModel.openSheet(LoginSheet.ChangePassword) })
                        SettingsRow(
                            icon = Icons.Rounded.MarkEmailUnread,
                            title = "Forgot password?",
                            subtitle = "Email a reset link to ${signIn.email}",
                            onClick = viewModel::sendPasswordReset,
                            enabled = !signIn.busy,
                        )
                    }
                    signIn.info?.let { Text(it, style = EmberTheme.type.caption, color = colors.like, modifier = Modifier.padding(horizontal = 6.dp)) }
                    signIn.error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 6.dp)) }
                }

                SettingsGroup("AI chat") {
                    SettingsRow(
                        icon = Icons.Rounded.AutoAwesome,
                        title = "AI replies for demo profiles",
                        subtitle = when {
                            ai?.ready == true -> "On with ${ai?.provider?.label}: demo profiles text back in character"
                            ai?.hasKey == true -> "Off. Your ${ai?.provider?.label} key is saved."
                            else -> "Add a Claude, Gemini or ChatGPT key and demo profiles reply like real people"
                        },
                        onClick = onAiChat,
                    )
                }

                SettingsGroup("Discovery & After Dark") {
                    SettingsToggleRow(
                        icon = Icons.Rounded.NightsStay,
                        title = "After Dark mode",
                        subtitle = "Always dark, and discovery shows casual and adventurous people.",
                        checked = settings.afterDark,
                        onCheckedChange = viewModel::setAfterDark,
                    )
                }

                SettingsGroup("Appearance") {
                    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                SettingsIconTile(Icons.Rounded.Palette)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Theme", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                                    Text(
                                        if (settings.afterDark) "After Dark keeps Ember dark whatever you pick." else "System follows your phone.",
                                        style = EmberTheme.type.caption,
                                        color = colors.textSecondary,
                                    )
                                }
                            }
                            Spacer(Modifier.height(12.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                ThemeMode.entries.forEach { mode ->
                                    EmberChip(mode.name, selected = settings.themeMode == mode, onClick = { viewModel.setTheme(mode) })
                                }
                            }
                        }
                    }
                }

                SettingsGroup("Notifications") {
                    SettingsRow(
                        icon = Icons.Rounded.Notifications,
                        title = "Notifications",
                        subtitle = "New messages are checked about every 15 minutes in the background. Choose which alerts Ember may show.",
                        onClick = {
                            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                            runCatching { context.startActivity(intent) }
                        },
                    )
                }

                SettingsGroup("Privacy & safety") {
                    SettingsRow(Icons.Rounded.PrivacyTip, "Privacy", subtitle = "What we store, who sees it, and your controls", onClick = onPrivacy)
                    SettingsRow(Icons.Rounded.HealthAndSafety, "Safety tips", onClick = { legal = LegalDoc.Safety })
                    SettingsRow(Icons.Rounded.Groups, "Community Guidelines", onClick = { legal = LegalDoc.Guidelines })
                    SettingsRow(Icons.Rounded.Gavel, "Terms of Service", onClick = { legal = LegalDoc.Terms })
                }

                SettingsGroup("App") {
                    SettingsRow(
                        icon = Icons.Rounded.SystemUpdate,
                        title = if (updateState.checking) "Checking for updates…" else "Check for updates",
                        subtitle = "Version ${BuildConfig.VERSION_NAME}",
                        onClick = { updates.check(manual = true) },
                    )
                    updateState.message?.let { message ->
                        LaunchedEffect(message) {
                            kotlinx.coroutines.delay(4_000)
                            updates.consumeMessage()
                        }
                        Text(message, style = EmberTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 6.dp))
                    }
                    if (preview) {
                        SettingsRow(
                            icon = Icons.Rounded.Info,
                            title = "Preview mode",
                            subtitle = "This build has no Firebase project, so everything stays on this device. Your profile is kept; chats reset when the app closes.",
                        )
                    }
                }

                SettingsGroup("Danger zone") {
                    SettingsRow(
                        icon = Icons.Rounded.DeleteForever,
                        title = "Delete account",
                        subtitle = "Permanently remove your profile, photos, matches and messages",
                        danger = true,
                        onClick = { deleteOpen = true },
                    )
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        LegalSheet(legal, onDismiss = { legal = null })
        AddPasswordSheet(
            visible = signIn.sheet == LoginSheet.AddPassword,
            email = signIn.email,
            busy = signIn.busy,
            error = signIn.sheetError,
            onSubmit = { password, confirm -> viewModel.addPassword(activity, password, confirm) },
            onDismiss = viewModel::closeSheet,
        )
        ChangePasswordSheet(
            visible = signIn.sheet == LoginSheet.ChangePassword,
            busy = signIn.busy,
            error = signIn.sheetError,
            onSubmit = viewModel::changePassword,
            onForgot = {
                viewModel.closeSheet()
                viewModel.sendPasswordReset()
            },
            onDismiss = viewModel::closeSheet,
        )
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
private fun AddPasswordSheet(
    visible: Boolean,
    email: String?,
    busy: Boolean,
    error: String?,
    onSubmit: (password: String, confirm: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var password by remember(visible) { mutableStateOf("") }
    var confirm by remember(visible) { mutableStateOf("") }
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = visible, onDismiss = onDismiss, dismissible = !busy) {
        Text("Add a password", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(8.dp))
        Text(
            "Sign in with ${email ?: "your email"} and a password as well as Google. You'll confirm with Google first.",
            style = EmberTheme.type.body,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        EmberTextField(password, { password = it }, label = "New password", placeholder = "At least 8 characters", isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        EmberTextField(
            confirm,
            { confirm = it },
            label = "Confirm password",
            placeholder = "Type it again",
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            onImeAction = { onSubmit(password, confirm) },
        )
        error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        EmberButton(
            "Confirm with Google and add",
            onClick = { onSubmit(password, confirm) },
            enabled = password.isNotEmpty() && confirm.isNotEmpty(),
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        GlassButton("Not now", onClick = onDismiss, enabled = !busy, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun ChangePasswordSheet(
    visible: Boolean,
    busy: Boolean,
    error: String?,
    onSubmit: (current: String, password: String, confirm: String) -> Unit,
    onForgot: () -> Unit,
    onDismiss: () -> Unit,
) {
    var current by remember(visible) { mutableStateOf("") }
    var password by remember(visible) { mutableStateOf("") }
    var confirm by remember(visible) { mutableStateOf("") }
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = visible, onDismiss = onDismiss, dismissible = !busy) {
        Text("Change password", style = EmberTheme.type.headline, color = colors.textPrimary)
        Spacer(Modifier.height(16.dp))
        EmberTextField(current, { current = it }, label = "Current password", placeholder = "Password", isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        EmberTextField(password, { password = it }, label = "New password", placeholder = "At least 8 characters", isPassword = true, keyboardType = KeyboardType.Password)
        Spacer(Modifier.height(12.dp))
        EmberTextField(
            confirm,
            { confirm = it },
            label = "Confirm new password",
            placeholder = "Type it again",
            isPassword = true,
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
            onImeAction = { onSubmit(current, password, confirm) },
        )
        error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(18.dp))
        EmberButton(
            "Change password",
            onClick = { onSubmit(current, password, confirm) },
            enabled = current.isNotEmpty() && password.isNotEmpty() && confirm.isNotEmpty(),
            loading = busy,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Forgot your current password? Email me a reset link",
            style = EmberTheme.type.label,
            color = colors.accent,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .pressable(onClick = onForgot, enabled = !busy)
                .padding(vertical = 10.dp, horizontal = 8.dp),
        )
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
            PROVIDER_PASSWORD -> {
                Spacer(Modifier.height(12.dp))
                EmberTextField(password, { password = it }, label = "Your password", placeholder = "Password", isPassword = true)
            }
            PROVIDER_GOOGLE -> Text(
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
            enabled = confirmText.trim() == "DELETE" && (provider != PROVIDER_PASSWORD || password.isNotBlank()),
            loading = deleting,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        GlassButton("Keep my account", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
