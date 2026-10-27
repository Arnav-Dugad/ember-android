package io.github.arnavdugad.ember.ui.likes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GlassTag
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.core.image.rememberBlurHashPainter
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.LikeNoteKind
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.ui.profile.likedWhat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** One tile: who liked you, their profile once fetched, and whether you've matched (unblurred). */
data class AdmirerTile(val admirer: Admirer, val profile: Profile?, val matched: Boolean)

/** Super likers the user has already felt a heartbeat for, kept for the life of the process. */
@Singleton
class SuperLikeHeartbeats @Inject constructor() {
    private val felt = HashSet<String>()

    /** True when [uids] holds anyone not felt before; they all count as felt from now on. */
    @Synchronized
    fun anyNew(uids: Collection<String>): Boolean = felt.addAll(uids)
}

@HiltViewModel
class LikesYouViewModel @Inject constructor(
    discovery: DiscoveryRepository,
    matches: MatchRepository,
    private val profiles: ProfileRepository,
    private val heartbeats: SuperLikeHeartbeats,
) : ViewModel() {
    private val fetched = MutableStateFlow<Map<String, Profile?>>(emptyMap())
    private val requested = mutableSetOf<String>()

    /** My own profile: a note names one of my prompts or photos by id. */
    val me: StateFlow<Profile?> = profiles.observeMyProfile().catch { emit(null) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Null while loading. Admirers whose profile is gone (deleted account) are dropped. */
    val tiles: StateFlow<List<AdmirerTile>?> = combine(
        discovery.observeLikesYou().catch { emit(emptyList()) },
        matches.observeMatches().catch { emit(emptyList()) },
        fetched,
    ) { admirers, matchList, cache ->
        admirers.forEach { fetch(it.uid) }
        val matched = matchList.map { it.otherUid }.toSet()
        admirers
            .filter { !cache.containsKey(it.uid) || cache[it.uid] != null }
            .map { AdmirerTile(it, cache[it.uid], it.uid in matched) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Fires when an unmatched super liker appears who hasn't been announced yet. */
    val superLikeArrived: Flow<Unit> = tiles
        .filterNotNull()
        .filter { list -> heartbeats.anyNew(list.filter { it.admirer.superLike && !it.matched }.map { it.admirer.uid }) }
        .map { }

    private fun fetch(uid: String) {
        if (!requested.add(uid)) return
        viewModelScope.launch {
            val profile = runCatching { profiles.getProfile(uid) }.getOrNull()
            fetched.update { it + (uid to profile) }
        }
    }
}

/**
 * Everyone who liked you, in a grid of heavily frosted faces. Anyone you've matched with comes
 * into focus. The rest stay blurred: like them back from your deck to find out who they are.
 */
@Composable
fun LikesYouScreen(onBack: () -> Unit, onOpenProfile: (String) -> Unit, onBackToDeck: () -> Unit, viewModel: LikesYouViewModel = hiltViewModel()) {
    val tiles by viewModel.tiles.collectAsStateWithLifecycle()
    val me by viewModel.me.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    LaunchedEffect(viewModel) { viewModel.superLikeArrived.collect { haptics.heartbeat() } }
    var mysteryOpen by remember { mutableStateOf(false) }
    // Kept after closing so the sheet doesn't empty out while it slides away.
    var mystery by remember { mutableStateOf<AdmirerTile?>(null) }
    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.7f) }) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Likes you", style = EmberTheme.type.title, color = colors.textPrimary)
                    val list = tiles
                    if (!list.isNullOrEmpty()) {
                        val hidden = list.count { !it.matched }
                        Text(
                            if (hidden > 0) "$hidden waiting · like them back from your deck" else "You've matched with all of them",
                            style = EmberTheme.type.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }
            val list = tiles
            when {
                list == null -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    userScrollEnabled = false,
                    modifier = Modifier.semantics { contentDescription = "Loading the people who like you" },
                ) {
                    items(6) { Box(Modifier.fillMaxWidth().aspectRatio(0.75f).shimmer(EmberShapes.tile)) }
                }
                list.isEmpty() -> EmptyLikes(onBackToDeck)
                else -> LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.navigationBarsPadding(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    itemsIndexed(list, key = { _, tile -> tile.admirer.uid }) { index, tile ->
                        AdmirerCard(tile, index) {
                            if (tile.matched) {
                                onOpenProfile(tile.admirer.uid)
                            } else {
                                mystery = tile
                                mysteryOpen = true
                            }
                        }
                    }
                    item(span = { GridItemSpan(2) }) {
                        Text(
                            "Faces stay frosted until you match. Nobody knows you looked.",
                            style = EmberTheme.type.caption,
                            color = colors.textTertiary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        }

        GlassBottomSheet(visible = mysteryOpen, onDismiss = { mysteryOpen = false }) {
            mystery?.let { tile ->
                MysterySheet(tile, me, onBackToDeck = {
                    mysteryOpen = false
                    onBackToDeck()
                })
            }
        }
    }
}

@Composable
private fun AdmirerCard(tile: AdmirerTile, index: Int, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    val profile = tile.profile
    // Matched people come into focus every time the screen opens: the reveal is the reward.
    val clear = remember(tile.admirer.uid) { Animatable(0f) }
    LaunchedEffect(tile.matched) {
        if (tile.matched) {
            delay(180L + index * 70L)
            clear.animateTo(1f, tween(1_100, easing = FastOutSlowInEasing))
        } else {
            clear.snapTo(0f)
        }
    }
    val note = tile.admirer.note
    val description = buildString {
        append(if (tile.matched && profile != null) "${profile.name}, matched" else "Someone who likes you")
        if (tile.admirer.superLike) append(", super like")
        if (note != null) {
            append(". ").append(likedWhat(note))
            if (note.text.isNotBlank()) append(": ").append(note.text)
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(0.75f)
            .pressable(onClick = onClick, pressedScale = 0.96f)
            .clip(EmberShapes.tile)
            .background(colors.surfaceHigh)
            // One clear description, and never the name of someone you haven't matched.
            .clearAndSetSemantics { contentDescription = description },
    ) {
        if (profile == null) {
            Box(Modifier.fillMaxSize().shimmer(EmberShapes.tile))
        } else {
            FrostedPhoto(profile, clear.value)
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f))),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                if (tile.matched) {
                    Text(
                        "${profile.name}, ${profile.age}",
                        style = EmberTheme.type.titleSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.graphicsLayer { alpha = clear.value },
                    )
                }
                if (note != null) {
                    // What they liked, and their comment: visible even while the face is frosted.
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.like, modifier = Modifier.size(12.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(likedWhat(note), style = EmberTheme.type.caption, color = Color.White.copy(alpha = 0.9f), maxLines = 1)
                    }
                    if (note.text.isNotBlank()) {
                        Text(
                            "“${note.text}”",
                            style = EmberTheme.type.bodySmall,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                } else if (tile.matched) {
                    Text("Matched", style = EmberTheme.type.caption, color = Color.White.copy(alpha = 0.85f), modifier = Modifier.graphicsLayer { alpha = clear.value })
                }
            }
            if (clear.value < 1f) {
                Icon(
                    Icons.Rounded.Favorite,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.9f * (1f - clear.value)),
                    modifier = Modifier.align(Alignment.Center).size(30.dp),
                )
            }
        }
        if (tile.admirer.superLike) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .glass(CircleShape, GlassDefaults.overPhoto(), LocalHazeState.current),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Star, contentDescription = "Super like", tint = colors.superLike, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * [clear] 0 is frosted, 1 is in focus. The frosted face is a tiny 16 px decode scaled up (soft on
 * every Android version, and no detail ever reaches the screen), blurred again where RenderEffect
 * exists, under a milky frost. The sharp photo only loads once it starts to clear.
 */
@Composable
private fun FrostedPhoto(profile: Profile, clear: Float) {
    val context = LocalContext.current
    val photo = profile.firstPhoto
    val url = photo?.url
    val placeholder = rememberBlurHashPainter(photo?.blurHash)
    val tiny = remember(url) { url?.let { ImageRequest.Builder(context).data(it).size(16).memoryCacheKey("$it#frost").build() } }
    Box(Modifier.fillMaxSize()) {
        if (placeholder != null) {
            androidx.compose.foundation.Image(placeholder, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        if (tiny != null && clear < 1f) {
            AsyncImage(
                model = tiny,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(24.dp).graphicsLayer { alpha = 1f - clear * 0.5f },
            )
        }
        if (url != null && clear > 0f) {
            AsyncImage(
                model = url,
                contentDescription = "${profile.name}'s photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .blur((22f * (1f - clear)).dp)
                    .graphicsLayer { alpha = clear },
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = 1f - clear }
                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.22f), Color.White.copy(alpha = 0.08f)))),
        )
    }
}

@Composable
private fun MysterySheet(tile: AdmirerTile, me: Profile?, onBackToDeck: () -> Unit) {
    val colors = EmberTheme.colors
    val note = tile.admirer.note
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(96.dp).clip(CircleShape)) {
            tile.profile?.let { FrostedPhoto(it, clear = 0f) }
        }
        Spacer(Modifier.height(16.dp))
        Text(
            when {
                note != null -> "Someone ${likedWhat(note).replaceFirstChar { it.lowercase() }}"
                tile.admirer.superLike -> "Someone super liked you"
                else -> "Someone likes you"
            },
            style = EmberTheme.type.headline,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
        )
        if (note != null) {
            Spacer(Modifier.height(14.dp))
            LikedNoteCard(note, me)
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (note != null) {
                "Like them back from your deck and you'll match instantly, with this as their first message."
            } else {
                "Like them back from your deck and you'll match instantly. They're in there somewhere: keep swiping."
            },
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        if (tile.admirer.superLike) {
            Spacer(Modifier.height(12.dp))
            GlassTag("Super like", leading = { Icon(Icons.Rounded.Star, contentDescription = null, tint = colors.superLike, modifier = Modifier.size(14.dp)) })
        }
        Spacer(Modifier.height(20.dp))
        EmberButton("Like them back from your deck", onClick = onBackToDeck, modifier = Modifier.fillMaxWidth())
    }
}

/**
 * What they liked: my prompt (question and answer) or a thumbnail of my photo, then their comment.
 * If I've since removed it, just the comment.
 */
@Composable
private fun LikedNoteCard(note: LikeNote, me: Profile?) {
    val colors = EmberTheme.colors
    val prompt = if (note.kind == LikeNoteKind.Prompt) me?.prompts?.firstOrNull { it.id == note.ref } else null
    val photo = if (note.kind == LikeNoteKind.Photo) me?.photos?.firstOrNull { it.id == note.ref } else null
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(16.dp)) {
            when {
                prompt != null -> {
                    Text(prompt.question, style = EmberTheme.type.label, color = colors.accent)
                    Spacer(Modifier.height(4.dp))
                    Text(prompt.answer, style = EmberTheme.type.bodySmall, color = colors.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                photo != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    EmberImage(photo.url, photo.blurHash, contentDescription = "Your photo", modifier = Modifier.size(56.dp).clip(EmberShapes.small))
                    Spacer(Modifier.width(12.dp))
                    Text("Your photo", style = EmberTheme.type.label, color = colors.accent)
                }
                else -> Text(likedWhat(note), style = EmberTheme.type.label, color = colors.accent)
            }
            if (note.text.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Rounded.Favorite, contentDescription = null, tint = colors.like, modifier = Modifier.padding(top = 3.dp).size(14.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("“${note.text}”", style = EmberTheme.type.body, color = colors.textPrimary)
                }
            }
        }
    }
}

@Composable
private fun EmptyLikes(onBackToDeck: () -> Unit) {
    val colors = EmberTheme.colors
    Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 32.dp, end = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        EmberFlame(size = 72.dp)
        Spacer(Modifier.height(18.dp))
        Text("No likes yet", style = EmberTheme.type.title, color = colors.textPrimary, modifier = Modifier.semantics { heading() })
        Spacer(Modifier.height(6.dp))
        Text(
            "When someone likes you, they'll appear here, frosted until you match. A great first photo and a prompt or two help.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(22.dp))
        GlassButton("Back to Discover", onClick = onBackToDeck)
    }
}
