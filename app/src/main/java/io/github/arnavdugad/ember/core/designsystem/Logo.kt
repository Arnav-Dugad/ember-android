package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private const val FLAME_OUTER =
    "M54,26C58.5,36 71,42.5 71,58.5C71,68.8 63.4,78 54,78C44.6,78 37,68.8 37,59" +
        "C37,51.6 41,46.2 45.6,42.8C45.8,49 48.6,53 52.2,53.4C50.2,45 51.4,33.6 54,26Z"
private const val FLAME_INNER =
    "M54.6,51C57.8,56 62.5,59.4 62.5,65.4C62.5,70.4 58.6,74 54,74C49.4,74 45.6,70.6 45.6,65.8" +
        "C45.6,62 48,59.2 50.6,58C50.8,61 52.2,63 54.2,63.2C53.2,59 53.4,54.6 54.6,51Z"

/** The Ember flame, drawn from the same path as the launcher icon. */
@Composable
fun EmberFlame(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val colors = EmberTheme.colors
    val outer = remember { PathParser().parsePathString(FLAME_OUTER).toPath() }
    val inner = remember { PathParser().parsePathString(FLAME_INNER).toPath() }
    Canvas(modifier.size(size)) {
        // The path lives in a 108-unit icon grid; the flame spans x 37..71, y 26..78.
        val s = this.size.minDimension / 54f
        withTransform({
            scale(s, s, pivot = Offset.Zero)
            translate(-27f, -26f)
        }) {
            drawPath(outer, Brush.verticalGradient(listOf(colors.accentWarm, colors.gradient[1], colors.accentAlt), startY = 26f, endY = 78f))
            drawPath(inner, Color(0xFFFFE2C2))
        }
    }
}

@Composable
fun EmberWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        EmberFlame(size = 30.dp)
        Spacer(Modifier.width(6.dp))
        GradientText("ember", style = EmberTheme.type.title.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.W800))
    }
}
