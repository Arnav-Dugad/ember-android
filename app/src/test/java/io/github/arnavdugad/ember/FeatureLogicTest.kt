package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.location.Cities
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import io.github.arnavdugad.ember.data.remote.withDistanceFrom
import io.github.arnavdugad.ember.ui.discover.DailyPicks
import io.github.arnavdugad.ember.ui.profile.EditSection
import io.github.arnavdugad.ember.ui.profile.ProfileStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.random.Random

class FeatureLogicTest {
    private fun person(
        uid: String,
        interests: List<String> = emptyList(),
        vibes: List<String> = emptyList(),
        lookingFor: LookingFor = LookingFor.Dating,
        distanceKm: Int? = null,
        photos: Int = 1,
        prompts: Int = 0,
        bio: String = "",
        heightCm: Int? = null,
        city: String = "",
        voiceMs: Int? = null,
        lat: Double? = null,
        lng: Double? = null,
    ) = Profile(
        uid = uid,
        name = uid,
        age = 28,
        gender = null,
        interestedIn = emptySet(),
        bio = bio,
        photos = List(photos) { Photo("$uid-$it", "https://example.com/$uid-$it.jpg") },
        prompts = List(prompts) { ProfilePrompt("p$it", "Q$it", "A$it") },
        interests = interests,
        vibes = vibes,
        lookingFor = lookingFor,
        heightCm = heightCm,
        city = city,
        distanceKm = distanceKm,
        lat = lat,
        lng = lng,
        voiceDurationMs = voiceMs,
    )

    // --- Today's picks ---

    @Test
    fun picksDayTurnsOverAtSixPm() {
        val day = LocalDate.of(2026, 10, 27)
        assertEquals(day.minusDays(1), DailyPicks.dayOf(day.atTime(17, 59)))
        assertEquals(day, DailyPicks.dayOf(day.atTime(18, 0)))
        assertEquals(day, DailyPicks.dayOf(day.atTime(23, 59)))
        assertEquals(day.minusDays(1), DailyPicks.dayOf(day.atTime(0, 30)))
        assertFalse(DailyPicks.isFresh(LocalDateTime.of(2026, 10, 27, 9, 0)))
        assertTrue(DailyPicks.isFresh(LocalDateTime.of(2026, 10, 27, 18, 0)))
    }

    @Test
    fun scoreWeighsInterestsVibesIntentAndDistance() {
        val me = person("me", interests = listOf("Wine", "Hiking", "Film"), vibes = listOf("chill", "foodie"), lookingFor = LookingFor.Dating)
        val them = person("x", interests = listOf("Wine", "Film", "Techno"), vibes = listOf("chill"), lookingFor = LookingFor.Dating, distanceKm = 4)
        // 2 shared interests x2 + 1 shared vibe x2 + same intent 3 + close (<= 5 km) 4.
        assertEquals(4 + 2 + 3 + 4, DailyPicks.score(me, them))
        assertEquals(0, DailyPicks.distanceBonus(null))
        assertEquals(0, DailyPicks.distanceBonus(500))
    }

    @Test
    fun picksAreTheBestFiveStableForTheDayAndNeverMe() {
        val me = person("me", interests = listOf("Wine", "Hiking", "Film", "Yoga"), vibes = listOf("chill", "foodie"))
        val strong = (1..5).map { person("strong$it", interests = listOf("Wine", "Hiking", "Film"), vibes = listOf("chill", "foodie"), distanceKm = 3) }
        val weak = (1..30).map { person("weak$it", lookingFor = LookingFor.Friends, distanceKm = 400) }
        val candidates = strong + weak + me
        val day = LocalDate.of(2026, 10, 27)
        val picks = DailyPicks.choose(candidates, me, "me", day)
        assertEquals(5, picks.size)
        assertEquals(strong.map { it.uid }.toSet(), picks.map { it.uid }.toSet())
        // Same day, any input order: the same picks in the same order.
        val reshuffled = DailyPicks.choose(candidates.shuffled(Random(3)), me, "me", day)
        assertEquals(picks.map { it.uid }, reshuffled.map { it.uid })
        // Among equals, a different day may order them differently, but it is still deterministic.
        val tomorrow = DailyPicks.choose(candidates, me, "me", day.plusDays(1))
        assertEquals(tomorrow.map { it.uid }, DailyPicks.choose(candidates.reversed(), me, "me", day.plusDays(1)).map { it.uid })
        assertTrue(picks.none { it.uid == "me" })
    }

    // --- City picker ---

    @Test
    fun bundledCitiesCoverIndiaAndTheWorld() {
        assertTrue("expected ~150 cities, got ${Cities.size}", Cities.size >= 150)
        assertEquals("Mumbai", Cities.search("mum").first().place.name)
        assertEquals("Bengaluru", Cities.search("Bangalore").first().place.name)
        assertEquals("Gurugram", Cities.search("gurgaon").first().place.name)
        assertEquals("São Paulo", Cities.search("sao pa").first().place.name)
        assertEquals("Zurich", Cities.search("zürich").first().place.name)
        assertTrue(Cities.search("").isNotEmpty())
        assertTrue(Cities.search("qqqqzz").isEmpty())
        // Coordinates are stored at city precision.
        Cities.search("", limit = 500).forEach { s ->
            assertEquals(s.place.lat, Math.round(s.place.lat * 100) / 100.0, 1e-9)
            assertEquals(s.place.lng, Math.round(s.place.lng * 100) / 100.0, 1e-9)
        }
    }

    // --- Travel mode ---

    @Test
    fun distancesStartFromTheTravelOrigin() {
        val mumbaiLocal = person("a", lat = 19.08, lng = 72.88)
        val fromMumbai = mumbaiLocal.withDistanceFrom(19.08 to 72.88)
        val fromDelhi = mumbaiLocal.withDistanceFrom(28.61 to 77.21)
        assertEquals(1, fromMumbai.distanceKm)
        assertTrue((fromDelhi.distanceKm ?: 0) in 1100..1200)
        assertNull(person("b").withDistanceFrom(19.08 to 72.88).distanceKm)
    }

    // --- Free tonight ---

    @Test
    fun freeTonightExpires() {
        val now = Instant.parse("2026-10-27T20:00:00Z")
        assertTrue(person("a").copy(freeTonightUntil = now.plusSeconds(3600)).isFreeTonight(now))
        assertFalse(person("a").copy(freeTonightUntil = now.minusSeconds(1)).isFreeTonight(now))
        assertFalse(person("a").isFreeTonight(now))
    }

    // --- Profile strength ---

    @Test
    fun tipsComeInOrderOfValue() {
        val bare = person("a", photos = 1)
        assertEquals(EditSection.Photos, ProfileStrength.nextTip(bare)?.section)
        assertTrue(ProfileStrength.nextTip(bare)!!.text.startsWith("Add a 2nd photo"))
        assertEquals(EditSection.Prompts, ProfileStrength.nextTip(person("a", photos = 2))?.section)
        val noVoice = person(
            "a", photos = 3, prompts = 2, bio = "x".repeat(60), interests = listOf("A", "B", "C"),
            vibes = listOf("chill", "foodie"), heightCm = 170, city = "Pune",
        )
        assertEquals(EditSection.Voice, ProfileStrength.nextTip(noVoice)?.section)
        assertTrue(ProfileStrength.completeness(noVoice) < 1f)
        val complete = noVoice.copy(voiceDurationMs = 12_000)
        assertNull(ProfileStrength.nextTip(complete))
        assertEquals(1f, ProfileStrength.completeness(complete), 0f)
        assertNotNull(ProfileStrength.nextTip(complete.copy(heightCm = null)))
        assertEquals(EditSection.About, ProfileStrength.nextTip(complete.copy(heightCm = null))?.section)
    }
}
