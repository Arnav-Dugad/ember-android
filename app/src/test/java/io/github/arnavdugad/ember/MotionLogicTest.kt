package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.core.designsystem.liquidSpan
import io.github.arnavdugad.ember.core.designsystem.mixPalette
import io.github.arnavdugad.ember.core.image.PhotoSwatch
import io.github.arnavdugad.ember.core.image.hsv
import io.github.arnavdugad.ember.core.image.interleave
import io.github.arnavdugad.ember.core.image.pickVividColors
import io.github.arnavdugad.ember.ui.chat.BubbleArrivals
import io.github.arnavdugad.ember.ui.discover.TrailBuffer
import io.github.arnavdugad.ember.ui.discover.trailFade
import io.github.arnavdugad.ember.ui.likes.SuperLikeHeartbeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionLogicTest {
    // ---------- Liquid page indicator ----------

    @Test
    fun liquidSpan_restsOnOnePage() {
        val span = liquidSpan(2f, 5)
        assertEquals(2f, span.start, 0.0001f)
        assertEquals(2f, span.end, 0.0001f)
        assertEquals(0f, span.stretch, 0.0001f)
    }

    @Test
    fun liquidSpan_leadingEdgeStretchesFirstThenTrailingCatchesUp() {
        val quarter = liquidSpan(1.25f, 4)
        assertEquals(1f, quarter.start, 0.0001f)
        assertEquals(1.5f, quarter.end, 0.0001f)

        val half = liquidSpan(1.5f, 4)
        assertEquals(1f, half.start, 0.0001f)
        assertEquals(2f, half.end, 0.0001f)
        assertEquals(1f, half.stretch, 0.0001f)

        val most = liquidSpan(1.75f, 4)
        assertEquals(1.5f, most.start, 0.0001f)
        assertEquals(2f, most.end, 0.0001f)
    }

    @Test
    fun liquidSpan_clampsOutOfRangeAndSinglePage() {
        assertEquals(liquidSpan(0f, 3), liquidSpan(-0.4f, 3))
        assertEquals(liquidSpan(2f, 3), liquidSpan(2.3f, 3))
        assertEquals(0f, liquidSpan(0.6f, 1).stretch, 0.0001f)
    }

    @Test
    fun liquidSpan_neverStretchesBeyondTwoPages() {
        var p = 0f
        while (p <= 4f) {
            val span = liquidSpan(p, 5)
            assertTrue(span.stretch in 0f..1f)
            assertTrue(span.start <= span.end)
            p += 0.05f
        }
    }

    // ---------- Swipe trail ----------

    @Test
    fun trailBuffer_keepsNewestPointsWhenFull() {
        val trail = TrailBuffer(capacity = 3)
        repeat(5) { i -> trail.add(i.toFloat(), -i.toFloat(), time = i * 10L, tint = i % 3, weight = 1f) }
        assertEquals(3, trail.size)
        assertEquals(2f, trail.x(0), 0f)
        assertEquals(4f, trail.x(2), 0f)
        assertEquals(-4f, trail.y(2), 0f)
        assertEquals(40L, trail.time(2))
        assertEquals(1, trail.tint(2))
    }

    @Test
    fun trailBuffer_skipsPointsTooCloseInTime() {
        val trail = TrailBuffer(capacity = 8, spacingMs = 20)
        assertTrue(trail.add(0f, 0f, 100, 0, 1f))
        assertFalse(trail.add(1f, 0f, 110, 0, 1f))
        assertTrue(trail.add(2f, 0f, 120, 0, 1f, angle = 7f))
        assertEquals(2, trail.size)
        assertEquals(7f, trail.angle(1), 0f)
    }

    @Test
    fun trailBuffer_prunesExpiredPointsFromTheOldEnd() {
        val trail = TrailBuffer(capacity = 4)
        listOf(0L, 100L, 200L, 300L).forEach { trail.add(it.toFloat(), 0f, it, 0, 1f) }
        trail.add(400f, 0f, 400, 0, 1f)
        trail.prune(now = 650, life = 400)
        assertEquals(2, trail.size)
        assertEquals(300f, trail.x(0), 0f)
        trail.prune(now = 10_000, life = 400)
        assertEquals(0, trail.size)
        trail.add(1f, 1f, 10_000, 2, 0.5f)
        assertEquals(1f, trail.x(0), 0f)
        assertEquals(0.5f, trail.weight(0), 0f)
    }

    @Test
    fun trailFade_easesOutOverItsLife() {
        assertEquals(1f, trailFade(0, 400), 0.0001f)
        assertEquals(0.25f, trailFade(200, 400), 0.0001f)
        assertEquals(0f, trailFade(400, 400), 0.0001f)
        assertEquals(0f, trailFade(900, 400), 0.0001f)
    }

    // ---------- Photo colours ----------

    @Test
    fun hsv_readsPrimaries() {
        val red = hsv(0xFF0000)
        assertEquals(0f, red[0], 0.5f)
        assertEquals(1f, red[1], 0.001f)
        val blue = hsv(0x0000FF)
        assertEquals(240f, blue[0], 0.5f)
        assertEquals(0f, hsv(0x808080)[1], 0.001f)
    }

    @Test
    fun pickVividColors_skipsGreysAndNearDuplicateHues() {
        val swatches = listOf(
            PhotoSwatch(0x808080, 5_000), // grey: not vivid however common
            PhotoSwatch(0x101010, 4_000), // near black
            PhotoSwatch(0xE0301E, 2_000), // red
            PhotoSwatch(0xD8381A, 1_500), // another red, too close in hue
            PhotoSwatch(0x1E60E0, 900), // blue
            PhotoSwatch(0x30C060, 400), // green
        )
        val picked = pickVividColors(swatches, limit = 3).map { hsv(it and 0xFFFFFF)[0] }
        assertEquals(3, picked.size)
        assertTrue(picked[0] < 15f || picked[0] > 345f)
        assertEquals(220f, picked[1], 5f)
        assertEquals(140f, picked[2], 5f)
    }

    @Test
    fun pickVividColors_liftsDarkColoursAndIsOpaque() {
        val picked = pickVividColors(listOf(PhotoSwatch(0x402010, 100)))
        assertEquals(1, picked.size)
        assertEquals(0xFF, picked[0] ushr 24)
        assertTrue(hsv(picked[0] and 0xFFFFFF)[2] >= 0.84f)
    }

    @Test
    fun pickVividColors_emptyForAColourlessPhoto() {
        assertTrue(pickVividColors(listOf(PhotoSwatch(0xEEEEEE, 10), PhotoSwatch(0x222222, 10))).isEmpty())
        assertTrue(pickVividColors(emptyList()).isEmpty())
    }

    @Test
    fun mixPalette_keepsBrandEndsAndCyclesPhotoColours() {
        val brand = listOf("accent", "b", "c", "d", "white")
        assertEquals(brand, mixPalette(brand, emptyList()))
        assertEquals(listOf("accent", "p1", "p2", "p1", "white"), mixPalette(brand, listOf("p1", "p2")))
    }

    @Test
    fun interleave_alternatesBothPeople() {
        assertEquals(listOf(1, 10, 2, 20, 3), interleave(listOf(1, 2, 3), listOf(10, 20)))
        assertEquals(listOf(10, 20), interleave(emptyList(), listOf(10, 20)))
    }

    // ---------- Bubble pop ----------

    @Test
    fun bubbleArrivals_historyNeverPops() {
        val arrivals = BubbleArrivals()
        arrivals.observe(listOf("c", "b", "a"), now = 0)
        // The server snapshot right after the cached one is still history.
        arrivals.observe(listOf("d", "c", "b", "a"), now = 300)
        listOf("a", "b", "c", "d").forEach { assertFalse(arrivals.consume(it)) }
    }

    @Test
    fun bubbleArrivals_newMessagesPopOnceAndOlderPagesDont() {
        val arrivals = BubbleArrivals()
        arrivals.observe(listOf("b", "a"), now = 0)
        arrivals.observe(listOf("c", "b", "a"), now = 5_000)
        // An older page loaded at the far end.
        arrivals.observe(listOf("d", "c", "b", "a", "old2", "old1"), now = 6_000)
        assertTrue(arrivals.consume("c"))
        assertFalse(arrivals.consume("c"))
        assertTrue(arrivals.consume("d"))
        assertFalse(arrivals.consume("old1"))
        assertFalse(arrivals.consume("old2"))
    }

    // ---------- Super-like heartbeat ----------

    @Test
    fun superLikeHeartbeats_oncePerAdmirer() {
        val beats = SuperLikeHeartbeats()
        assertFalse(beats.anyNew(emptyList()))
        assertTrue(beats.anyNew(listOf("ana")))
        assertFalse(beats.anyNew(listOf("ana")))
        assertTrue(beats.anyNew(listOf("ana", "bo")))
        assertFalse(beats.anyNew(listOf("bo", "ana")))
    }
}
