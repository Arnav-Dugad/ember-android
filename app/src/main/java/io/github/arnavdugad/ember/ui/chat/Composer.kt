package io.github.arnavdugad.ember.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Casino
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.arnavdugad.ember.core.designsystem.EmberBurst
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.core.designsystem.rememberReduceMotion
import io.github.arnavdugad.ember.core.media.VoiceRecorder
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.VoiceClip
import kotlinx.coroutines.delay

/** What the composer shows above the text field, besides the field itself. */
internal class ComposerExtras(
    val replyTo: ReplyRef?,
    val replyAuthor: String,
    val onCancelReply: () -> Unit,
    /** The other person said something scam-shaped. */
    val safetyBanner: Boolean,
    val onDismissBanner: () -> Unit,
    val onReport: () -> Unit,
    /** I'm about to share a phone number very early on. */
    val phoneHint: Boolean,
)

@Composable
internal fun Composer(
    text: String,
    onText: (String) -> Unit,
    uploading: Boolean,
    extras: ComposerExtras,
    onSend: () -> Unit,
    onPhoto: () -> Unit,
    onGame: () -> Unit,
    onPlanDate: () -> Unit,
    onVoice: (VoiceClip) -> Unit,
    onNotice: (String) -> Unit,
) {
    val colors = EmberTheme.colors
    val haptics = EmberTheme.haptics
    val context = LocalContext.current
    val recorder = remember { VoiceRecorder(context.applicationContext) }
    var recording by remember { mutableStateOf(false) }
    var elapsedMs by remember { mutableLongStateOf(0L) }
    var level by remember { mutableFloatStateOf(0f) }
    // How far towards cancelling the user has slid, 0 to 1.
    var slide by remember { mutableFloatStateOf(0f) }
    val reduceMotion = rememberReduceMotion()
    var bursts by remember { mutableIntStateOf(0) }
    DisposableEffect(recorder) { onDispose { recorder.cancel() } }
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onNotice(if (granted) "Ready. Hold the mic to record." else "Ember needs the microphone to record voice notes.")
    }

    fun finishRecording() {
        if (!recording) return
        recording = false
        val clip = recorder.stop()
        if (clip != null) {
            haptics.confirm()
            onVoice(clip)
        } else {
            onNotice("Hold the mic to record a voice note.")
        }
    }

    LaunchedEffect(recording) {
        while (recording) {
            elapsedMs = recorder.elapsedMs()
            level = recorder.level()
            if (elapsedMs >= VoiceRecorder.MAX_DURATION_MS) finishRecording()
            delay(50)
        }
    }

    Column(Modifier.navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
        AnimatedVisibility(extras.safetyBanner, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SafetyBanner(onReport = extras.onReport, onDismiss = extras.onDismissBanner)
        }
        // Keeps the last quote on screen while the preview animates away.
        val lastReply = remember { arrayOfNulls<ReplyRef>(1) }
        extras.replyTo?.let { lastReply[0] = it }
        AnimatedVisibility(extras.replyTo != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            lastReply[0]?.let { ReplyPreview(it, extras.replyAuthor, extras.onCancelReply) }
        }
        AnimatedVisibility(extras.phoneHint && !recording, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Row(Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Info, contentDescription = null, tint = colors.textTertiary, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    "Sharing your number this early? There's no rush; you can keep chatting here.",
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                )
            }
        }
        Box(Modifier.fillMaxWidth()) {
            Row(
                Modifier
                    .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current, elevated = true)
                    .padding(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (recording) {
                    RecordingStatus(elapsedMs = elapsedMs, slide = slide, modifier = Modifier.weight(1f))
                } else {
                    ComposerIcon(Icons.Rounded.Casino, "Truth or Dare", onGame)
                    if (uploading) {
                        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        ComposerIcon(Icons.Rounded.Image, "Send a photo", onPhoto)
                    }
                    ComposerIcon(Icons.Rounded.Event, "Plan a date", onPlanDate)
                    BasicTextField(
                        value = text,
                        onValueChange = onText,
                        textStyle = EmberTheme.type.body.copy(color = colors.textPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        maxLines = 5,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp),
                        decorationBox = { inner ->
                            Box {
                                if (text.isEmpty()) Text("Message", style = EmberTheme.type.body, color = colors.textTertiary)
                                inner()
                            }
                        },
                    )
                }
                AnimatedContent(
                    targetState = text.isNotBlank(),
                    transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
                    label = "sendOrMic",
                ) { canSend ->
                    if (canSend) {
                        Box(
                            Modifier
                                .size(42.dp)
                                .pressable(
                                    onClick = {
                                        if (!reduceMotion) bursts++
                                        onSend()
                                    },
                                    pressedScale = 0.85f,
                                )
                                .background(colors.accentBrush, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send", tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else {
                        MicButton(
                            recording = recording,
                            level = level,
                            onPress = {
                                when {
                                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                                        false
                                    }
                                    recorder.start() -> {
                                        haptics.press()
                                        elapsedMs = 0L
                                        level = 0f
                                        slide = 0f
                                        recording = true
                                        true
                                    }
                                    else -> {
                                        onNotice("Couldn't start recording. Is another app using the microphone?")
                                        false
                                    }
                                }
                            },
                            onSlide = { progress ->
                                if (progress >= 0.5f && slide < 0.5f) haptics.tick()
                                slide = progress
                            },
                            onRelease = ::finishRecording,
                            onCancel = {
                                if (recording) {
                                    recording = false
                                    recorder.cancel()
                                    haptics.reject()
                                }
                            },
                        )
                    }
                }
            }
            if (bursts > 0) {
                // A few embers thrown off the send button as the message leaves. Centred on it: the
                // button's centre sits 27 dp in from the bar's bottom-end corner.
                EmberBurst(
                    key = bursts,
                    modifier = Modifier.align(Alignment.BottomEnd).offset(x = 33.dp, y = 33.dp).size(120.dp),
                    origin = Offset(0.5f, 0.5f),
                    count = 22,
                    durationMs = 900,
                )
            }
        }
    }
}

/**
 * Hold to record, release to send, slide left about 110 dp to cancel. [onPress] returns false when recording
 * couldn't start (e.g. the permission prompt is showing instead), which ends the gesture.
 */
@Composable
private fun MicButton(
    recording: Boolean,
    level: Float,
    onPress: () -> Boolean,
    onSlide: (progress: Float) -> Unit,
    onRelease: () -> Unit,
    onCancel: () -> Unit,
) {
    val colors = EmberTheme.colors
    val press by rememberUpdatedState(onPress)
    val slide by rememberUpdatedState(onSlide)
    val release by rememberUpdatedState(onRelease)
    val cancel by rememberUpdatedState(onCancel)
    val isRecording by rememberUpdatedState(recording)
    val scale by animateFloatAsState(if (recording) 1.2f else 1f, spring(dampingRatio = 0.5f, stiffness = 500f), label = "mic")
    val pulse by animateFloatAsState(if (recording) 1.3f + level * 0.9f else 1f, spring(stiffness = 900f), label = "level")
    Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
        if (recording) {
            Box(
                Modifier
                    .size(42.dp)
                    .graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }
                    .background(colors.accent.copy(alpha = 0.25f), CircleShape),
            )
        }
        Box(
            Modifier
                .size(42.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
                .background(colors.accentBrush, CircleShape)
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        if (!press()) return@awaitEachGesture
                        val cancelPx = 110.dp.toPx()
                        var cancelled = false
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) {
                                change.consume()
                                break
                            }
                            change.consume()
                            val progress = (down.position.x - change.position.x) / cancelPx
                            slide(progress.coerceIn(0f, 1f))
                            if (progress >= 1f) {
                                cancelled = true
                                break
                            }
                            // Finished on its own at the one-minute limit.
                            if (!isRecording) break
                        }
                        if (cancelled) cancel() else release()
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Mic, contentDescription = "Hold to record a voice note", tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun RecordingStatus(elapsedMs: Long, slide: Float, modifier: Modifier = Modifier) {
    val colors = EmberTheme.colors
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "recDot",
    )
    Row(modifier.height(52.dp).padding(start = 14.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).graphicsLayer { alpha = blink }.background(colors.danger, CircleShape))
        Spacer(Modifier.width(10.dp))
        Text(formatDuration(elapsedMs), style = EmberTheme.type.label, color = colors.textPrimary)
        Spacer(Modifier.weight(1f))
        Text(
            "‹  Slide to cancel",
            style = EmberTheme.type.caption,
            color = colors.textSecondary,
            modifier = Modifier.graphicsLayer {
                translationX = -slide * 48.dp.toPx()
                alpha = 1f - slide * 0.8f
            },
        )
    }
}

@Composable
private fun ReplyPreview(reply: ReplyRef, author: String, onCancel: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = EmberShapes.tile, style = GlassDefaults.chrome()) {
        Row(Modifier.height(IntrinsicSize.Min).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(3.dp).fillMaxHeight().background(colors.accentBrush, PillShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Replying to $author", style = EmberTheme.type.caption, color = colors.accent)
                Text(quoteText(reply), style = EmberTheme.type.bodySmall, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box(Modifier.size(32.dp).pressable(onClick = onCancel, pressedScale = 0.85f), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = "Cancel reply", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/** Shown when the other person mentions money or moving to another app. Dismissible. */
@Composable
private fun SafetyBanner(onReport: () -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().padding(bottom = 8.dp), shape = EmberShapes.tile, style = GlassDefaults.chrome(), elevated = true) {
        Row(Modifier.padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)) {
            Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Never send money or move off Ember too fast.", style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    "Asking for payments, gift cards or your number early on is a classic scam move.",
                    style = EmberTheme.type.caption,
                    color = colors.textSecondary,
                )
                Text(
                    "Report",
                    style = EmberTheme.type.label,
                    color = colors.accent,
                    modifier = Modifier.padding(top = 8.dp).pressable(onClick = onReport, pressedScale = 0.92f),
                )
            }
            Box(Modifier.size(32.dp).pressable(onClick = onDismiss, pressedScale = 0.85f), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = colors.textSecondary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun ComposerIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).pressable(onClick = onClick, pressedScale = 0.85f), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = label, tint = EmberTheme.colors.textSecondary, modifier = Modifier.size(22.dp))
    }
}
