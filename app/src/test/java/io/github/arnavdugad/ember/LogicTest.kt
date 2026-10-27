package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.core.util.Geo
import io.github.arnavdugad.ember.data.model.Ages
import io.github.arnavdugad.ember.update.UpdateRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LogicTest {
    @Test
    fun versionsCompareNumerically() {
        assertTrue(UpdateRepository.isNewer("1.10.0", "1.9.3"))
        assertTrue(UpdateRepository.isNewer("2.0.0", "1.99.99"))
        assertTrue(UpdateRepository.isNewer("1.0.1", "1.0"))
        assertFalse(UpdateRepository.isNewer("1.0.0", "1.0.0"))
        assertFalse(UpdateRepository.isNewer("1.0.0", "1.0.1"))
    }

    @Test
    fun releaseNotesDropMachineLinesAndMarker() {
        val body = """
            Faster deck [mandatory]

            **[Download the APK](https://example.com/a.apk)**

            <!-- Read by the in-app updater. -->
            versionCode: 10100
            sha256: ${"a".repeat(64)}
        """.trimIndent()
        assertEquals("Faster deck", UpdateRepository.cleanNotes(body))
    }

    @Test
    fun geohashMatchesReferenceValue() {
        // Reference from the original geohash algorithm: 57.64911, 10.40744 -> u4pruydqqvj
        assertEquals("u4pru", Geo.geohash(57.64911, 10.40744))
    }

    @Test
    fun coarseLocationIsTwoDecimals() {
        assertEquals(40.71, Geo.coarse(40.712776), 0.0)
        assertEquals(-74.01, Geo.coarse(-74.005974), 0.0)
    }

    @Test
    fun ageCountsBirthdayOnlyOnceReached() {
        val today = LocalDate.of(2026, 10, 27)
        assertEquals(18, Ages.ageOn(LocalDate.of(2008, 10, 27), today))
        assertEquals(17, Ages.ageOn(LocalDate.of(2008, 10, 28), today))
        assertEquals(17, Ages.ageOn(LocalDate.of(2008, 12, 1), today))
    }
}
