package io.github.arnavdugad.ember.update

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import kotlin.math.roundToInt

@Composable
fun UpdateSheet(
    state: UpdateUiState,
    onUpdate: () -> Unit,
    onRemindLater: () -> Unit,
    onGrantPermission: () -> Unit,
) {
    val update = state.update
    GlassBottomSheet(
        visible = state.sheetVisible && update != null,
        onDismiss = onRemindLater,
        dismissible = update?.mandatory != true,
    ) {
        if (update == null) return@GlassBottomSheet
        val colors = EmberTheme.colors
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(state.download)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (update.mandatory) "Required update" else "A fresh Ember is ready",
                    style = EmberTheme.type.title,
                    color = colors.textPrimary,
                )
                Text(
                    "Version ${update.version} • ${"%.1f".format(update.sizeBytes / 1_048_576f)} MB",
                    style = EmberTheme.type.bodySmall,
                    color = colors.textSecondary,
                )
            }
        }
        Spacer(Modifier.height(18.dp))
        if (update.notes.isNotBlank()) {
            GlassSurface(Modifier.fillMaxWidth(), shape = EmberShapes.tile) {
                Text(
                    update.notes,
                    style = EmberTheme.type.bodySmall,
                    color = colors.textSecondary,
                    modifier = Modifier
                        .heightIn(max = 220.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        if (update.mandatory) {
            Text(
                "This update fixes something important, so it's needed to keep using Ember.",
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
        AnimatedContent(targetState = state.needsInstallPermission, label = "permission") { needsPermission ->
            if (needsPermission) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "One more step: allow Ember to install updates. Turn on \"Allow from this source\", then come back.",
                        style = EmberTheme.type.bodySmall,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(14.dp))
                    EmberButton("Open settings", onClick = onGrantPermission, modifier = Modifier.fillMaxWidth())
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val download = state.download
                    if (download is DownloadState.Failed) {
                        Text(download.message, style = EmberTheme.type.caption, color = colors.danger)
                    }
                    val busy = download is DownloadState.Downloading || download is DownloadState.Verifying
                    EmberButton(
                        text = when (download) {
                            is DownloadState.Downloading -> "Downloading…"
                            DownloadState.Verifying -> "Verifying…"
                            is DownloadState.Ready -> "Install"
                            is DownloadState.Failed -> "Try again"
                            DownloadState.Idle -> "Update now"
                        },
                        onClick = onUpdate,
                        enabled = !busy,
                        icon = Icons.Rounded.SystemUpdate,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (!update.mandatory) {
                        GlassButton("Remind me later", onClick = onRemindLater, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** A gradient progress ring that springs toward the download progress. */
@Composable
private fun ProgressRing(download: DownloadState) {
    val colors = EmberTheme.colors
    val target = when (download) {
        is DownloadState.Downloading -> download.progress
        DownloadState.Verifying, is DownloadState.Ready -> 1f
        else -> 0f
    }
    val progress by animateFloatAsState(target, spring(stiffness = 120f), label = "ring")
    Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(56.dp)) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arcSize = Size(size.width - stroke, size.height - stroke)
            drawArc(colors.textTertiary.copy(alpha = 0.25f), 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
            drawArc(
                brush = Brush.sweepGradient(colors.gradient + colors.gradient.first()),
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        if (download is DownloadState.Downloading) {
            Text("${(download.progress * 100).roundToInt()}%", style = EmberTheme.type.caption, color = colors.textPrimary)
        } else {
            Icon(Icons.Rounded.SystemUpdate, contentDescription = null, tint = colors.accent, modifier = Modifier.size(22.dp))
        }
    }
}
