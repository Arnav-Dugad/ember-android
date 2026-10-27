package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** The three palettes Ember ships. After Dark is a deeper, moodier variant of [Night]. */
enum class Palette { Night, AfterDark, Day }

/**
 * Semantic colors. Screens never use raw hex values; they read these through [EmberTheme.colors]
 * so the three palettes, and the glow tinted by the current photo, stay consistent.
 */
@Immutable
data class EmberColors(
    val palette: Palette,
    val background: Color,
    val backgroundRaised: Color,
    val surface: Color,
    val surfaceHigh: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    val accent: Color,
    val accentAlt: Color,
    val accentWarm: Color,
    val onAccent: Color,
    val gradient: List<Color>,
    val glow: Color,
    val glassTint: Color,
    val glassFallback: Color,
    val glassRimLight: Color,
    val glassRimShade: Color,
    val glassShadow: Color,
    val divider: Color,
    val like: Color,
    val nope: Color,
    val superLike: Color,
    val danger: Color,
    val scrim: Color,
) {
    val isDark: Boolean get() = palette != Palette.Day

    val accentBrush: Brush get() = Brush.linearGradient(gradient)
    val accentHorizontal: Brush get() = Brush.horizontalGradient(gradient)
}

internal val NightColors = EmberColors(
    palette = Palette.Night,
    background = Color(0xFF120A12),
    backgroundRaised = Color(0xFF1A111B),
    surface = Color(0xFF1F1520),
    surfaceHigh = Color(0xFF2A1D2B),
    textPrimary = Color(0xFFF8F0F4),
    textSecondary = Color(0xFFC2B1BC),
    textTertiary = Color(0xFF8C7A86),
    accent = Color(0xFFFF6B3D),
    accentAlt = Color(0xFFFF3D7F),
    accentWarm = Color(0xFFFFB547),
    onAccent = Color.White,
    gradient = listOf(Color(0xFFFF9A4D), Color(0xFFFF5E57), Color(0xFFFF3D7F)),
    glow = Color(0xFFFF5E57),
    glassTint = Color(0x1FFFFFFF),
    glassFallback = Color(0xE6261A27),
    glassRimLight = Color(0x40FFFFFF),
    glassRimShade = Color(0x0FFFFFFF),
    glassShadow = Color(0x66000000),
    divider = Color(0x1AFFFFFF),
    like = Color(0xFF3DDC97),
    nope = Color(0xFFFF4D6D),
    superLike = Color(0xFF4DA3FF),
    danger = Color(0xFFFF4D6D),
    scrim = Color(0x99000000),
)

internal val AfterDarkColors = NightColors.copy(
    palette = Palette.AfterDark,
    background = Color(0xFF07040A),
    backgroundRaised = Color(0xFF0E0812),
    surface = Color(0xFF140B18),
    surfaceHigh = Color(0xFF1E1124),
    textSecondary = Color(0xFFBBA6C4),
    textTertiary = Color(0xFF7F6B88),
    accent = Color(0xFFFF2E63),
    accentAlt = Color(0xFFB5179E),
    accentWarm = Color(0xFFFF7A45),
    gradient = listOf(Color(0xFFFF7A45), Color(0xFFFF2E63), Color(0xFFB5179E)),
    glow = Color(0xFF9D1D7A),
    glassTint = Color(0x17FFFFFF),
    glassFallback = Color(0xEB1A0F1F),
    glassRimLight = Color(0x33FFC2E2),
)

internal val DayColors = EmberColors(
    palette = Palette.Day,
    background = Color(0xFFFFF8F5),
    backgroundRaised = Color(0xFFFFFFFF),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFF7EDEB),
    textPrimary = Color(0xFF1E1418),
    textSecondary = Color(0xFF6E5F66),
    textTertiary = Color(0xFFA3949A),
    accent = Color(0xFFF2542D),
    accentAlt = Color(0xFFE8336F),
    accentWarm = Color(0xFFF59E0B),
    onAccent = Color.White,
    gradient = listOf(Color(0xFFFF8A3D), Color(0xFFF7504F), Color(0xFFE8336F)),
    glow = Color(0xFFFF9A7A),
    glassTint = Color(0x8CFFFFFF),
    glassFallback = Color(0xF2FFFFFF),
    glassRimLight = Color(0xCCFFFFFF),
    glassRimShade = Color(0x14000000),
    glassShadow = Color(0x26401A2A),
    divider = Color(0x14000000),
    like = Color(0xFF16B97A),
    nope = Color(0xFFF0365A),
    superLike = Color(0xFF2F86F6),
    danger = Color(0xFFE11D48),
    scrim = Color(0x66000000),
)
