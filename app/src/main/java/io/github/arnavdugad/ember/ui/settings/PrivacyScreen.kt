package io.github.arnavdugad.ember.ui.settings

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SocialDistance
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.PrivacyRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.ui.legal.LegalDoc
import io.github.arnavdugad.ember.ui.legal.LegalSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject

data class PrivacyUiState(
    val saving: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

@HiltViewModel
class PrivacyViewModel @Inject constructor(
    profiles: ProfileRepository,
    private val privacy: PrivacyRepository,
    @ApplicationContext private val context: Context,
    val mode: BackendMode,
) : ViewModel() {
    val profile: StateFlow<Profile?> = profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _ui = MutableStateFlow(PrivacyUiState())
    val ui: StateFlow<PrivacyUiState> = _ui.asStateFlow()

    val exportFileName: String get() = "ember-data-${LocalDate.now()}.json"

    fun setShowDistance(show: Boolean) = save { privacy.setHideDistance(!show) }

    fun setPaused(paused: Boolean) = save { privacy.setPaused(paused) }

    fun setShowActivity(show: Boolean) = save { privacy.setHideActivity(!show) }

    /** Writes the export to the file the user picked in the system "Save as" screen. */
    fun export(uri: Uri) {
        if (_ui.value.exporting) return
        viewModelScope.launch {
            _ui.update { it.copy(exporting = true, message = null, error = null) }
            val result = runCatching {
                val json = privacy.exportMyData()
                withContext(Dispatchers.IO) {
                    val out = context.contentResolver.openOutputStream(uri, "wt") ?: error("Couldn't open that file.")
                    out.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                }
            }
            _ui.update {
                it.copy(
                    exporting = false,
                    message = if (result.isSuccess) "Saved. It's a plain JSON file you can open in any text editor." else null,
                    error = result.exceptionOrNull()?.let { e -> e.message ?: "Couldn't export your data. Try again." },
                )
            }
        }
    }

    fun consumeMessages() = _ui.update { it.copy(message = null, error = null) }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            _ui.update { it.copy(saving = true, error = null) }
            val error = runCatching { block() }.exceptionOrNull()
            _ui.update { it.copy(saving = false, error = error?.let { e -> e.message ?: "Couldn't save that. Try again." }) }
        }
    }
}

/** What Ember stores, who sees it, and the switches the user controls, in plain language. */
@Composable
fun PrivacyScreen(onBack: () -> Unit, viewModel: PrivacyViewModel = hiltViewModel()) {
    val profile by viewModel.profile.collectAsStateWithLifecycle()
    val ui by viewModel.ui.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var legal by remember { mutableStateOf<LegalDoc?>(null) }
    val saveAs = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(viewModel::export)
    }
    LaunchedEffect(ui.message) {
        if (ui.message != null) {
            kotlinx.coroutines.delay(6_000)
            viewModel.consumeMessages()
        }
    }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Text("Privacy", style = EmberTheme.type.title, color = colors.textPrimary)
            }
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "What Ember keeps, who can see it, and the switches that are yours. No legalese.",
                    style = EmberTheme.type.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )

                SettingsGroup("Your controls") {
                    val loaded = profile != null
                    SettingsToggleRow(
                        icon = Icons.Rounded.SocialDistance,
                        title = "Show my distance",
                        subtitle = if (profile?.hideDistance == true) {
                            "Off: people see only your city."
                        } else {
                            "On: people see roughly how far away you are, like \"12 km away\"."
                        },
                        checked = profile?.hideDistance != true,
                        onCheckedChange = viewModel::setShowDistance,
                        enabled = loaded,
                    )
                    SettingsToggleRow(
                        icon = Icons.Rounded.Schedule,
                        title = "Show when I'm active",
                        subtitle = if (profile?.hideActivity == true) {
                            "Off: nobody sees \"Active now\" on you, and you won't see it on anyone else either."
                        } else {
                            "On: a green dot and \"Active 2h ago\" show on your profile. Turn it off and you won't see others' either."
                        },
                        checked = profile?.hideActivity != true,
                        onCheckedChange = viewModel::setShowActivity,
                        enabled = loaded,
                    )
                    SettingsToggleRow(
                        icon = Icons.Rounded.PauseCircle,
                        title = "Pause my profile",
                        subtitle = if (profile?.paused == true) {
                            "Paused: nobody new sees you in Discover. Your matches and chats carry on."
                        } else {
                            "Take a break from Discover without losing your matches or chats."
                        },
                        checked = profile?.paused == true,
                        onCheckedChange = viewModel::setPaused,
                        enabled = loaded,
                    )
                    SettingsRow(
                        icon = Icons.Rounded.Download,
                        title = "Download my data",
                        subtitle = "A JSON copy of your profile, matches and the messages you sent. Photos stay where they are.",
                        onClick = { if (!ui.exporting) saveAs.launch(viewModel.exportFileName) },
                        trailing = if (ui.exporting) {
                            { TrailingSpinner() }
                        } else {
                            null
                        },
                    )
                    ui.message?.let { Text(it, style = EmberTheme.type.caption, color = colors.like, modifier = Modifier.padding(horizontal = 6.dp)) }
                    ui.error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(horizontal = 6.dp)) }
                }

                SettingsGroup("What we store, and who sees it") {
                    PrivacyFact(
                        Icons.Rounded.Place,
                        "Location: city level only",
                        "Your position is rounded to about 1 km on your phone before it's saved, with your city name. " +
                            "Your exact GPS location is never stored. Others see your city and, unless you switch it off above, a rough distance.",
                    )
                    PrivacyFact(
                        Icons.Rounded.Schedule,
                        "Activity status",
                        "While Ember is open it saves when you were last active, about every five minutes, so matches and people nearby can see \"Active now\". " +
                            "With \"Show when I'm active\" off, only the date is saved, at most once a day.",
                    )
                    PrivacyFact(
                        Icons.Rounded.Cake,
                        "Birth date: private",
                        "Saved once to confirm you're 18 or older, and only you can read it. Everyone else sees just your age.",
                    )
                    PrivacyFact(
                        Icons.Rounded.PhotoLibrary,
                        "Photos",
                        "Compressed, stripped of location data and stored in our database (Google Cloud Firestore). " +
                            "Profile photos can be seen by any signed-in adult on Ember; chat photos only by you and that match.",
                    )
                    PrivacyFact(
                        Icons.Rounded.Lock,
                        "Private album",
                        "Only people you approve can open it. Revoking access works instantly, and screenshots are blocked on private photos.",
                    )
                    PrivacyFact(
                        Icons.Rounded.Forum,
                        "Chats",
                        "Only you and your match can read a conversation. Unmatching or blocking deletes it for both of you.",
                    )
                    PrivacyFact(
                        Icons.Rounded.NotificationsOff,
                        "No push notifications",
                        "Ember doesn't hand your data to a push service. Chats and matches update while the app is open.",
                    )
                    PrivacyFact(
                        Icons.Rounded.BugReport,
                        "Analytics and crash reports",
                        "We count a few anonymous events, such as a swipe or a match (never what you write), with Google Analytics for Firebase, " +
                            "along with basic device and app-version details. Crash reports go to Firebase Crashlytics so we can fix bugs." +
                            if (viewModel.mode == BackendMode.Preview) " In this Preview build, nothing is sent at all." else "",
                    )
                    PrivacyFact(
                        Icons.Rounded.Flag,
                        "Blocks and reports",
                        "Blocking is silent: the other person isn't told. Reports go to our moderators and are kept even if you delete your account.",
                    )
                    PrivacyFact(
                        Icons.Rounded.DeleteForever,
                        "Leaving",
                        "Delete account (in Settings) permanently removes your profile, photos, private album, matches and messages.",
                    )
                }

                SettingsGroup("Read more") {
                    SettingsRow(Icons.Rounded.Gavel, "Terms of Service", onClick = { legal = LegalDoc.Terms })
                    SettingsRow(Icons.Rounded.Groups, "Community Guidelines", onClick = { legal = LegalDoc.Guidelines })
                    SettingsRow(Icons.Rounded.HealthAndSafety, "Safety tips", onClick = { legal = LegalDoc.Safety })
                }
                Spacer(Modifier.height(24.dp))
            }
        }
        LegalSheet(legal, onDismiss = { legal = null })
    }
}

@Composable
private fun PrivacyFact(icon: ImageVector, title: String, body: String) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            SettingsIconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(body, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
            }
        }
    }
}
