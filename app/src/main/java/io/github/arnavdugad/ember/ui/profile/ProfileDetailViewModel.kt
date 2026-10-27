package io.github.arnavdugad.ember.ui.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.remote.withDistanceFrom
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.ui.matches.UnmatchQueue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProfileDetailState(
    val profile: Profile? = null,
    val loading: Boolean = true,
    val match: Match? = null,
    val matchOnly: MatchOnlyDetails? = null,
    val albumAccess: AccessStatus = AccessStatus.None,
    val busy: Boolean = false,
    val message: String? = null,
    /** Set after block/unmatch: the screen should close. */
    val removed: Boolean = false,
    /** I hide my own activity, so I don't see anyone else's either. */
    val viewerHidesActivity: Boolean = false,
    val myUid: String? = null,
)

@HiltViewModel(assistedFactory = ProfileDetailViewModel.Factory::class)
class ProfileDetailViewModel @AssistedInject constructor(
    @Assisted private val uid: String,
    private val profiles: ProfileRepository,
    private val matches: MatchRepository,
    private val albums: AlbumRepository,
    private val safety: SafetyRepository,
    private val settings: SettingsRepository,
    private val unmatches: UnmatchQueue,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(uid: String): ProfileDetailViewModel
    }

    private val _state = MutableStateFlow(ProfileDetailState())
    val state: StateFlow<ProfileDetailState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val loaded = runCatching { profiles.getProfile(uid) }.getOrNull()
            // In travel mode the distance is measured from the city you're browsing, like the deck.
            val travel = runCatching { settings.current().filters.browseFrom }.getOrNull()
            val profile = if (travel != null) loaded?.withDistanceFrom(travel.lat to travel.lng) else loaded
            _state.update { it.copy(profile = profile, loading = false) }
        }
        viewModelScope.launch {
            // A match waiting out its Undo window already counts as gone.
            combine(matches.observeMatches().catch { emit(emptyList()) }, unmatches.pending) { list, pending ->
                list.firstOrNull { m -> m.otherUid == uid && pending.none { it.matchId == m.id } }
            }.collect { match ->
                val wasMatched = _state.value.match != null
                _state.update { it.copy(match = match) }
                if (match != null && !wasMatched) {
                    val details = runCatching { profiles.getMatchOnly(uid) }.getOrNull()
                    _state.update { it.copy(matchOnly = details) }
                }
            }
        }
        viewModelScope.launch {
            profiles.observeMyProfile().catch { emit(null) }.collect { me ->
                _state.update { it.copy(viewerHidesActivity = me?.hideActivity == true, myUid = me?.uid) }
            }
        }
        viewModelScope.launch {
            albums.observeAccess(uid).catch { emit(AccessStatus.None) }.collect { status ->
                _state.update { it.copy(albumAccess = status) }
            }
        }
    }

    fun requestAlbum() = act { albums.requestAccess(uid) }

    fun withdrawAlbum() = act { albums.withdrawAccess(uid) }

    fun report(reason: ReportReason, details: String, block: Boolean) = act(closeAfter = block) {
        safety.report(uid, reason, details, _state.value.match?.id, block)
        _state.update { it.copy(message = "Thanks for telling us. Our team will review this.") }
    }

    fun block() = act(closeAfter = true) { safety.block(uid) }

    /** Hides the match now; the real unmatch waits out the Undo window. */
    fun unmatch() {
        val match = _state.value.match ?: return
        unmatches.unmatch(match.id, match.otherName)
        _state.update { it.copy(removed = true) }
    }

    fun consumeMessage() = _state.update { it.copy(message = null) }

    private fun act(closeAfter: Boolean = false, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            val result = runCatching { block() }
            _state.update {
                it.copy(
                    busy = false,
                    removed = closeAfter && result.isSuccess,
                    message = result.exceptionOrNull()?.let { e -> e.message ?: "That didn't work. Try again." } ?: it.message,
                )
            }
        }
    }
}
