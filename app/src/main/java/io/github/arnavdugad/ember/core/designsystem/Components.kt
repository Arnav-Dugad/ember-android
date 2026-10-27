package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Pill shape. A full semicircle end is already curvature-continuous along the straight edge. */
val PillShape = CircleShape

/**
 * The micro-interaction every tappable thing in Ember shares: scale down to 96% on press with a
 * bouncy spring back, plus a haptic tick on click.
 */
@Composable
fun Modifier.pressable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    pressedScale: Float = 0.96f,
    haptic: Boolean = true,
    role: Role = Role.Button,
): Modifier {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "press",
    )
    val haptics = EmberTheme.haptics
    return this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .clickable(
            interactionSource = interaction,
            indication = null,
            enabled = enabled,
            role = role,
        ) {
            if (haptic) haptics.press()
            onClick()
        }
}

/** Primary action: ember gradient pill. */
@Composable
fun EmberButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val colors = EmberTheme.colors
    val alpha by animateFloatAsState(if (enabled) 1f else 0.45f, label = "enabled")
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .alpha(alpha)
            .pressable(onClick = onClick, enabled = enabled && !loading)
            .background(colors.accentHorizontal, PillShape)
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent)), PillShape)
            .padding(horizontal = 28.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(targetState = loading, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "loading") { busy ->
            if (busy) {
                CircularProgressIndicator(color = colors.onAccent, strokeWidth = 2.5.dp, modifier = Modifier.size(22.dp))
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = colors.onAccent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                    }
                    Text(text, style = EmberTheme.type.label, color = colors.onAccent)
                }
            }
        }
    }
}

/** Secondary action: glass pill. */
@Composable
fun GlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentColor: Color = EmberTheme.colors.textPrimary,
) {
    Box(
        modifier = modifier
            .heightIn(min = 56.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .pressable(onClick = onClick, enabled = enabled)
            .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, style = EmberTheme.type.label, color = contentColor)
        }
    }
}

/** Circular glass icon button, used in top bars and over photos. */
@Composable
fun GlassIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    tint: Color = EmberTheme.colors.textPrimary,
    style: GlassStyle = GlassDefaults.surface(),
) {
    Box(
        modifier = modifier
            .size(size)
            .pressable(onClick = onClick, pressedScale = 0.9f)
            .glass(CircleShape, style, LocalHazeState.current),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.46f))
    }
}

/** Selectable tag. Unselected is glass, selected fills with the ember gradient. */
@Composable
fun EmberChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emoji: String? = null,
) {
    val colors = EmberTheme.colors
    val textColor by animateColorAsState(if (selected) colors.onAccent else colors.textPrimary, label = "chipText")
    val fill by animateFloatAsState(if (selected) 1f else 0f, label = "chipFill")
    Box(
        modifier = modifier
            .pressable(onClick = onClick, pressedScale = 0.92f, role = Role.Checkbox)
            .glass(PillShape, GlassDefaults.surface(), LocalHazeState.current)
            .background(colors.accentHorizontal, PillShape, alpha = fill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (emoji != null) "$emoji  $text" else text,
            style = EmberTheme.type.bodySmall,
            color = textColor,
        )
    }
}

/** Read-only tag, e.g. on a profile card. */
@Composable
fun GlassTag(
    text: String,
    modifier: Modifier = Modifier,
    style: GlassStyle = GlassDefaults.surface(),
    textColor: Color = EmberTheme.colors.textPrimary,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .glass(PillShape, style, LocalHazeState.current)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = EmberTheme.type.caption, color = textColor)
    }
}

/** Every seeded profile wears this so nobody mistakes it for a real person. */
@Composable
fun DemoBadge(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.White.copy(alpha = 0.92f), PillShape)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text("DEMO", style = EmberTheme.type.overline, color = Color(0xFF5B2A4B))
    }
}

@Composable
fun EmberTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLength: Int = Int.MAX_VALUE,
    keyboardType: KeyboardType = KeyboardType.Text,
    isPassword: Boolean = false,
    imeAction: androidx.compose.ui.text.input.ImeAction = androidx.compose.ui.text.input.ImeAction.Next,
    onImeAction: (() -> Unit)? = null,
    error: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val colors = EmberTheme.colors
    val focus = LocalFocusManager.current
    Column(modifier) {
        if (label != null) {
            Text(label, style = EmberTheme.type.caption, color = colors.textSecondary, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = { if (it.length <= maxLength) onValueChange(it) },
            singleLine = singleLine,
            minLines = minLines,
            textStyle = EmberTheme.type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
            // Without a handler, Next moves to the following field and Done hides the keyboard.
            keyboardActions = KeyboardActions(onAny = {
                when {
                    onImeAction != null -> onImeAction()
                    imeAction == androidx.compose.ui.text.input.ImeAction.Next -> if (!focus.moveFocus(FocusDirection.Next)) focus.clearFocus()
                    else -> focus.clearFocus()
                }
            }),
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glass(EmberShapes.field, GlassDefaults.surface(), LocalHazeState.current)
                        .then(if (error != null) Modifier.border(1.dp, colors.danger, EmberShapes.field) else Modifier)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
                ) {
                    if (leading != null) {
                        leading()
                        Spacer(Modifier.width(10.dp))
                    }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty()) Text(placeholder, style = EmberTheme.type.body, color = colors.textTertiary)
                        inner()
                    }
                }
            },
        )
        if (error != null) {
            Text(error, style = EmberTheme.type.caption, color = colors.danger, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
        } else if (maxLength != Int.MAX_VALUE && !singleLine) {
            Text(
                "${value.length}/$maxLength",
                style = EmberTheme.type.caption,
                color = colors.textTertiary,
                textAlign = TextAlign.End,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 4.dp),
            )
        }
    }
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text.uppercase(), style = EmberTheme.type.overline, color = EmberTheme.colors.textTertiary, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Large screen title with optional subtitle. */
@Composable
fun ScreenTitle(title: String, modifier: Modifier = Modifier, subtitle: String? = null) {
    Column(modifier) {
        Text(title, style = EmberTheme.type.headline, color = EmberTheme.colors.textPrimary)
        if (subtitle != null) {
            Spacer(Modifier.height(6.dp))
            Text(subtitle, style = EmberTheme.type.body, color = EmberTheme.colors.textSecondary)
        }
    }
}

/** Text painted with the accent gradient. */
@Composable
fun GradientText(text: String, style: TextStyle, modifier: Modifier = Modifier, brush: Brush = EmberTheme.colors.accentHorizontal) {
    Text(text = text, style = style.copy(brush = brush), modifier = modifier)
}
