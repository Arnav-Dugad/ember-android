package io.github.arnavdugad.ember.ui.root

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.Presence
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.AppSettings
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the user is in the journey; decides which top-level screen shows. */
enum class Session { Loading, SignedOut, Blocked, NeedsAgeGate, NeedsProfile, Ready }

data class RootState(
    val session: Session = Session.Loading,
    val settings: AppSettings = AppSettings(),
    val mode: BackendMode = BackendMode.Preview,
)

@HiltViewModel
class RootViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val profiles: ProfileRepository,
    private val settingsRepo: SettingsRepository,
    private val mode: BackendMode,
    private val deletion: AccountDeletion,
    val deepLinks: DeepLinks,
) : ViewModel() {

    private val profileForUser = auth.user.flatMapLatest { user ->
        if (user == null || !user.isAdult) flowOf(user to null) else profiles.observeMyProfile().map { user to it }
    }

    // Deleting (or deleted, not yet signed out): the account doc is gone, but that's no age gate.
    val state: StateFlow<RootState> = combine(profileForUser, settingsRepo.settings, deletion.inProgress) { (user, profile), settings, leaving ->
        RootState(session = sessionOf(user, profile, settings, leaving), settings = settings, mode = mode)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState(mode = mode))

    /** "Account deleted", once, over the welcome screen. */
    val notice: StateFlow<String?> = deletion.notice

    init {
        viewModelScope.launch { state.collect { if (it.session == Session.SignedOut) deletion.onSignedOut() } }
        // The heartbeat: while fully signed in and the app is in the foreground, mark the user
        // active about every five minutes (which also keeps their shown age current).
        viewModelScope.launch {
            state.map { it.session == Session.Ready }.distinctUntilChanged().collectLatest { ready ->
                if (!ready || mode != BackendMode.Firebase) return@collectLatest
                ProcessLifecycleOwner.get().lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    while (true) {
                        profiles.touchLastActive()
                        delay(Presence.HEARTBEAT.toMillis())
                    }
                }
            }
        }
    }

    fun onNotificationsPrompted() {
        viewModelScope.launch { settingsRepo.setNotificationsPrompted() }
    }

    fun consumeNotice() = deletion.consumeNotice()

    private fun sessionOf(user: AuthUser?, profile: Profile?, settings: AppSettings, leaving: Boolean): Session = when {
        settings.ageGateBlocked -> Session.Blocked
        user == null -> Session.SignedOut
        // Mid-deletion the account doc disappears before the login does; stay put until signed out.
        leaving -> Session.Ready
        !user.isAdult -> Session.NeedsAgeGate
        profile == null || !profile.onboarded -> Session.NeedsProfile
        else -> Session.Ready
    }
}
