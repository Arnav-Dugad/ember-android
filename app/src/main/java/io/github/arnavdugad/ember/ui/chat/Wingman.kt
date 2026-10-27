package io.github.arnavdugad.ember.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassDefaults
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.LocalHazeState
import io.github.arnavdugad.ember.core.designsystem.PillShape
import io.github.arnavdugad.ember.core.designsystem.glass
import io.github.arnavdugad.ember.core.designsystem.pressable

/** A small "Help me reply" pill above the composer, shown when the user has an AI key. */
@Composable
internal fun WingmanChip(onClick: () -> Unit) {
    val colors = EmberTheme.colors
    Box(Modifier.fillMaxWidth().padding(start = 16.dp, bottom = 6.dp)) {
        Row(
            Modifier
                .pressable(onClick = onClick, pressedScale = 0.94f, role = Role.Button)
                .glass(PillShape, GlassDefaults.chrome(), LocalHazeState.current)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = colors.accent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text("Help me reply", style = EmberTheme.type.label, color = colors.textPrimary)
        }
    }
}

/** Three reply ideas from the user's own AI. Tapping one puts it in the composer to edit; nothing is sent. */
@Composable
internal fun WingmanSheet(state: WingmanState, name: String, onPick: (String) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val colors = EmberTheme.colors
    GlassBottomSheet(visible = state != WingmanState.Closed, onDismiss = onDismiss) {
        Text("Reply ideas", style = EmberTheme.type.title, color = colors.textPrimary)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap one to edit it before sending. Your AI reads this chat to suggest them; ${name.ifBlank { "they" }} won't know.",
            style = EmberTheme.type.bodySmall,
            color = colors.textSecondary,
        )
        Spacer(Modifier.height(16.dp))
        AnimatedContent(targetState = state, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "wingman") { current ->
            when (current) {
                WingmanState.Closed, WingmanState.Loading -> Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = colors.accent, strokeWidth = 2.5.dp, modifier = Modifier.size(28.dp))
                }
                is WingmanState.Failed -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(current.message, style = EmberTheme.type.bodySmall, color = colors.danger)
                    Spacer(Modifier.height(12.dp))
                    GlassButton("Try again", onClick = onRetry)
                }
                is WingmanState.Ready -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    current.ideas.forEach { idea ->
                        GlassSurface(
                            Modifier.fillMaxWidth().pressable(onClick = { onPick(idea) }, pressedScale = 0.97f, role = Role.Button),
                            shape = EmberShapes.panel,
                        ) {
                            Text(idea, style = EmberTheme.type.body, color = colors.textPrimary, modifier = Modifier.padding(16.dp))
                        }
                    }
                    GlassButton("More ideas", onClick = onRetry, icon = Icons.Rounded.AutoAwesome, modifier = Modifier.fillMaxWidth())
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
