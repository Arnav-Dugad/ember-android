package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * A rounded rectangle with continuous-curvature corners, the "squircle" Apple uses everywhere.
 *
 * A circular corner jumps from zero curvature on the straight edge to 1/r on the arc, which the
 * eye reads as a kink. Here each corner starts earlier along the edge (by `1 + smoothing`) and the
 * Bezier handles sit closer to the corner, so curvature ramps up instead of stepping.
 */
@Immutable
class SquircleShape(
    private val radius: Dp,
    private val smoothing: Float = 0.6f,
) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { radius.toPx() }
        val maxExtent = minOf(size.width, size.height) / 2f
        val extent = (r * (1f + smoothing * 0.6f)).coerceAtMost(maxExtent)
        if (extent <= 0f) return Outline.Rectangle(size.toRect())
        // `handle` is the control point's distance from the corner. A circular quarter arc puts it
        // at (1 - 0.5523) of the extent; pulling it closer to the corner keeps the curve flat for
        // longer and then turns tighter, which is what makes the curvature continuous.
        val handle = extent * (1f - CIRCLE_HANDLE) * (1f - smoothing * 0.5f)
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(extent, 0f)
            lineTo(w - extent, 0f)
            cubicTo(w - handle, 0f, w, handle, w, extent)
            lineTo(w, h - extent)
            cubicTo(w, h - handle, w - handle, h, w - extent, h)
            lineTo(extent, h)
            cubicTo(handle, h, 0f, h - handle, 0f, h - extent)
            lineTo(0f, extent)
            cubicTo(0f, handle, handle, 0f, extent, 0f)
            close()
        }
        return Outline.Generic(path)
    }

    override fun equals(other: Any?): Boolean =
        other is SquircleShape && other.radius == radius && other.smoothing == smoothing

    override fun hashCode(): Int = 31 * radius.hashCode() + smoothing.hashCode()

    private companion object {
        const val CIRCLE_HANDLE = 0.5523f
    }
}

private fun Size.toRect() = androidx.compose.ui.geometry.Rect(0f, 0f, width, height)

/** Corner radii, all continuous. */
object EmberShapes {
    val card = SquircleShape(32.dp)
    val sheet = SquircleShape(32.dp)
    val panel = SquircleShape(24.dp)
    val tile = SquircleShape(20.dp)
    val field = SquircleShape(18.dp)
    val bubble = SquircleShape(22.dp)
    val small = SquircleShape(12.dp)
}
