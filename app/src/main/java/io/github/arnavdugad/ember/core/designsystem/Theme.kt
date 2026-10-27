package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

val LocalEmberColors = staticCompositionLocalOf { NightColors }
val LocalEmberTypography = staticCompositionLocalOf { DefaultTypography }
val LocalHaptics = staticCompositionLocalOf<Haptics> { error("Haptics not provided") }

object EmberTheme {
    val colors: EmberColors
        @Composable @ReadOnlyComposable get() = LocalEmberColors.current
    val type: EmberTypography
        @Composable @ReadOnlyComposable get() = LocalEmberTypography.current
    val haptics: Haptics
        @Composable @ReadOnlyComposable get() = LocalHaptics.current
}

/**
 * Ember's theme. Palette changes (Night to After Dark, say) cross-fade every semantic color over
 * 600 ms rather than snapping, which is what makes the After Dark toggle feel like dimming the
 * lights instead of switching apps.
 */
@Composable
fun EmberTheme(
    palette: Palette,
    content: @Composable () -> Unit,
) {
    val target = when (palette) {
        Palette.Night -> NightColors
        Palette.AfterDark -> AfterDarkColors
        Palette.Day -> DayColors
    }
    val colors = target.animated()
    val view = LocalView.current
    val haptics = remember(view) { Haptics(view) }
    CompositionLocalProvider(
        LocalEmberColors provides colors,
        LocalEmberTypography provides DefaultTypography,
        LocalHaptics provides haptics,
    ) {
        MaterialTheme(
            colorScheme = colors.toMaterial(),
            typography = DefaultTypography.toMaterial(),
            content = content,
        )
    }
}

@Composable
private fun EmberColors.animated(): EmberColors {
    val spec = tween<androidx.compose.ui.graphics.Color>(durationMillis = 600)
    val background by animateColorAsState(background, spec, label = "bg")
    val backgroundRaised by animateColorAsState(backgroundRaised, spec, label = "bgRaised")
    val surface by animateColorAsState(surface, spec, label = "surface")
    val surfaceHigh by animateColorAsState(surfaceHigh, spec, label = "surfaceHigh")
    val textPrimary by animateColorAsState(textPrimary, spec, label = "text")
    val textSecondary by animateColorAsState(textSecondary, spec, label = "text2")
    val accent by animateColorAsState(accent, spec, label = "accent")
    val accentAlt by animateColorAsState(accentAlt, spec, label = "accentAlt")
    val glow by animateColorAsState(glow, spec, label = "glow")
    val g0 by animateColorAsState(gradient[0], spec, label = "g0")
    val g1 by animateColorAsState(gradient[1], spec, label = "g1")
    val g2 by animateColorAsState(gradient[2], spec, label = "g2")
    return copy(
        background = background,
        backgroundRaised = backgroundRaised,
        surface = surface,
        surfaceHigh = surfaceHigh,
        textPrimary = textPrimary,
        textSecondary = textSecondary,
        accent = accent,
        accentAlt = accentAlt,
        glow = glow,
        gradient = listOf(g0, g1, g2),
    )
}

private fun EmberColors.toMaterial(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = onAccent,
        primaryContainer = accent.copy(alpha = 0.2f),
        onPrimaryContainer = textPrimary,
        secondary = accentAlt,
        onSecondary = onAccent,
        tertiary = accentWarm,
        background = background,
        onBackground = textPrimary,
        surface = surface,
        onSurface = textPrimary,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = textSecondary,
        surfaceContainerLowest = background,
        surfaceContainerLow = backgroundRaised,
        surfaceContainer = surface,
        surfaceContainerHigh = surfaceHigh,
        surfaceContainerHighest = surfaceHigh,
        outline = divider,
        outlineVariant = divider,
        error = danger,
        scrim = scrim,
    )
}

private fun EmberTypography.toMaterial() = Typography(
    displayLarge = display,
    displayMedium = display,
    displaySmall = headline,
    headlineLarge = headline,
    headlineMedium = headline,
    headlineSmall = title,
    titleLarge = title,
    titleMedium = titleSmall,
    titleSmall = label,
    bodyLarge = body,
    bodyMedium = bodySmall,
    bodySmall = caption,
    labelLarge = label,
    labelMedium = caption,
    labelSmall = caption,
)
