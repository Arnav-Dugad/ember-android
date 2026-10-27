package io.github.arnavdugad.ember.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.arnavdugad.ember.core.audio.VoiceRecorder
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.ui.profile.VoicePlayback
import io.github.arnavdugad.ember.ui.profile.VoiceRow
import io.github.arnavdugad.ember.ui.profile.VoiceViewModel
import io.github.arnavdugad.ember.ui.profile.formatClip

private enum class VoicePhase { Empty, Recording, Uploading, Ready }

/**
 * "My voice intro": tap to record up to 30 seconds, tap again to stop. The microphone permission
 * is asked for the first time you tap record, never before.
 */
@Composable
fun VoiceIntroSection(state: EditorState, vm: ProfileEditorViewModel) {
    val colors = EmberTheme.colors
    val context = LocalContext.current
    val haptics = EmberTheme.haptics
    val voice: VoiceViewModel = hiltViewModel()
    val playback by voice.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startRecording()
    }
    fun record() {
        voice.stop()
        haptics.confirm()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            vm.startRecording()
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val duration = state.draft.voiceDurationMs
    val phase = when {
        state.recordingMs != null -> VoicePhase.Recording
        state.voiceUploading -> VoicePhase.Uploading
        duration != null -> VoicePhase.Ready
        else -> VoicePhase.Empty
    }
    GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.panel) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text("My voice intro", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Text(
                    "Up to 30 seconds. Say hi, tell a tiny story, laugh. It plays on your card and profile.",
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                )
            }
            AnimatedContent(phase, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "voicePhase") { current ->
                when (current) {
                    VoicePhase.Empty -> RecordRow(label = "Tap to record", detail = "0:30 max", onClick = ::record)
                    VoicePhase.Recording -> RecordingRow(state.recordingMs ?: 0L, onStop = {
                        haptics.confirm()
                        vm.stopRecording()
                    })
                    VoicePhase.Uploading -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(12.dp))
                        Text("Saving your clip…", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                    }
                    VoicePhase.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val fresh = state.recordedVoice
                        val key = fresh?.path ?: state.uid
                        VoiceRow(
                            title = "Your intro",
                            durationMs = duration ?: 0,
                            playback = playback.takeIf { it.key != null && it.key == key } ?: VoicePlayback(),
                            onToggle = {
                                when {
                                    fresh != null -> voice.toggleFile(fresh)
                                    state.uid != null -> voice.toggle(state.uid)
                                }
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                            TextAction("Re-record", colors.accent, ::record)
                            TextAction("Remove", colors.textSecondary) {
                                voice.stop()
                                vm.removeVoice()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TextAction(text: String, color: Color, onClick: () -> Unit) {
    Text(text, style = EmberTheme.type.label, color = color, modifier = Modifier.pressable(onClick = onClick).padding(vertical = 4.dp))
}

@Composable
private fun RecordRow(label: String, detail: String, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).pressable(onClick = onClick, pressedScale = 0.9f, haptic = false).background(colors.accentBrush, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = "Record a voice intro", tint = Color.White, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(label, style = EmberTheme.type.label, color = colors.textPrimary)
            Text(detail, style = EmberTheme.type.caption, color = colors.textTertiary)
        }
    }
}

/** A breathing stop button wrapped in a ring that fills over the 30 seconds. */
@Composable
private fun RecordingRow(elapsedMs: Long, onStop: () -> Unit) {
    val colors = EmberTheme.colors
    val transition = rememberInfiniteTransition(label = "rec")
    val pulse by transition.animateFloat(1f, 1.12f, infiniteRepeatable(tween(650), RepeatMode.Reverse), label = "recPulse")
    val progress = (elapsedMs / VoiceRecorder.MAX_MS.toFloat()).coerceIn(0f, 1f)
    val ring = colors.nope
    val track = colors.textTertiary.copy(alpha = 0.25f)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp).pressable(onClick = onStop, pressedScale = 0.9f, haptic = false), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(56.dp)) {
                val stroke = 3.dp.toPx()
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2, stroke / 2)
                drawArc(track, 0f, 360f, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
                drawArc(ring, -90f, 360f * progress, false, topLeft = topLeft, size = arcSize, style = Stroke(stroke))
            }
            Box(
                Modifier
                    .size(40.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .background(ring.copy(alpha = 0.22f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(16.dp).background(ring, RoundedCornerShape(4.dp)))
            }
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Recording… tap to stop", style = EmberTheme.type.label, color = colors.textPrimary)
            Text(
                "${formatClip(elapsedMs.toInt())} / ${formatClip(VoiceRecorder.MAX_MS)}",
                style = EmberTheme.type.caption,
                color = ring,
            )
        }
    }
}
