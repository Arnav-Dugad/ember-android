package io.github.arnavdugad.ember.core.designsystem

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

/** The backdrop glass surfaces blur. Provided by [GlassScene]; null outside one. */
val LocalHazeState = staticCompositionLocalOf<HazeState?> { null }

/** Real-time blur needs RenderEffect (Android 12). Older devices get a denser frosted tint. */
val SupportsBlur: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Immutable
data class GlassStyle(
    val tint: Color,
    val fallback: Color,
    val blur: Dp,
    val rimLight: Color,
    val rimShade: Color,
    val innerGlow: Color,
    val shadow: Color,
)

object GlassDefaults {
    /** Cards, chips and panels. */
    @Composable
    @ReadOnlyComposable
    fun surface(): GlassStyle = style(boost = 0f, blur = 24.dp)

    /** Sheets and the navigation bar: stronger blur so text on top stays legible. */
    @Composable
    @ReadOnlyComposable
    fun chrome(): GlassStyle = style(boost = 0.04f, blur = 32.dp)

    /** Over photos: lighter tint, the photo should still read through. */
    @Composable
    @ReadOnlyComposable
    fun overPhoto(): GlassStyle {
        val base = style(boost = 0f, blur = 18.dp)
        return base.copy(tint = Color.White.copy(alpha = 0.14f), fallback = Color.Black.copy(alpha = 0.42f), rimLight = Color.White.copy(alpha = 0.32f))
    }

    @Composable
    @ReadOnlyComposable
    private fun style(boost: Float, blur: Dp): GlassStyle {
        val c = EmberTheme.colors
        val tint = c.glassTint.copy(alpha = (c.glassTint.alpha + boost).coerceAtMost(1f))
        return GlassStyle(
            tint = tint,
            fallback = tint.compositeOver(c.glassFallback),
            blur = blur,
            rimLight = c.glassRimLight,
            rimShade = c.glassRimShade,
            innerGlow = Color.White.copy(alpha = if (c.isDark) 0.06f else 0.25f),
            shadow = c.glassShadow,
        )
    }
}

/**
 * Makes everything drawn by [backdrop] the source that glass inside [content] blurs. Glass must
 * not be a descendant of its own source, hence the two slots.
 */
@Composable
fun GlassScene(
    modifier: Modifier = Modifier,
    backdrop: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit,
) {
    val haze = rememberHazeState()
    Box(modifier) {
        Box(Modifier.matchParentSize().hazeSource(haze), content = backdrop)
        CompositionLocalProvider(LocalHazeState provides haze) {
            content()
        }
    }
}

/**
 * Frosted glass: backdrop blur (or a dense tint below Android 12), a 1dp rim that is bright at
 * the top-left and fades toward the bottom-right, and a soft inner glow along the top edge.
 */
fun Modifier.glass(
    shape: Shape,
    style: GlassStyle,
    haze: HazeState?,
    elevated: Boolean = false,
): Modifier {
    val shadowed = if (elevated) {
        dropShadow(shape, Shadow(radius = 24.dp, color = style.shadow, offset = DpOffset(0.dp, 10.dp)))
    } else {
        this
    }
    val material = if (haze != null && SupportsBlur) {
        Modifier.hazeBlur(
            input = HazeInput.Sources(haze),
            style = HazeBlurStyle {
                blurRadius(style.blur)
                noiseFactor(0.04f)
                colorEffects(listOf(HazeColorEffect.tint(style.tint)))
                fallbackColorEffect(HazeColorEffect.tint(style.fallback))
            },
        )
    } else {
        Modifier.background(style.fallback)
    }
    return shadowed
        .clip(shape)
        .then(material)
        .glassRim(shape, style)
}

private fun Modifier.glassRim(shape: Shape, style: GlassStyle): Modifier = drawWithCache {
    val path = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val rim = Brush.linearGradient(
        0f to style.rimLight,
        0.5f to style.rimLight.copy(alpha = style.rimLight.alpha * 0.3f),
        1f to style.rimShade,
        start = Offset.Zero,
        end = Offset(size.width, size.height),
    )
    val glow = Brush.verticalGradient(
        0f to style.innerGlow,
        0.35f to Color.Transparent,
        endY = size.height.coerceAtMost(120.dp.toPx()),
    )
    // The path is clipped, so a 2dp stroke shows exactly 1dp inside the edge.
    val stroke = Stroke(width = 2.dp.toPx())
    onDrawWithContent {
        drawRect(glow)
        drawContent()
        drawPath(path, rim, style = stroke)
    }
}

/** A glass panel that picks up the nearest [GlassScene]. */
@Composable
fun GlassSurface(
    modifier: Modifier = Modifier,
    shape: Shape = EmberShapes.panel,
    style: GlassStyle = GlassDefaults.surface(),
    elevated: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glass(shape, style, LocalHazeState.current, elevated), content = content)
}
