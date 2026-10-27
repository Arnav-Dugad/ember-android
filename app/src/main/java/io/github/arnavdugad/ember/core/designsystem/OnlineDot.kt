package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The online green over photos, where the Day palette's darker green would disappear. */
val OnlineGreenOverPhoto = Color(0xFF3DDC97)

/**
 * "Online now": a soft green dot with a halo that breathes slowly (still when the system says to
 * remove animations). [ring] draws a thin outline in that color, so a dot sitting on an avatar's
 * edge reads cleanly against the photo.
 */
@Composable
fun OnlineDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = EmberTheme.colors.like,
    ring: Color? = null,
    contentDescription: String? = "Online now",
) {
    val still = rememberReduceMotion()
    val transition = rememberInfiniteTransition(label = "online")
    val breath by transition.animateFloat(0f, 1f, infiniteRepeatable(tween(1_600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breath")
    val pulse = if (still) 0.5f else breath
    Canvas(
        modifier
            .size(size * 1.8f)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier),
    ) {
        val r = size.toPx() / 2f
        drawCircle(
            Brush.radialGradient(listOf(color.copy(alpha = 0.18f + 0.22f * pulse), Color.Transparent), center = center, radius = r * (1.5f + 0.3f * pulse)),
            radius = r * (1.5f + 0.3f * pulse),
        )
        if (ring != null) drawCircle(ring, radius = r + 1.5.dp.toPx())
        drawCircle(color, radius = r)
    }
}
