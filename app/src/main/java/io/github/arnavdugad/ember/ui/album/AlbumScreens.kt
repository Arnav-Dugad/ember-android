package io.github.arnavdugad.ember.ui.album

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.ScreenLockPortrait
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.designsystem.AmbientGlow
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberFlame
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassIconButton
import io.github.arnavdugad.ember.core.designsystem.GlassScene
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.shimmer
import io.github.arnavdugad.ember.core.image.EmberImage
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

private const val MAX_PRIVATE = 12

@HiltViewModel
class MyAlbumViewModel @Inject constructor(private val albums: AlbumRepository) : ViewModel() {
    val photos: StateFlow<List<AlbumPhoto>?> = albums.observeMyAlbum().catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val requests: StateFlow<List<AlbumAccessRequest>> = albums.observeRequests().catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uploading = MutableStateFlow(0)
    val uploading: StateFlow<Int> = _uploading.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun add(uris: List<android.net.Uri>) {
        val room = MAX_PRIVATE - (photos.value?.size ?: 0) - _uploading.value
        uris.take(room.coerceAtLeast(0)).forEach { uri ->
            viewModelScope.launch {
                _uploading.update { it + 1 }
                runCatching { albums.addPhoto(uri) }.onFailure { _error.value = it.message }
                _uploading.update { it - 1 }
            }
        }
    }

    fun remove(photo: AlbumPhoto) = act { albums.removePhoto(photo) }
    fun respond(viewer: String, approve: Boolean) = act { albums.respond(viewer, approve) }
    fun revoke(viewer: String) = act { albums.revoke(viewer) }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { _error.value = it.message ?: "That didn't work." } }
    }
}

@HiltViewModel(assistedFactory = AlbumViewerViewModel.Factory::class)
class AlbumViewerViewModel @AssistedInject constructor(
    @Assisted private val ownerUid: String,
    albums: AlbumRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(ownerUid: String): AlbumViewerViewModel
    }

    val access: StateFlow<AccessStatus> = albums.observeAccess(ownerUid).catch { emit(AccessStatus.None) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AccessStatus.Pending)
    val photos: StateFlow<List<AlbumPhoto>?> = albums.observeAlbum(ownerUid).catch { emit(emptyList()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Private photos are cached only while the album is open. */
    override fun onCleared() {
        File(context.cacheDir, "private_album").deleteRecursively()
    }
}

@Composable
fun MyAlbumScreen(onBack: () -> Unit, viewModel: MyAlbumViewModel = hiltViewModel()) {
    SecureWindow()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val requests by viewModel.requests.collectAsStateWithLifecycle()
    val uploading by viewModel.uploading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_PRIVATE)) { viewModel.add(it) }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 76.dp, bottom = 32.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Private album", style = EmberTheme.type.headline, color = colors.textPrimary)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Matches see these fully blurred until you approve them. You can revoke access at any time, and it takes effect immediately.",
                        style = EmberTheme.type.bodySmall,
                        color = colors.textSecondary,
                    )
                    Spacer(Modifier.height(10.dp))
                    ScreenshotNotice()
                    error?.let { Text(it, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(top = 8.dp)) }
                    Spacer(Modifier.height(14.dp))
                }
            }
            val list = photos
            if (list == null) {
                items(3) { Box(Modifier.aspectRatio(0.8f).shimmer(EmberShapes.tile)) }
            } else {
                items(list, key = { it.id }) { photo ->
                    Box(Modifier.aspectRatio(0.8f).clip(EmberShapes.tile)) {
                        EmberImage(photo.url, null, contentDescription = "Private photo", modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(6.dp)
                                .size(26.dp)
                                .background(Color.Black.copy(alpha = 0.55f), CircleShape)
                                .pressable(onClick = { viewModel.remove(photo) }, pressedScale = 0.85f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                items(uploading) { Box(Modifier.aspectRatio(0.8f).shimmer(EmberShapes.tile)) }
                if (list.size + uploading < MAX_PRIVATE) {
                    item {
                        Box(
                            Modifier
                                .aspectRatio(0.8f)
                                .pressable(onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) })
                                .background(colors.surfaceHigh.copy(alpha = 0.6f), EmberShapes.tile),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = "Add private photo", tint = colors.accent)
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pending = requests.filter { it.status == AccessStatus.Pending }
                    val approved = requests.filter { it.status == AccessStatus.Approved }
                    SectionHeader("Requests · ${pending.size}")
                    if (pending.isEmpty()) Text("No one's waiting. Matches can ask from your profile.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                    pending.forEach { r ->
                        RequestRow(r) {
                            GlassButton("Deny", onClick = { viewModel.respond(r.viewerId, false) })
                            Spacer(Modifier.width(8.dp))
                            EmberButton("Share", onClick = { viewModel.respond(r.viewerId, true) })
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    SectionHeader("Can see your album · ${approved.size}")
                    approved.forEach { r -> RequestRow(r) { GlassButton("Revoke", onClick = { viewModel.revoke(r.viewerId) }, contentColor = colors.danger) } }
                }
            }
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = onBack)
        }
    }
}

@Composable
private fun RequestRow(request: AlbumAccessRequest, actions: @Composable () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).clip(CircleShape)) {
                EmberImage(request.viewerPhotoUrl, null, contentDescription = null, modifier = Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Text(request.viewerName, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            actions()
        }
    }
}

@Composable
private fun ScreenshotNotice() {
    val colors = EmberTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ScreenLockPortrait, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text("Screenshots and screen recording are blocked here.", style = EmberTheme.type.caption, color = colors.textSecondary)
    }
}

@Composable
fun AlbumViewerScreen(ownerUid: String, ownerName: String, onBack: () -> Unit) {
    val viewModel = hiltViewModel<AlbumViewerViewModel, AlbumViewerViewModel.Factory>(key = ownerUid) { it.create(ownerUid) }
    SecureWindow()
    val access by viewModel.access.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val colors = EmberTheme.colors
    // Consent reminder every time the album is opened, before a single photo is shown.
    var consented by remember { mutableStateOf(false) }
    var fullscreen by remember { mutableStateOf<AlbumPhoto?>(null) }
    BackHandler(enabled = fullscreen != null) { fullscreen = null }

    GlassScene(Modifier.fillMaxSize(), backdrop = { AmbientGlow(intensity = 0.6f) }) {
        when {
            access != AccessStatus.Approved -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(Icons.Rounded.Lock, contentDescription = null, tint = colors.accent, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(14.dp))
                Text("$ownerName's album is private", style = EmberTheme.type.title, color = colors.textPrimary, textAlign = TextAlign.Center)
                Text("Access was revoked or hasn't been granted.", style = EmberTheme.type.bodySmall, color = colors.textSecondary, textAlign = TextAlign.Center)
            }
            !consented -> Column(
                Modifier.fillMaxSize().padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                EmberFlame(size = 64.dp)
                Spacer(Modifier.height(20.dp))
                Text("$ownerName trusted you", style = EmberTheme.type.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
                Spacer(Modifier.height(10.dp))
                Text(
                    "These photos were shared with you alone. Keep them between the two of you: don't save, screenshot, photograph or share them. $ownerName can revoke access at any time.",
                    style = EmberTheme.type.body,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(10.dp))
                ScreenshotNotice()
                Spacer(Modifier.height(26.dp))
                EmberButton("I understand, show me", onClick = { consented = true }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                GlassButton("Not now", onClick = onBack, modifier = Modifier.fillMaxWidth())
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 76.dp, bottom = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                val list = photos
                if (list == null) {
                    items(4) { Box(Modifier.aspectRatio(0.8f).shimmer(EmberShapes.tile)) }
                } else {
                    items(list, key = { it.id }) { photo ->
                        Box(Modifier.aspectRatio(0.8f).clip(EmberShapes.tile).pressable(onClick = { fullscreen = photo }, pressedScale = 0.97f)) {
                            EmberImage(photo.url, null, contentDescription = "Private photo", modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
        AnimatedVisibility(fullscreen != null, enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.fillMaxSize().background(Color.Black).pressable(onClick = { fullscreen = null }, pressedScale = 1f, haptic = false), contentAlignment = Alignment.Center) {
                val url = fullscreen?.url
                if (url == null) CircularProgressIndicator(color = colors.accent) else AsyncImage(url, contentDescription = "Private photo", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
            }
        }
        Row(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", onClick = { if (fullscreen != null) fullscreen = null else onBack() }, style = GlassDefaults.chrome())
            Spacer(Modifier.width(12.dp))
            Text("$ownerName's private album", style = EmberTheme.type.titleSmall, color = if (fullscreen != null) Color.White else colors.textPrimary)
        }
    }
}
