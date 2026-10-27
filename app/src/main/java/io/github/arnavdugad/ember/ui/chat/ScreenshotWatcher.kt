package io.github.arnavdugad.ember.ui.chat

import android.app.Activity
import android.os.Build
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Calls [onScreenshot] when the user screenshots this screen (Android 14+, through
 * [Activity.ScreenCaptureCallback]; the system only reports captures of a visible activity, and
 * none while FLAG_SECURE blocks them). Does nothing on older versions or when [enabled] is false.
 */
@Composable
internal fun ScreenshotWatcher(enabled: Boolean, onScreenshot: () -> Unit) {
    if (!enabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
    val activity = LocalActivity.current ?: return
    val latest by rememberUpdatedState(onScreenshot)
    DisposableEffect(activity) {
        val callback = Activity.ScreenCaptureCallback { latest() }
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)
        onDispose { activity.unregisterScreenCaptureCallback(callback) }
    }
}
