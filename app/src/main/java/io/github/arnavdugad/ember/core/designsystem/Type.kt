package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.arnavdugad.ember.R

private fun manrope(weight: Int) = Font(
    resId = R.font.manrope,
    weight = FontWeight(weight),
    variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
)

/** Manrope variable font, bundled so the first frame never waits on a download. */
val Manrope = FontFamily(
    manrope(400),
    manrope(500),
    manrope(600),
    manrope(700),
    manrope(800),
)

@Immutable
data class EmberTypography(
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val overline: TextStyle,
)

internal val DefaultTypography = EmberTypography(
    display = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W800, fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = (-0.03).em),
    headline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W800, fontSize = 28.sp, lineHeight = 34.sp, letterSpacing = (-0.02).em),
    title = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W700, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.01).em),
    titleSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 22.sp),
    body = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 16.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W500, fontSize = 14.sp, lineHeight = 20.sp),
    label = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W700, fontSize = 15.sp, lineHeight = 20.sp),
    caption = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 16.sp),
    overline = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.W800, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.12.em),
)
