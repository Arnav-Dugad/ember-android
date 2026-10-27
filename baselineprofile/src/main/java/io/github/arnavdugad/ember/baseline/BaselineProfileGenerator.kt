package io.github.arnavdugad.ember.baseline

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the start-up path and the swipe deck, the two places where a dropped frame is most
 * noticeable. The app opens in Preview mode when no Firebase project is configured, so the deck
 * is reachable without signing in.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()

        device.findObject(By.res("preview_mode"))?.click()
        device.wait(Until.hasObject(By.res("swipe_deck")), 5_000)
        val deck = device.findObject(By.res("swipe_deck")) ?: return@collect
        repeat(4) {
            deck.swipe(if (it % 2 == 0) Direction.RIGHT else Direction.LEFT, 0.8f)
            device.waitForIdle()
        }
    }

    private companion object {
        const val TARGET_PACKAGE = "io.github.arnavdugad.ember"
    }
}
