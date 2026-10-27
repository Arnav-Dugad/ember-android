package io.github.arnavdugad.ember.ui.profile

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.designsystem.DemoBadge
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.GlassTag
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PaginationBars
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.ui.main.sharedPhoto
import io.github.arnavdugad.ember.ui.onboarding.formatHeight
import io.github.arnavdugad.ember.ui.safety.SafetySheet

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProfileDetailScreen(
    uid: String,
    initialPhoto: Int,
    fromDeck: Boolean,
    onBack: () -> Unit,
    onDeckAction: (SwipeDirection) -> Unit,
    onOpenAlbum: (ownerName: String) -> Unit,
    onRemoved: () -> Unit,
) {
    val viewModel = hiltViewModel<ProfileDetailViewModel, ProfileDetailViewModel.Factory>(key = uid) { it.create(uid) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    var safetyOpen by remember { mutableStateOf(false) }
    LaunchedEffect(state.removed) { if (state.removed) onRemoved() }

    val profile = state.profile
    GlassScene(
        Modifier.fillMaxSize(),
        backdrop = { Box(Modifier.fillMaxSize().background(colors.background)) },
    ) {
        if (profile == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.loading) {
                    CircularProgressIndicator(color = colors.accent)
                } else {
                    Text("This profile isn't available anymore.", style = EmberTheme.type.body, color = colors.textSecondary)
                }
            }
        } else {
            ProfileBody(
                profile = profile,
                initialPhoto = initialPhoto,
                state = state,
                bottomPadding = if (fromDeck) 120.dp else 40.dp,
                onRequestAlbum = viewModel::requestAlbum,
                onWithdrawAlbum = viewModel::withdrawAlbum,
                onOpenAlbum = { onOpenAlbum(profile.name) },
            )
        }

        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack, style = GlassDefaults.overPhoto(), tint = Color.White)
            Spacer(Modifier.weight(1f))
            if (profile != null) {
                GlassIconButton(Icons.Rounded.MoreHoriz, contentDescription = "Safety options", onClick = { safetyOpen = true }, style = GlassDefaults.overPhoto(), tint = Color.White)
            }
        }

        if (fromDeck && profile != null) {
            Row(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundAction(Icons.Rounded.Close, "Nope", colors.nope, 64) { onDeckAction(SwipeDirection.Nope) }
                RoundAction(Icons.Rounded.Star, "Super like", colors.superLike, 52) { onDeckAction(SwipeDirection.Super) }
                RoundAction(Icons.Rounded.Favorite, "Like", colors.like, 64) { onDeckAction(SwipeDirection.Like) }
            }
        }

        state.message?.let { message ->
            LaunchedEffect(message) {
                kotlinx.coroutines.delay(3_000)
                viewModel.consumeMessage()
            }
            GlassSurface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 70.dp, start = 24.dp, end = 24.dp),
                shape = EmberShapes.tile,
                style = GlassDefaults.chrome(),
                elevated = true,
            ) {
                Text(message, style = EmberTheme.type.bodySmall, color = colors.textPrimary, modifier = Modifier.padding(16.dp))
            }
        }

        SafetySheet(
            visible = safetyOpen,
            name = profile?.name ?: "",
            matched = state.match != null,
            busy = state.busy,
            onDismiss = { safetyOpen = false },
            onReport = { reason, details, block ->
                viewModel.report(reason, details, block)
                safetyOpen = false
            },
            onBlock = viewModel::block,
            onUnmatch = viewModel::unmatch,
        )
    }
}

@Composable
private fun RoundAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color, size: Int, onClick: () -> Unit) {
    Box(
        Modifier
            .size(size.dp)
            .pressable(onClick = onClick, pressedScale = 0.86f)
            .glass(CircleShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size((size * 0.46f).dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProfileBody(
    profile: Profile,
    initialPhoto: Int,
    state: ProfileDetailState,
    bottomPadding: androidx.compose.ui.unit.Dp,
    onRequestAlbum: () -> Unit,
    onWithdrawAlbum: () -> Unit,
    onOpenAlbum: () -> Unit,
) {
    val colors = EmberTheme.colors
    val scroll = rememberScrollState()
    val pager = rememberPagerState(initialPage = initialPhoto.coerceIn(0, (profile.photos.size - 1).coerceAtLeast(0))) { profile.photos.size.coerceAtLeast(1) }
    Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(0.78f)
                .graphicsLayer {
                    // Parallax: the hero scrolls at half speed and fades as the content covers it.
                    translationY = scroll.value * 0.5f
                    alpha = 1f - (scroll.value / (size.height * 1.4f)).coerceIn(0f, 0.6f)
                },
        ) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                val photo = profile.photos.getOrNull(page)
                EmberImage(
                    photo?.url,
                    photo?.blurHash,
                    contentDescription = "${profile.name}'s photo ${page + 1}",
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (page == 0) Modifier.sharedPhoto("photo-${profile.uid}") else Modifier),
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent))),
            )
            PaginationBars(
                profile.photos.size,
                pager.currentPage,
                Modifier.statusBarsPadding().padding(start = 76.dp, end = 76.dp, top = 28.dp),
            )
        }
        Column(
            Modifier
                .graphicsLayer { translationY = -36.dp.toPx() }
                .clip(EmberShapes.sheet)
                .background(colors.background)
                .padding(horizontal = 22.dp, vertical = 24.dp)
                .padding(bottom = bottomPadding),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${profile.name}, ${profile.age}", style = EmberTheme.type.headline, color = colors.textPrimary)
                    if (profile.isDemo) {
                        Spacer(Modifier.width(10.dp))
                        DemoBadge()
                    }
                }
                val place = listOfNotNull(profile.city.ifBlank { null }, Geo.distanceLabel(profile.distanceKm)).joinToString(" · ")
                if (place.isNotEmpty()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        Icon(Icons.Rounded.LocationOn, contentDescription = null, tint = colors.textSecondary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(place, style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                    }
                }
                if (profile.isDemo) {
                    Text(
                        "Demo profiles are illustrated test accounts. They can match with you but never message you.",
                        style = EmberTheme.type.caption,
                        color = colors.textTertiary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassTag("${profile.lookingFor.emoji} ${profile.lookingFor.label}")
                profile.heightCm?.let { GlassTag("📏 ${formatHeight(it)}") }
                profile.gender?.let { GlassTag(it.label) }
            }
            if (profile.bio.isNotBlank()) {
                Section("About") { Text(profile.bio, style = EmberTheme.type.body, color = colors.textPrimary) }
            }
            profile.prompts.forEach { prompt ->
                GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
                    Column(Modifier.padding(20.dp)) {
                        Text(prompt.question, style = EmberTheme.type.label, color = colors.accent)
                        Spacer(Modifier.height(8.dp))
                        Text(prompt.answer, style = EmberTheme.type.title, color = colors.textPrimary)
                    }
                }
            }
            if (profile.vibes.isNotEmpty()) {
                Section("Vibe") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.vibes.mapNotNull(Catalog::vibe).forEach { GlassTag("${it.emoji} ${it.label}") }
                    }
                }
            }
            if (profile.interests.isNotEmpty()) {
                Section("Into") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        profile.interests.forEach { GlassTag(it) }
                    }
                }
            }
            MatchOnlySection(profile, state)
            if (profile.privateAlbumCount > 0) {
                PrivateAlbumCard(profile, state, onRequestAlbum, onWithdrawAlbum, onOpenAlbum)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column {
        SectionHeader(title)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable
private fun MatchOnlySection(profile: Profile, state: ProfileDetailState) {
    val colors = EmberTheme.colors
    val details = state.matchOnly
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Boundaries & turn-ons", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            }
            Spacer(Modifier.height(10.dp))
            when {
                state.match == null -> Text(
                    "Only visible once you match with ${profile.name}.",
                    style = EmberTheme.type.bodySmall,
                    color = colors.textSecondary,
                )
                details == null || (details.boundaries.isEmpty() && details.turnOns.isEmpty()) -> Text(
                    "${profile.name} hasn't added any yet.",
                    style = EmberTheme.type.bodySmall,
                    color = colors.textSecondary,
                )
                else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    details.boundaries.mapNotNull(Catalog::boundary).forEach { GlassTag("${it.emoji} ${it.label}") }
                    details.turnOns.mapNotNull(Catalog::turnOn).forEach { GlassTag("${it.emoji} ${it.label}", textColor = colors.accent) }
                }
            }
        }
    }
}

@Composable
private fun PrivateAlbumCard(profile: Profile, state: ProfileDetailState, onRequest: () -> Unit, onWithdraw: () -> Unit, onOpen: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(20.dp)) {
            Text("Private album · ${profile.privateAlbumCount} photos", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(minOf(profile.privateAlbumCount, 3)) { i ->
                    // Fully frosted stand-ins: no pixels of a private photo reach this screen.
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(0.8f)
                            .clip(EmberShapes.tile)
                            .background(Brush.linearGradient(listOf(colors.gradient[i % 3].copy(alpha = 0.55f), colors.surfaceHigh))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White.copy(alpha = 0.85f))
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            when {
                state.match == null -> Text("Match with ${profile.name} to ask for access.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                state.albumAccess == AccessStatus.Approved -> EmberButton("View album", onClick = onOpen, modifier = Modifier.fillMaxWidth())
                state.albumAccess == AccessStatus.Pending -> {
                    Text(
                        if (profile.isDemo) "Requested. Demo profiles can't approve requests." else "Requested. ${profile.name} decides whether to share.",
                        style = EmberTheme.type.bodySmall,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    GlassButton("Withdraw request", onClick = onWithdraw, modifier = Modifier.fillMaxWidth())
                }
                state.albumAccess == AccessStatus.Denied -> Text("${profile.name} has chosen not to share right now.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                else -> EmberButton("Request access", onClick = onRequest, loading = state.busy, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
