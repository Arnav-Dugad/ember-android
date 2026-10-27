package io.github.arnavdugad.ember.ui.main

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.LocalOverlayCount
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.ui.album.AlbumViewerScreen
import io.github.arnavdugad.ember.ui.album.MyAlbumScreen
import io.github.arnavdugad.ember.ui.chat.ChatScreen
import io.github.arnavdugad.ember.ui.discover.DiscoverScreen
import io.github.arnavdugad.ember.ui.discover.DiscoverViewModel
import io.github.arnavdugad.ember.ui.likes.LikesYouScreen
import io.github.arnavdugad.ember.ui.matches.MatchesScreen
import io.github.arnavdugad.ember.ui.matches.MatchesViewModel
import io.github.arnavdugad.ember.ui.profile.EditProfileScreen
import io.github.arnavdugad.ember.ui.profile.EditSection
import io.github.arnavdugad.ember.ui.profile.MeScreen
import io.github.arnavdugad.ember.ui.profile.ProfileDetailScreen
import io.github.arnavdugad.ember.ui.root.DeepLink
import io.github.arnavdugad.ember.ui.root.DeepLinks
import io.github.arnavdugad.ember.ui.settings.SettingsScreen
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.serialization.Serializable

@Serializable data object DiscoverRoute

@Serializable data object MatchesRoute

@Serializable data object MeRoute

@Serializable data class ProfileRoute(val uid: String, val photoIndex: Int = 0, val fromDeck: Boolean = false)

@Serializable data class ChatRoute(val matchId: String)

/** [section] is an [EditSection] key to scroll to, from a profile-strength tip. */
@Serializable data class EditProfileRoute(val section: String? = null)

@Serializable data object SettingsRoute

@Serializable data object MyAlbumRoute

@Serializable data object LikesYouRoute

@Serializable data class AlbumViewerRoute(val ownerUid: String, val ownerName: String)

/** Height reserved at the bottom of tab screens for the floating nav bar. */
val BottomBarSpace = 96.dp

private const val DECK_ACTION = "deckAction"

val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavScope = staticCompositionLocalOf<AnimatedVisibilityScope?> { null }

/** Card-to-profile shared element. A no-op outside a navigation transition. */
@Composable
fun Modifier.sharedPhoto(key: String): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val nav = LocalNavScope.current ?: return this
    return with(shared) {
        this@sharedPhoto.sharedElement(
            sharedContentState = rememberSharedContentState(key),
            animatedVisibilityScope = nav,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.85f, stiffness = 380f) },
        )
    }
}

@Composable
fun MainScaffold(deepLinks: DeepLinks) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val tab = when {
        destination?.hasRoute<DiscoverRoute>() == true -> 0
        destination?.hasRoute<MatchesRoute>() == true -> 1
        destination?.hasRoute<MeRoute>() == true -> 2
        else -> -1
    }
    val matchesVm: MatchesViewModel = hiltViewModel()
    val unread by matchesVm.unreadCount.collectAsStateWithLifecycle()
    val pending by deepLinks.pending.collectAsStateWithLifecycle()
    LaunchedEffect(pending) {
        when (val link = pending) {
            is DeepLink.Chat -> nav.navigate(ChatRoute(link.matchId))
            DeepLink.Matches -> nav.navigate(MatchesRoute) { launchSingleTop = true }
            DeepLink.Album -> nav.navigate(MyAlbumRoute)
            DeepLink.Update, null -> return@LaunchedEffect
        }
        deepLinks.consume()
    }

    fun selectTab(index: Int) {
        val route: Any = when (index) {
            0 -> DiscoverRoute
            1 -> MatchesRoute
            else -> MeRoute
        }
        nav.navigate(route) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    val haze = rememberHazeState()
    val overlays = remember { mutableIntStateOf(0) }
    Box(Modifier.fillMaxSize()) {
        SharedTransitionLayout(Modifier.fillMaxSize().hazeSource(haze)) {
            CompositionLocalProvider(LocalSharedScope provides this, LocalOverlayCount provides overlays) {
                NavHost(
                    navController = nav,
                    startDestination = DiscoverRoute,
                    enterTransition = { fadeIn() + slideInHorizontally { it / 6 } },
                    exitTransition = { fadeOut() + slideOutHorizontally { -it / 10 } },
                    popEnterTransition = { fadeIn() + slideInHorizontally { -it / 10 } },
                    popExitTransition = { fadeOut() + slideOutHorizontally { it / 6 } },
                ) {
                    composable<DiscoverRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) { backStack ->
                        val vm: DiscoverViewModel = hiltViewModel()
                        val action by backStack.savedStateHandle.getStateFlow<String?>(DECK_ACTION, null).collectAsStateWithLifecycle()
                        LaunchedEffect(action) {
                            val parts = action?.split(':') ?: return@LaunchedEffect
                            SwipeDirection.entries.firstOrNull { it.key == parts.getOrNull(1) }?.let { vm.swipeByUid(parts[0], it) }
                            backStack.savedStateHandle[DECK_ACTION] = null
                        }
                        CompositionLocalProvider(LocalNavScope provides this) {
                            DiscoverScreen(
                                viewModel = vm,
                                onOpenProfile = { uid, index -> nav.navigate(ProfileRoute(uid, index, fromDeck = true)) },
                                onOpenChat = { nav.navigate(ChatRoute(it)) },
                                onOpenLikes = { nav.navigate(LikesYouRoute) },
                            )
                        }
                    }
                    composable<MatchesRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                        MatchesScreen(matchesVm, onOpenChat = { nav.navigate(ChatRoute(it)) })
                    }
                    composable<MeRoute>(enterTransition = { fadeIn() }, exitTransition = { fadeOut() }) {
                        MeScreen(
                            onEdit = { section -> nav.navigate(EditProfileRoute(section?.key)) },
                            onSettings = { nav.navigate(SettingsRoute) },
                            onAlbum = { nav.navigate(MyAlbumRoute) },
                            onPreview = { uid -> nav.navigate(ProfileRoute(uid)) },
                        )
                    }
                    composable<ProfileRoute>(
                        enterTransition = { fadeIn() + slideInVertically { it / 8 } },
                        popExitTransition = { fadeOut() + slideOutVertically { it / 8 } },
                    ) { backStack ->
                        val route = backStack.toRoute<ProfileRoute>()
                        CompositionLocalProvider(LocalNavScope provides this) {
                            ProfileDetailScreen(
                                uid = route.uid,
                                initialPhoto = route.photoIndex,
                                fromDeck = route.fromDeck,
                                onBack = { nav.popBackStack() },
                                onDeckAction = { dir ->
                                    nav.previousBackStackEntry?.savedStateHandle?.set(DECK_ACTION, "${route.uid}:${dir.key}")
                                    nav.popBackStack()
                                },
                                onOpenAlbum = { name -> nav.navigate(AlbumViewerRoute(route.uid, name)) },
                                onRemoved = { nav.popBackStack(DiscoverRoute, inclusive = false) },
                            )
                        }
                    }
                    composable<ChatRoute> { backStack ->
                        val route = backStack.toRoute<ChatRoute>()
                        ChatScreen(
                            matchId = route.matchId,
                            onBack = { nav.popBackStack() },
                            onOpenProfile = { uid -> nav.navigate(ProfileRoute(uid)) },
                            onRemoved = { nav.popBackStack() },
                        )
                    }
                    composable<EditProfileRoute> { backStack ->
                        val route = backStack.toRoute<EditProfileRoute>()
                        EditProfileScreen(onBack = { nav.popBackStack() }, initialSection = EditSection.of(route.section))
                    }
                    composable<SettingsRoute> { SettingsScreen(onBack = { nav.popBackStack() }) }
                    composable<MyAlbumRoute> { MyAlbumScreen(onBack = { nav.popBackStack() }) }
                    composable<LikesYouRoute> {
                        LikesYouScreen(
                            onBack = { nav.popBackStack() },
                            onOpenProfile = { uid -> nav.navigate(ProfileRoute(uid)) },
                            onBackToDeck = { nav.popBackStack(DiscoverRoute, inclusive = false) },
                        )
                    }
                    composable<AlbumViewerRoute> { backStack ->
                        val route = backStack.toRoute<AlbumViewerRoute>()
                        AlbumViewerScreen(ownerUid = route.ownerUid, ownerName = route.ownerName, onBack = { nav.popBackStack() })
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = tab >= 0 && overlays.intValue == 0,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            GlassNavBar(selected = tab, unread = unread, onSelect = ::selectTab, haze = haze)
        }
    }
}

private data class TabItem(val label: String, val icon: ImageVector)

private val tabs = listOf(
    TabItem("Discover", Icons.Rounded.LocalFireDepartment),
    TabItem("Matches", Icons.Rounded.ChatBubble),
    TabItem("Profile", Icons.Rounded.Person),
)

/** Floating frosted pill. The selected tab grows a gradient capsule that springs between items. */
@Composable
private fun GlassNavBar(selected: Int, unread: Int, onSelect: (Int) -> Unit, haze: dev.chrisbanes.haze.HazeState) {
    val colors = EmberTheme.colors
    Row(
        Modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .glass(PillShape, GlassDefaults.chrome(), haze, elevated = true)
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEachIndexed { index, item ->
            val isSelected = index == selected
            val width by animateDpAsState(if (isSelected) 124.dp else 64.dp, spring(dampingRatio = 0.72f, stiffness = Spring.StiffnessMediumLow), label = "tabWidth")
            val tint by animateColorAsState(if (isSelected) colors.onAccent else colors.textSecondary, label = "tabTint")
            Box(
                Modifier
                    .width(width)
                    .height(52.dp)
                    .pressable(onClick = { onSelect(index) }, pressedScale = 0.92f)
                    .then(if (isSelected) Modifier.background(colors.accentHorizontal, PillShape) else Modifier),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        Icon(item.icon, contentDescription = item.label, tint = tint, modifier = Modifier.size(24.dp))
                        if (index == 1 && unread > 0) {
                            Box(
                                Modifier
                                    .align(Alignment.TopEnd)
                                    .offset(x = 4.dp, y = (-2).dp)
                                    .size(10.dp)
                                    .background(colors.accentAlt, CircleShape),
                            )
                        }
                    }
                    AnimatedVisibility(isSelected, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                        Text(item.label, style = EmberTheme.type.caption, color = tint, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

