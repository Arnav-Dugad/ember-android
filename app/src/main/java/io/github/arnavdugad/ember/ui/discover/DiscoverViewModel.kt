package io.github.arnavdugad.ember.ui.discover

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.analytics.Analytics
import io.github.arnavdugad.ember.core.image.PaletteExtractor
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DiscoverState(
    val deck: List<Profile> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val afterDark: Boolean = false,
    val lastSwipe: Pair<Profile, SwipeDirection>? = null,
    val rewinding: Boolean = false,
    val glow: Color? = null,
    val celebration: Celebration? = null,
)

data class Celebration(val match: Match, val them: Profile?)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discovery: DiscoveryRepository,
    private val matches: MatchRepository,
    profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val palette: PaletteExtractor,
    private val analytics: Analytics,
) : ViewModel() {
    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    val myPhotoUrl: StateFlow<String?> = profiles.observeMyProfile()
        .map { it?.firstPhoto?.url }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private var loadJob: Job? = null
    private var glowJob: Job? = null

    /** Profiles liked this session, so a new match can be celebrated (not ones from earlier). */
    private val recentLikes = mutableMapOf<String, Profile>()
    private var knownMatches: Set<String>? = null
    private val celebrated = mutableSetOf<String>()

    init {
        viewModelScope.launch {
            settings.settings
                .map { it.filters to it.afterDark }
                .distinctUntilChanged()
                .collect { (filters, afterDark) ->
                    _state.update { it.copy(filters = filters, afterDark = afterDark) }
                    reload()
                }
        }
        viewModelScope.launch {
            matches.observeMatches().collect { list ->
                val ids = list.map { it.id }.toSet()
                val known = knownMatches
                knownMatches = ids
                if (known == null) return@collect
                // A match created by the other person's like (or one we missed) still celebrates.
                list.firstOrNull { it.id !in known && it.otherUid in recentLikes }?.let { celebrate(it, recentLikes[it.otherUid]) }
            }
        }
    }

    fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            val s = _state.value
            val result = runCatching { discovery.loadDeck(s.filters, s.afterDark) }
            _state.update {
                it.copy(
                    loading = false,
                    deck = result.getOrDefault(emptyList()),
                    error = result.exceptionOrNull()?.let { "Couldn't load profiles. Check your connection and try again." },
                )
            }
            updateGlow()
        }
    }

    private fun loadMore() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val s = _state.value
            val more = runCatching { discovery.loadDeck(s.filters, s.afterDark) }.getOrDefault(emptyList())
            _state.update { state ->
                val have = state.deck.map { it.uid }.toSet()
                state.copy(deck = state.deck + more.filterNot { it.uid in have || it.uid == state.lastSwipe?.first?.uid })
            }
        }
    }

    fun onSwiped(profile: Profile, direction: SwipeDirection) {
        _state.update { s -> s.copy(deck = s.deck.filterNot { it.uid == profile.uid }, lastSwipe = profile to direction) }
        if (direction != SwipeDirection.Nope) recentLikes[profile.uid] = profile
        analytics.log("swipe", "direction" to direction.key)
        viewModelScope.launch {
            val result = runCatching { discovery.swipe(profile, direction) }
            result.onFailure { e ->
                // Put the card back rather than silently losing a like.
                _state.update { s -> s.copy(deck = listOf(profile) + s.deck, error = e.message ?: "That swipe didn't go through.") }
            }
            // The like itself reports the match, so the celebration never depends on a listener.
            result.getOrNull()?.let { matchId ->
                celebrate(
                    Match(
                        id = matchId,
                        otherUid = profile.uid,
                        otherName = profile.name,
                        otherPhotoUrl = profile.firstPhoto?.url,
                        otherBlurHash = profile.firstPhoto?.blurHash,
                        otherIsDemo = profile.isDemo,
                        createdAt = java.time.Instant.now(),
                        lastMessage = null,
                        myLastRead = null,
                        otherLastRead = null,
                        superLike = direction == SwipeDirection.Super,
                    ),
                    profile,
                )
            }
        }
        updateGlow()
        if (_state.value.deck.size < 4) loadMore()
    }

    /** From the full profile's like/nope buttons. */
    fun swipeByUid(uid: String, direction: SwipeDirection) {
        val profile = _state.value.deck.firstOrNull { it.uid == uid } ?: return
        onSwiped(profile, direction)
    }

    fun rewind() {
        val (profile, _) = _state.value.lastSwipe ?: return
        if (_state.value.rewinding) return
        viewModelScope.launch {
            _state.update { it.copy(rewinding = true) }
            val ok = runCatching { discovery.rewind(profile.uid) }
            _state.update { s ->
                if (ok.getOrDefault(false)) {
                    recentLikes.remove(profile.uid)
                    s.copy(deck = listOf(profile) + s.deck, lastSwipe = null, rewinding = false)
                } else {
                    s.copy(rewinding = false, lastSwipe = null, error = "That one can't be undone (it was a match, or it's been a while).")
                }
            }
            updateGlow()
        }
    }

    fun setAfterDark(enabled: Boolean) {
        viewModelScope.launch { settings.setAfterDark(enabled) }
    }

    fun applyFilters(filters: DiscoveryFilters) {
        viewModelScope.launch { settings.setFilters(filters) }
    }

    private fun celebrate(match: Match, them: Profile?) {
        if (!celebrated.add(match.id)) return
        _state.update { it.copy(celebration = Celebration(match, them)) }
        analytics.log("match", "super" to match.superLike)
    }

    fun dismissCelebration() = _state.update { it.copy(celebration = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun updateGlow() {
        val url = _state.value.deck.firstOrNull()?.firstPhoto?.url ?: return
        glowJob?.cancel()
        glowJob = viewModelScope.launch {
            val color = runCatching { palette.dominant(url) }.getOrNull()
            _state.update { it.copy(glow = color) }
        }
    }
}
