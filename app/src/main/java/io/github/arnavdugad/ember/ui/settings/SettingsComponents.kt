package io.github.arnavdugad.ember.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.arnavdugad.ember.core.designsystem.EmberShapes
import io.github.arnavdugad.ember.core.designsystem.EmberTheme
import io.github.arnavdugad.ember.core.designsystem.GlassSurface
import io.github.arnavdugad.ember.core.designsystem.SectionHeader
import io.github.arnavdugad.ember.core.designsystem.pressable

/** A titled group of rows. */
@Composable
internal fun SettingsGroup(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionHeader(title, Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp))
        content()
    }
}

/** The gradient icon square every settings row leads with (the Profile tab's style). */
@Composable
internal fun SettingsIconTile(icon: ImageVector, danger: Boolean = false) {
    val colors = EmberTheme.colors
    val fill = if (danger) SolidColor(colors.danger) else colors.accentBrush
    Box(Modifier.size(40.dp).background(fill, EmberShapes.small), contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
    }
}

/**
 * Icon tile, title, optional subtitle and a trailing slot (a chevron by default). Tappable when
 * [onClick] is set.
 */
@Composable
internal fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    danger: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = EmberTheme.colors
    val modifier = if (onClick != null) Modifier.pressable(onClick = onClick, pressedScale = 0.98f) else Modifier
    GlassSurface(Modifier.fillMaxWidth().then(modifier), shape = EmberShapes.panel) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIconTile(icon, danger)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = if (danger) colors.danger else colors.textPrimary)
                if (subtitle != null) Text(subtitle, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            if (trailing != null || onClick != null) {
                Spacer(Modifier.width(10.dp))
                if (trailing != null) trailing() else Chevron()
            }
        }
    }
}

/** A row with a switch; the whole row toggles it. */
@Composable
internal fun SettingsToggleRow(
    icon: ImageVector,
    title: String,
    subtitle: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    val colors = EmberTheme.colors
    GlassSurface(
        Modifier.fillMaxWidth().pressable(onClick = { onCheckedChange(!checked) }, enabled = enabled, pressedScale = 0.98f, role = Role.Switch),
        shape = EmberShapes.panel,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsIconTile(icon)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = EmberTheme.type.titleSmall, color = colors.textPrimary)
                if (subtitle != null) Text(subtitle, style = EmberTheme.type.caption, color = colors.textSecondary)
            }
            Spacer(Modifier.width(10.dp))
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = enabled,
                colors = SwitchDefaults.colors(checkedTrackColor = colors.accent, checkedThumbColor = Color.White),
            )
        }
    }
}

@Composable
internal fun Chevron() {
    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = EmberTheme.colors.textTertiary)
}

/** A short status word at the end of a row, e.g. "Linked". */
@Composable
internal fun TrailingLabel(text: String, color: Color = EmberTheme.colors.textSecondary) {
    Text(text, style = EmberTheme.type.label, color = color)
}

@Composable
internal fun TrailingSpinner() {
    CircularProgressIndicator(color = EmberTheme.colors.accent, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
}
