package io.github.arnavdugad.ember.ui.discover

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.analytics.Analytics
import io.github.arnavdugad.ember.core.image.PaletteExtractor
import io.github.arnavdugad.ember.core.image.interleave
import io.github.arnavdugad.ember.data.location.CitySuggestion
import io.github.arnavdugad.ember.data.location.LocationRepository
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.LikeNoteCourier
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDateTime
import javax.inject.Inject
import io.github.arnavdugad.ember.data.ai.AiConfig
import io.github.arnavdugad.ember.data.ai.AiSettingsRepository

data class DiscoverState(
    val deck: List<Profile> = emptyList(),
    val loading: Boolean = true,
    /** Pull-to-refresh in progress (the deck stays visible underneath). */
    val refreshing: Boolean = false,
    /** Bumped whenever a fresh deck replaces the old one, so the cards are dealt in again. */
    val dealKey: Int = 0,
    val error: String? = null,
    val filters: DiscoveryFilters = DiscoveryFilters(),
    val afterDark: Boolean = false,
    val lastSwipe: Pair<Profile, SwipeDirection>? = null,
    val rewinding: Boolean = false,
    val glow: Color? = null,
    val celebration: Celebration? = null,
    val picks: List<Pick> = emptyList(),
    /** True from 6 pm, when today's picks are out; before that the list is yesterday's. */
    val picksFresh: Boolean = false,
)

/**
 * A match to celebrate. [theySuperLiked] is whether the other person's like was a super like (the
 * match's own flag is whoever completed it); [confetti] fills in with colours from both photos.
 */
data class Celebration(
    val match: Match,
    val them: Profile?,
    val theySuperLiked: Boolean = false,
    val confetti: List<Color> = emptyList(),
)

@HiltViewModel
class DiscoverViewModel @Inject constructor(
    private val discovery: DiscoveryRepository,
    private val matches: MatchRepository,
    private val profiles: ProfileRepository,
    private val settings: SettingsRepository,
    private val location: LocationRepository,
    private val palette: PaletteExtractor,
    private val analytics: Analytics,
    private val likeNotes: LikeNoteCourier,
    aiSettings: AiSettingsRepository,
) : ViewModel() {
    /** Whether demo profiles reply, for the match screen's note. */
    val ai: StateFlow<AiConfig> = aiSettings.config.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiConfig())

    private val _state = MutableStateFlow(DiscoverState())
    val state: StateFlow<DiscoverState> = _state.asStateFlow()

    val me: StateFlow<Profile?> = profiles.observeMyProfile()
        .catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val myPhotoUrl: StateFlow<String?> = me.map { it?.firstPhoto?.url }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Everyone who liked me; also tells a celebration whether their like was a super like. */
    private val admirers: StateFlow<List<Admirer>> = discovery.observeLikesYou()
        .catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** People who liked me and aren't matched yet: the "Likes you" chip's count. */
    val likesYouCount: StateFlow<Int> = combine(
        admirers,
        matches.observeMatches().catch { emit(emptyList()) },
    ) { likes, matchList ->
        val matched = matchList.map { it.otherUid }.toSet()
        likes.count { it.uid !in matched }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private var loadJob: Job? = null
    private var glowJob: Job? = null

    /** Everyone the last full load returned: daily picks and the profile screen's buttons draw from it. */
    private var pool: List<Profile> = emptyList()
    private val swipedThisSession = mutableSetOf<String>()

    /** Profiles liked this session, so a new match can be celebrated (not ones from earlier). */
    private val recentLikes = mutableMapOf<String, Profile>()
    private val mySuperLikes = mutableSetOf<String>()
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

    fun reload() = load(pull = false)

    /** Pull to refresh: the deck stays put until the new one is in, then it's dealt again. */
    fun refresh() = load(pull = true)

    private fun load(pull: Boolean) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, refreshing = pull, error = null) }
            val s = _state.value
            val result = runCatching { discovery.loadDeck(s.filters, s.afterDark) }
            result.getOrNull()?.let { pool = it }
            _state.update { s ->
                s.copy(
                    loading = false,
                    refreshing = false,
                    // A failed pull keeps the cards you had; a failed load shows the error state.
                    deck = result.getOrElse { if (pull) s.deck else emptyList() },
                    dealKey = if (result.isSuccess) s.dealKey + 1 else s.dealKey,
                    error = result.exceptionOrNull()?.let { "Couldn't load profiles. Check your connection and try again." },
                )
            }
            updateGlow()
            updatePicks()
        }
    }

    private fun loadMore() {
        if (loadJob?.isActive == true) return
        loadJob = viewModelScope.launch {
            val s = _state.value
            val more = runCatching { discovery.loadDeck(s.filters, s.afterDark) }.getOrDefault(emptyList())
            _state.update { state ->
                val have = state.deck.map { it.uid }.toSet()
                state.copy(deck = state.deck + more.filterNot { it.uid in have || it.uid == state.lastSwipe?.first?.uid || it.uid in swipedThisSession })
            }
        }
    }

    fun onSwiped(profile: Profile, direction: SwipeDirection, note: LikeNote? = null) {
        swipedThisSession += profile.uid
        _state.update { s ->
            s.copy(
                deck = s.deck.filterNot { it.uid == profile.uid },
                picks = s.picks.filterNot { it.profile.uid == profile.uid },
                lastSwipe = profile to direction,
            )
        }
        if (direction != SwipeDirection.Nope) recentLikes[profile.uid] = profile
        if (direction == SwipeDirection.Super) mySuperLikes += profile.uid
        analytics.log("swipe", "direction" to direction.key, "note" to (note?.kind?.key ?: "none"))
        viewModelScope.launch {
            val result = runCatching { discovery.swipe(profile, direction, note) }
            result.onFailure { e ->
                // Put the card back rather than silently losing a like.
                swipedThisSession -= profile.uid
                _state.update { s -> s.copy(deck = listOf(profile) + s.deck, error = e.message ?: "That swipe didn't go through.") }
            }
            // The like itself reports the match, so the celebration never depends on a listener.
            result.getOrNull()?.let { matchId ->
                // My prompt or photo note opens the chat, quoting what I liked.
                if (note != null) launch { likeNotes.deliver(matchId, profile, note) }
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

    /** From the full profile's like/nope buttons (a deck card or one of today's picks). */
    fun swipeByUid(uid: String, direction: SwipeDirection, note: LikeNote? = null) {
        val s = _state.value
        val profile = s.deck.firstOrNull { it.uid == uid }
            ?: s.picks.firstOrNull { it.profile.uid == uid }?.profile
            ?: pool.firstOrNull { it.uid == uid && it.uid !in swipedThisSession }
            ?: return
        onSwiped(profile, direction, note)
    }

    /** The "Online now" chip. */
    fun setOnlineOnly(enabled: Boolean) {
        applyFilters(_state.value.filters.copy(onlineOnly = enabled))
        analytics.log("online_only", "on" to enabled)
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
                    swipedThisSession -= profile.uid
                    s.copy(deck = listOf(profile) + s.deck, lastSwipe = null, rewinding = false)
                } else {
                    s.copy(rewinding = false, lastSwipe = null, error = "That one can't be undone (it was a match, or it's been a while).")
                }
            }
            updateGlow()
            updatePicks()
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
        // Their like doc says so directly; failing that, a super-like match I didn't super like was theirs.
        val theySuper = admirers.value.any { it.uid == match.otherUid && it.superLike } ||
            (match.superLike && match.otherUid !in mySuperLikes)
        _state.update { it.copy(celebration = Celebration(match, them, theySuperLiked = theySuper)) }
        analytics.log("match", "super" to match.superLike)
        // The confetti starts in brand colours and takes on both photos' colours once sampled.
        val myUrl = myPhotoUrl.value
        viewModelScope.launch {
            val theirs = async { vividColors(match.otherPhotoUrl) }
            val mine = async { vividColors(myUrl) }
            val colors = interleave(theirs.await(), mine.await())
            if (colors.isEmpty()) return@launch
            _state.update { s ->
                val c = s.celebration
                if (c?.match?.id == match.id) s.copy(celebration = c.copy(confetti = colors)) else s
            }
        }
    }

    /** Travel mode off: back to browsing from your own city. */
    fun stopTravel() {
        viewModelScope.launch { settings.clearTravel() }
    }

    fun setFreeTonight(enabled: Boolean) {
        viewModelScope.launch {
            runCatching { profiles.setFreeTonight(enabled) }.onFailure {
                _state.update { s -> s.copy(error = "Couldn't update Free tonight. Try again.") }
            }
        }
        analytics.log("free_tonight", "on" to enabled)
    }

    suspend fun searchCities(query: String): List<CitySuggestion> = location.searchCities(query)

    fun dismissCelebration() = _state.update { it.copy(celebration = null) }

    fun clearError() = _state.update { it.copy(error = null) }

    /**
     * Today's picks: chosen once per picks-day from the loaded candidates and remembered on the
     * device, then shown minus anyone swiped since. Recomputed after every load because the day
     * may have rolled over at 6 pm.
     */
    private suspend fun updatePicks() {
        val mine = me.value ?: myProfileOnce() ?: return
        val now = LocalDateTime.now()
        val day = DailyPicks.dayOf(now)
        val stored = runCatching { settings.dailyPicks(mine.uid, day.toString()) }.getOrNull()
        val uids = stored ?: DailyPicks.choose(pool, mine, mine.uid, day).map { it.uid }.also { chosen ->
            if (chosen.isNotEmpty()) runCatching { settings.setDailyPicks(mine.uid, day.toString(), chosen) }
        }
        val byUid = pool.associateBy { it.uid }
        val picks = uids.mapNotNull { byUid[it] }
            .filter { it.uid !in swipedThisSession }
            .map { Pick(it, DailyPicks.reason(mine, it)) }
        _state.update { it.copy(picks = picks, picksFresh = DailyPicks.isFresh(now)) }
    }

    private suspend fun myProfileOnce(): Profile? =
        runCatching { withTimeoutOrNull(3_000) { profiles.observeMyProfile().first { it != null } } }.getOrNull()

    private suspend fun vividColors(url: String?): List<Color> =
        url?.let { runCatching { palette.vivid(it) }.getOrNull() }.orEmpty()

    private fun updateGlow() {
        val url = _state.value.deck.firstOrNull()?.firstPhoto?.url ?: return
        glowJob?.cancel()
        glowJob = viewModelScope.launch {
            val color = runCatching { palette.dominant(url) }.getOrNull()
            _state.update { it.copy(glow = color) }
        }
    }
}
