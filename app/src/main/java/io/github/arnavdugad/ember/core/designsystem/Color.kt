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

/**
 * Daylight: warm cream and blush rather than stark white, with a dustier terracotta-to-rose
 * gradient so the accent reads as warm instead of hot pink. Text colors meet WCAG AA (4.5:1) on
 * [background], on surface glass and on [surfaceHigh]; white on the button gradient is 3.6:1 to
 * 5.2:1 (the old one dipped to 2.4:1).
 */
internal val DayColors = EmberColors(
    palette = Palette.Day,
    background = Color(0xFFFBF3EE),
    backgroundRaised = Color(0xFFFFF9F5),
    surface = Color(0xFFFFFBF8),
    surfaceHigh = Color(0xFFF3E5DF),
    textPrimary = Color(0xFF2B1F24),
    textSecondary = Color(0xFF66545B),
    textTertiary = Color(0xFF766369),
    accent = Color(0xFFB84F3A),
    accentAlt = Color(0xFFB5476A),
    accentWarm = Color(0xFFD98A2B),
    onAccent = Color.White,
    gradient = listOf(Color(0xFFD06A45), Color(0xFFC8534F), Color(0xFFB5476A)),
    glow = Color(0xFFF2B49B),
    // Warm milk glass, a little denser than before so text on it never fights the backdrop.
    glassTint = Color(0xA8FFF9F5),
    glassFallback = Color(0xF5FFFAF6),
    glassRimLight = Color(0xE6FFFFFF),
    glassRimShade = Color(0x1A6B3F3A),
    // Soft, warm-brown shadow instead of a grey drop.
    glassShadow = Color(0x1F6B3A35),
    divider = Color(0x175A3A40),
    like = Color(0xFF178A60),
    nope = Color(0xFFD6455F),
    superLike = Color(0xFF2F74D0),
    danger = Color(0xFFBE2F45),
    scrim = Color(0x662A1A1F),
)
