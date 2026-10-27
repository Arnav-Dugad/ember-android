package io.github.arnavdugad.ember.ui.root

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.AppSettings
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
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
    val deepLinks: DeepLinks,
) : ViewModel() {

    private val profileForUser = auth.user.flatMapLatest { user ->
        if (user == null || !user.isAdult) flowOf(user to null) else profiles.observeMyProfile().map { user to it }
    }

    val state: StateFlow<RootState> = combine(profileForUser, settingsRepo.settings) { (user, profile), settings ->
        RootState(session = sessionOf(user, profile, settings), settings = settings, mode = mode)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RootState(mode = mode))

    init {
        // Once fully signed in: register for pushes and mark the user active.
        state.map { it.session }.distinctUntilChanged().filter { it == Session.Ready }.onEach {
            if (mode == BackendMode.Firebase) {
                viewModelScope.launch {
                    // The token is delivered to EmberMessagingService.onRegistered, which saves it.
                    runCatching { FirebaseMessaging.getInstance().register().await() }
                    profiles.touchLastActive()
                }
            }
        }.launchIn(viewModelScope)
    }

    fun onNotificationsPrompted() {
        viewModelScope.launch { settingsRepo.setNotificationsPrompted() }
    }

    private fun sessionOf(user: AuthUser?, profile: Profile?, settings: AppSettings): Session = when {
        settings.ageGateBlocked -> Session.Blocked
        user == null -> Session.SignedOut
        !user.isAdult -> Session.NeedsAgeGate
        profile == null || !profile.onboarded -> Session.NeedsProfile
        else -> Session.Ready
    }
}
