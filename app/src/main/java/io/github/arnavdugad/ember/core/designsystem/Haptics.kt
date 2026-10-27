package io.github.arnavdugad.ember.core.designsystem

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Stable

/**
 * One vocabulary for touch feedback, so every screen feels the same in the hand.
 *
 * View-based constants respect the user's system haptics setting for free; the richer
 * celebration pattern uses composition primitives where the motor supports them.
 */
@Stable
class Haptics(private val view: View) {
    private val vibrator: Vibrator? by lazy {
        val context = view.context
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    /** A light tick: tapping a chip, paging a photo. */
    fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    /** Button press. */
    fun press() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    /** A swipe crossed the commit threshold. */
    fun threshold() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        }
    }

    fun confirm() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    fun reject() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            view.performHapticFeedback(HapticFeedbackConstants.REJECT)
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
    }

    /** It's a match: a rising double pulse and a soft landing. */
    fun celebrate() {
        val v = vibrator
        if (v == null || !v.hasVibrator()) {
            confirm()
            return
        }
        val primitives = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && v.areAllPrimitivesSupported(*primitives)) {
            val effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.7f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 60)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 0.6f, 120)
                .compose()
            v.vibrate(effect)
        } else {
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 30, 60, 50, 80, 30), intArrayOf(0, 140, 0, 255, 0, 120), -1))
        }
    }
}
