package io.github.arnavdugad.ember.ui.profile

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import kotlin.math.sin

/** What's playing. [key] is a uid, or a local recording's path in the editor. */
data class VoicePlayback(val key: String? = null, val loading: Boolean = false, val playing: Boolean = false, val progress: Float = 0f)

/**
 * Plays voice intros, one at a time per screen. Clips are fetched on demand (they are blobs in
 * Firestore) and played from a cached file.
 */
@HiltViewModel
class VoiceViewModel @Inject constructor(private val profiles: ProfileRepository) : ViewModel() {
    private val _state = MutableStateFlow(VoicePlayback())
    val state: StateFlow<VoicePlayback> = _state.asStateFlow()
    private var player: MediaPlayer? = null
    private var job: Job? = null

    /** Plays [uid]'s intro, or stops it if it's the one playing. */
    fun toggle(uid: String) = toggle(uid) { profiles.voiceFile(uid) }

    /** Plays a local recording (the editor's fresh clip). */
    fun toggleFile(file: File) = toggle(file.path) { file.takeIf { it.exists() } }

    private fun toggle(key: String, source: suspend () -> File?) {
        val current = _state.value
        val same = current.key == key && (current.playing || current.loading)
        stop()
        if (same) return
        job = viewModelScope.launch {
            _state.value = VoicePlayback(key, loading = true)
            val file = runCatching { source() }.getOrNull()
            val p = file?.let { f ->
                runCatching {
                    MediaPlayer().apply {
                        setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        setDataSource(f.path)
                        prepare()
                    }
                }.getOrNull()
            }
            if (p == null) {
                _state.value = VoicePlayback()
                return@launch
            }
            player = p
            p.setOnCompletionListener { stop() }
            p.start()
            _state.value = VoicePlayback(key, playing = true)
            while (isActive && player === p) {
                val duration = p.duration.coerceAtLeast(1)
                _state.value = VoicePlayback(key, playing = true, progress = (p.currentPosition.toFloat() / duration).coerceIn(0f, 1f))
                delay(50)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.let { p ->
            runCatching { p.stop() }
            p.release()
        }
        player = null
        _state.value = VoicePlayback()
    }

    /** Stops [key] if it's the one playing (its card left the screen). */
    fun stopIf(key: String) {
        if (_state.value.key == key) stop()
    }

    override fun onCleared() = stop()
}

fun formatClip(ms: Int): String {
    val seconds = (ms + 500) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The small play pill on a swipe card, over the photo. */
@Composable
fun VoicePill(profile: Profile, modifier: Modifier = Modifier) {
    val duration = profile.voiceDurationMs ?: return
    val voice: VoiceViewModel = hiltViewModel()
    val playback by voice.state.collectAsStateWithLifecycle()
    val mine = playback.key == profile.uid
    DisposableEffect(profile.uid) { onDispose { voice.stopIf(profile.uid) } }
    val haptics = EmberTheme.haptics
    Row(
        modifier
            .pressable(onClick = {
                haptics.tick()
                voice.toggle(profile.uid)
            }, pressedScale = 0.92f, haptic = false)
            .glass(PillShape, GlassDefaults.overPhoto(), LocalHazeState.current)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlayGlyph(loading = mine && playback.loading, playing = mine && playback.playing, progress = if (mine) playback.progress else 0f, size = 26)
        Spacer(Modifier.width(6.dp))
        if (mine && playback.playing) {
            Waveform(Modifier.width(22.dp).height(14.dp), Color.White)
            Spacer(Modifier.width(6.dp))
        }
        Text(formatClip(duration), style = EmberTheme.type.caption, color = Color.White)
    }
}

/** The voice intro on a full profile: a glass row with a big play button and a progress track. */
@Composable
fun VoiceIntroCard(profile: Profile, modifier: Modifier = Modifier) {
    val duration = profile.voiceDurationMs ?: return
    val voice: VoiceViewModel = hiltViewModel()
    val playback by voice.state.collectAsStateWithLifecycle()
    VoiceRow(
        title = "${profile.name}'s voice intro",
        durationMs = duration,
        playback = playback.takeIf { it.key == profile.uid } ?: VoicePlayback(),
        onToggle = { voice.toggle(profile.uid) },
        modifier = modifier,
    )
}

@Composable
fun VoiceRow(title: String, durationMs: Int, playback: VoicePlayback, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    GlassSurface(modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .pressable(onClick = onToggle, pressedScale = 0.9f)
                    .background(colors.accentBrush, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                PlayGlyph(loading = playback.loading, playing = playback.playing, progress = 0f, size = 48, ring = false)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(formatClip(durationMs), style = EmberTheme.type.caption, color = colors.textSecondary)
                }
                val track = colors.textTertiary.copy(alpha = 0.3f)
                val fill = colors.accent
                Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                    drawRoundRect(track, cornerRadius = CornerRadius(size.height / 2))
                    drawRoundRect(fill, size = Size(size.width * playback.progress, size.height), cornerRadius = CornerRadius(size.height / 2))
                }
            }
        }
    }
}

/** Play/pause icon, a spinner while fetching, and (optionally) a progress ring. */
@Composable
private fun PlayGlyph(loading: Boolean, playing: Boolean, progress: Float, size: Int, ring: Boolean = true) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        when {
            loading -> CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size((size * 0.6f).dp))
            else -> Icon(
                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                contentDescription = if (playing) "Pause voice intro" else "Play voice intro",
                tint = Color.White,
                modifier = Modifier.size((size * 0.62f).dp),
            )
        }
        if (ring && playing) {
            Canvas(Modifier.size(size.dp)) {
                val stroke = 2.dp.toPx()
                drawArc(
                    Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(this.size.width - stroke, this.size.height - stroke),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                )
            }
        }
    }
}

/** Four bars bouncing out of phase: "this is audio, and it's playing". */
@Composable
fun Waveform(modifier: Modifier, color: Color) {
    val transition = rememberInfiniteTransition(label = "wave")
    val t by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "waveT")
    Canvas(modifier) {
        val bars = 4
        val gap = size.width / (bars * 2 - 1)
        repeat(bars) { i ->
            val h = size.height * (0.35f + 0.65f * ((sin((t + i * 0.27f) * 2f * Math.PI.toFloat()) + 1f) / 2f))
            drawRoundRect(
                color,
                topLeft = Offset(i * gap * 2, (size.height - h) / 2),
                size = Size(gap, h),
                cornerRadius = CornerRadius(gap / 2),
            )
        }
    }
}
