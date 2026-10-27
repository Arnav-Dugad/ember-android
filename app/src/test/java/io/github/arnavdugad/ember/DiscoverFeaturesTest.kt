package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.model.ActivityStamp
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.LikeNoteKind
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Presence
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.Spotlight
import io.github.arnavdugad.ember.data.model.spotlightFirst
import io.github.arnavdugad.ember.data.remote.matches
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant

/** Online status, Spotlight and prompt/photo like notes: the pure logic behind them. */
class DiscoverFeaturesTest {
    private val now: Instant = Instant.parse("2026-10-27T15:30:00Z")

    private fun person(
        uid: String,
        lastActive: Instant? = null,
        hideActivity: Boolean = false,
        spotlightUntil: Instant? = null,
    ) = Profile(
        uid = uid,
        name = uid,
        age = 28,
        gender = null,
        interestedIn = emptySet(),
        bio = "",
        photos = listOf(Photo("$uid-0", "https://example.com/$uid.jpg")),
        prompts = listOf(ProfilePrompt("p_flirt_style", "My flirting style is", "Eye contact and terrible puns")),
        interests = emptyList(),
        vibes = emptyList(),
        lookingFor = LookingFor.Dating,
        heightCm = null,
        city = "",
        lastActiveAt = lastActive,
        hideActivity = hideActivity,
        spotlightUntil = spotlightUntil,
    )

    private fun minutesAgo(m: Long) = now.minus(Duration.ofMinutes(m))

    // --- Online now ---

    @Test
    fun onlineMeansSeenWithinTheWindow() {
        assertTrue(Presence.isOnline(now, now))
        assertTrue(Presence.isOnline(minutesAgo(10), now))
        assertFalse(Presence.isOnline(minutesAgo(11), now))
        assertFalse(Presence.isOnline(null, now))
        // A server clock slightly ahead of the phone's is still "now"; far in the future isn't.
        assertTrue(Presence.isOnline(now.plusSeconds(90), now))
        assertFalse(Presence.isOnline(now.plus(Duration.ofHours(2)), now))
    }

    @Test
    fun activityLabels() {
        assertEquals("Active now", Presence.label(minutesAgo(3), now))
        assertEquals("Active 25m ago", Presence.label(minutesAgo(25), now))
        assertEquals("Active 2h ago", Presence.label(minutesAgo(150), now))
        assertEquals("Active 3d ago", Presence.label(now.minus(Duration.ofDays(3)), now))
        // Past a week it says nothing rather than something discouraging.
        assertNull(Presence.label(now.minus(Duration.ofDays(8)), now))
        assertNull(Presence.label(null, now))
    }

    @Test
    fun hidingActivityWorksBothWays() {
        val them = person("them", lastActive = minutesAgo(2))
        assertTrue(them.isOnline(viewerHidesActivity = false, now = now))
        assertEquals("Active now", them.activityLabel(viewerHidesActivity = false, now = now))
        // I hide mine, so I see nobody's.
        assertFalse(them.isOnline(viewerHidesActivity = true, now = now))
        assertNull(them.activityLabel(viewerHidesActivity = true, now = now))
        // They hide theirs.
        val hidden = person("hidden", lastActive = minutesAgo(2), hideActivity = true)
        assertFalse(hidden.isOnline(now = now))
        assertNull(hidden.activityLabel(now = now))
    }

    @Test
    fun heartbeatStampsRespectPrivacy() {
        val midnight = Instant.parse("2026-10-27T00:00:00Z")
        assertEquals(midnight, Presence.dayStart(now))
        assertEquals(midnight, Presence.dayStart(Instant.parse("2026-10-27T23:59:59Z")))
        // Visible: the server's clock, but not again within a couple of minutes of the last write.
        assertEquals(ActivityStamp.ServerTime, Presence.nextStamp(false, minutesAgo(30), null, now))
        assertEquals(ActivityStamp.ServerTime, Presence.nextStamp(false, minutesAgo(5), Duration.ofMinutes(5), now))
        assertNull(Presence.nextStamp(false, minutesAgo(1), Duration.ofSeconds(40), now))
        // Hidden: today's midnight, once.
        assertEquals(ActivityStamp.At(midnight), Presence.nextStamp(true, Instant.parse("2026-10-26T00:00:00Z"), null, now))
        assertEquals(ActivityStamp.At(midnight), Presence.nextStamp(true, null, null, now))
        assertNull(Presence.nextStamp(true, midnight, null, now))
        // A precise time left over from before hiding gets replaced by the midnight.
        assertEquals(ActivityStamp.At(midnight), Presence.nextStamp(true, minutesAgo(5), Duration.ofMinutes(5), now))
    }

    @Test
    fun onlineFilterIsIgnoredWhileIHideMyOwnActivity() {
        val online = person("online", lastActive = minutesAgo(1))
        val away = person("away", lastActive = minutesAgo(90))
        val filters = DiscoveryFilters(onlineOnly = true)
        val me = person("me")
        assertTrue(online.matches(filters, afterDark = false, me = me, now = now))
        assertFalse(away.matches(filters, afterDark = false, me = me, now = now))
        assertTrue(away.matches(filters, afterDark = false, me = me.copy(hideActivity = true), now = now))
        assertTrue(away.matches(DiscoveryFilters(), afterDark = false, me = me, now = now))
    }

    // --- Spotlight ---

    @Test
    fun spotlightCounterAndClock() {
        assertEquals("spotlight_2026-10-27", Spotlight.counterId(now))
        // 23:30 in New York is already tomorrow in UTC, which is when boosts reset.
        assertEquals("spotlight_2026-10-28", Spotlight.counterId(Instant.parse("2026-10-28T03:30:00Z")))
        val until = Spotlight.until(now)
        assertTrue(until <= now.plus(Spotlight.DURATION))
        assertTrue(until > now.plus(Duration.ofMinutes(29)))
        assertEquals(1f, Spotlight.remainingFraction(now.plus(Spotlight.DURATION), now), 0.001f)
        assertEquals(0.5f, Spotlight.remainingFraction(now.plus(Duration.ofMinutes(15)), now), 0.001f)
        assertEquals(0f, Spotlight.remainingFraction(minutesAgo(1), now), 0.001f)
        assertEquals(0f, Spotlight.remainingFraction(null, now), 0.001f)
        assertEquals("29:41", Spotlight.remainingLabel(now.plusSeconds(29 * 60 + 41), now))
        assertEquals("0:05", Spotlight.remainingLabel(now.plusSeconds(5), now))
        assertEquals("0:00", Spotlight.remainingLabel(minutesAgo(1), now))
    }

    @Test
    fun spotlitPeopleComeFirst() {
        val a = person("a")
        val b = person("b", spotlightUntil = now.plus(Duration.ofMinutes(20)))
        val c = person("c", spotlightUntil = minutesAgo(5)) // over
        val d = person("d", spotlightUntil = now.plus(Duration.ofMinutes(5)))
        val e = person("e")
        val ordered = listOf(a, b, c, d, e).spotlightFirst(now).map { it.uid }
        // Lit ones first, ending soonest first; everyone else keeps their order.
        assertEquals(listOf("d", "b", "a", "c", "e"), ordered)
        assertEquals(listOf("a", "e"), listOf(a, e).spotlightFirst(now).map { it.uid })
    }

    // --- Like notes ---

    @Test
    fun likeNotesAreValidatedLikeTheRules() {
        assertEquals("Love this", LikeNote.of(LikeNoteKind.Prompt, "p_flirt_style", "  Love this  ")?.text)
        assertEquals(LikeNote.MAX_TEXT, LikeNote.of(LikeNoteKind.Prompt, "p1", "x".repeat(500))?.text?.length)
        assertEquals("", LikeNote.of(LikeNoteKind.Photo, "4b0c6f1e-2a7d-4c52-9a51-0d3b7f2e9c11", "")?.text)
        assertNull(LikeNote.of(LikeNoteKind.Photo, "https://example.com/a.jpg", "hi"))
        assertNull(LikeNote.of(LikeNoteKind.Prompt, "", "hi"))
        assertNull(LikeNote.of(LikeNoteKind.Prompt, "x".repeat(129), "hi"))
        // Cutting at the limit never splits an emoji.
        val emojiAtEdge = "x".repeat(LikeNote.MAX_TEXT - 1) + "🔥"
        assertEquals(LikeNote.MAX_TEXT - 1, LikeNote.of(LikeNoteKind.Prompt, "p1", emojiAtEdge)?.text?.length)
    }

    @Test
    fun likeNotesSurviveTheTripBackToTheDeck() {
        val note = LikeNote.of(LikeNoteKind.Prompt, "p_flirt_style", "Ha: same, honestly")!!
        assertEquals(note, LikeNote.decode(note.encode()))
        assertNull(LikeNote.decode(null))
        assertNull(LikeNote.decode("nonsense"))
        assertNull(LikeNote.decode("bio\u001Fp1\u001Fhi"))
    }

    @Test
    fun theFirstMessageQuotesWhatWasLiked() {
        val maya = person("maya")
        val prompt = LikeNote.of(LikeNoteKind.Prompt, "p_flirt_style", "")!!
        assertEquals("❤️", prompt.messageText)
        val quote = prompt.quote(maya)!!
        assertEquals("maya", quote.senderId)
        assertEquals(MessageType.Text, quote.type)
        assertEquals("My flirting style is: Eye contact and terrible puns", quote.text)
        assertTrue(quote.text.length <= ReplyRef.MAX_TEXT)

        val photo = LikeNote.of(LikeNoteKind.Photo, "maya-0", "Where is this?")!!
        assertEquals("Where is this?", photo.messageText)
        assertEquals(MessageType.Image, photo.quote(maya)?.type)
        // Gone from their profile since: the message goes out without a quote.
        assertNull(LikeNote.of(LikeNoteKind.Photo, "maya-9", "")!!.quote(maya))
        val long = maya.copy(prompts = listOf(ProfilePrompt("p1", "Q".repeat(60), "A".repeat(200))))
        assertEquals(ReplyRef.MAX_TEXT, LikeNote.of(LikeNoteKind.Prompt, "p1", "")!!.quote(long)?.text?.length)
    }
}
