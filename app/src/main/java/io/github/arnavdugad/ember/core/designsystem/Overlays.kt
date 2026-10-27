package io.github.arnavdugad.ember.core.designsystem

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * How many full-screen overlays (sheets, the match celebration) are showing. The main scaffold
 * hides its floating nav bar while this is above zero, so the bar never floats over a sheet.
 */
val LocalOverlayCount = staticCompositionLocalOf<MutableIntState?> { null }

/** Registers an overlay for as long as [visible] is true. */
@Composable
fun OverlayEffect(visible: Boolean) {
    val count = LocalOverlayCount.current ?: return
    DisposableEffect(visible, count) {
        if (visible) count.intValue++
        onDispose { if (visible) count.intValue-- }
    }
}
