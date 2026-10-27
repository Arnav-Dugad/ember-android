package io.github.arnavdugad.ember.ui.safety

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.HeartBroken
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberButton
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTextField
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassBottomSheet
import io.github.arnavdugad.ember.core.designsystem.GlassButton
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.pressable
import io.github.arnavdugad.ember.data.model.ReportReason

enum class SafetyAction { Report, Block, Unmatch }

/** The "..." menu on profiles and chats. Each action confirms before anything happens. */
@Composable
fun SafetySheet(
    visible: Boolean,
    name: String,
    matched: Boolean,
    busy: Boolean,
    onDismiss: () -> Unit,
    onReport: (ReportReason, String, Boolean) -> Unit,
    onBlock: () -> Unit,
    onUnmatch: () -> Unit,
) {
    var action by remember(visible) { mutableStateOf<SafetyAction?>(null) }
    GlassBottomSheet(visible = visible, onDismiss = onDismiss) {
        val colors = EmberTheme.colors
        when (action) {
            null -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = colors.accent)
                    Spacer(Modifier.width(10.dp))
                    Text("Safety", style = EmberTheme.type.headline, color = colors.textPrimary)
                }
                Text("Nothing here notifies $name.", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
                Spacer(Modifier.height(16.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (matched) MenuRow(Icons.Rounded.HeartBroken, "Unmatch", "Remove the match and your conversation.") { action = SafetyAction.Unmatch }
                    MenuRow(Icons.Rounded.Block, "Block $name", "You won't see each other anywhere on Ember.") { action = SafetyAction.Block }
                    MenuRow(Icons.Rounded.Flag, "Report $name", "Tell us what happened. Reports are confidential.", danger = true) { action = SafetyAction.Report }
                }
            }
            SafetyAction.Report -> ReportForm(name, busy, onSubmit = onReport, onCancel = { action = null })
            SafetyAction.Block -> Confirm(
                title = "Block $name?",
                body = "They won't be able to see your profile or message you, and any match is removed. They aren't told.",
                confirm = "Block",
                busy = busy,
                onConfirm = onBlock,
                onCancel = { action = null },
            )
            SafetyAction.Unmatch -> Confirm(
                title = "Unmatch $name?",
                body = "Your conversation and any private album access between you are deleted for both of you.",
                confirm = "Unmatch",
                busy = busy,
                onConfirm = onUnmatch,
                onCancel = { action = null },
            )
        }
    }
}

@Composable
private fun MenuRow(icon: ImageVector, title: String, subtitle: String, danger: Boolean = false, onClick: () -> Unit) {
    val colors = EmberTheme.colors
    GlassSurface(Modifier.fillMaxWidth().pressable(onClick = onClick, pressedScale = 0.98f), shape = EmberShapes.tile) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = if (danger) colors.danger else colors.textPrimary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = EmberTheme.type.titleSmall, color = if (danger) colors.danger else colors.textPrimary)
                Text(subtitle, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
        }
    }
}

@Composable
private fun Confirm(title: String, body: String, confirm: String, busy: Boolean, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val colors = EmberTheme.colors
    Text(title, style = EmberTheme.type.headline, color = colors.textPrimary)
    Spacer(Modifier.height(8.dp))
    Text(body, style = EmberTheme.type.body, color = colors.textSecondary)
    Spacer(Modifier.height(22.dp))
    EmberButton(confirm, onClick = onConfirm, loading = busy, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(10.dp))
    GlassButton("Cancel", onClick = onCancel, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ReportForm(name: String, busy: Boolean, onSubmit: (ReportReason, String, Boolean) -> Unit, onCancel: () -> Unit) {
    val colors = EmberTheme.colors
    var reason by remember { mutableStateOf<ReportReason?>(null) }
    var details by remember { mutableStateOf("") }
    var alsoBlock by remember { mutableStateOf(true) }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text("Report $name", style = EmberTheme.type.headline, color = colors.textPrimary)
        Text("What's going on?", style = EmberTheme.type.bodySmall, color = colors.textSecondary)
        Spacer(Modifier.height(14.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReportReason.entries.forEach { r ->
                val selected = reason == r
                val border by animateColorAsState(if (selected) colors.accent else Color.Transparent, label = "reason")
                GlassSurface(
                    Modifier.fillMaxWidth().border(1.5.dp, border, EmberShapes.tile).pressable(onClick = { reason = r }, pressedScale = 0.98f),
                    shape = EmberShapes.tile,
                ) {
                    Text(r.label, style = EmberTheme.type.body, color = colors.textPrimary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        EmberTextField(details, { details = it }, placeholder = "Anything else we should know? (optional)", singleLine = false, minLines = 3, maxLength = 1000, imeAction = ImeAction.Default)
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also block $name", style = EmberTheme.type.titleSmall, color = colors.textPrimary, modifier = Modifier.weight(1f))
            Switch(alsoBlock, { alsoBlock = it }, colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White))
        }
        if (reason == ReportReason.Underage) {
            Text(
                "Thank you. Suspected minors are reviewed first and removed immediately if confirmed.",
                style = EmberTheme.type.caption,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        EmberButton("Send report", onClick = { reason?.let { onSubmit(it, details, alsoBlock) } }, enabled = reason != null, loading = busy, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        GlassButton("Back", onClick = onCancel, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.size(4.dp))
    }
}
