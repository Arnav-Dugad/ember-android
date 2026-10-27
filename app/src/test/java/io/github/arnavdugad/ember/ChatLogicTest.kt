package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.DatePlanner
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.ui.chat.SafetyNudges
import io.github.arnavdugad.ember.ui.chat.formatDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ChatLogicTest {
    @Test
    fun moneyAndOffPlatformAsksAreFlagged() {
        listOf(
            "Can you send me $200 for a cab? I'll pay you back",
            "my upi is rahul.k@okaxis",
            "Could you get me an Apple gift card?",
            "I made so much on bitcoin, let me show you",
            "Text me on WhatsApp instead, I'm rarely here",
            "add me on snap: wild_thing99",
            "my number is +91 98765 43210",
            "call me 555-123-4567",
            "what's your bank account number?",
            "send over some money and I'll come see you",
            "I need 500 dollars for my visa",
            "t.me/someone",
        ).forEach { assertTrue(it, SafetyNudges.isRisky(it)) }
    }

    @Test
    fun ordinaryFlirtingIsNotFlagged() {
        listOf(
            "hey! how was your weekend?",
            "send me your favourite song",
            "We walked along the river bank at sunset",
            "I was born in 1994 and moved here in 2019",
            "See you at 7:30 near gate 4?",
            "Snapping my fingers to this song",
            "",
        ).forEach { assertFalse(it, SafetyNudges.isRisky(it)) }
    }

    @Test
    fun phoneNumbersNeedTenToFifteenDigits() {
        assertTrue(SafetyNudges.containsPhoneNumber("9876543210"))
        assertTrue(SafetyNudges.containsPhoneNumber("it's (415) 555-0132"))
        assertTrue(SafetyNudges.containsPhoneNumber("+44 7700 900123"))
        assertFalse(SafetyNudges.containsPhoneNumber("my pin is 123456"))
        assertFalse(SafetyNudges.containsPhoneNumber("1234 5678 9012 3456 7890")) // 20 digits: not a phone number
    }

    @Test
    fun datePlannerPrefersSharedInterests() {
        val ideas = DatePlanner.suggest(listOf("Hiking", "Cocktails"), listOf("Cocktails", "Film"), "Pune", Random(7))
        assertTrue(ideas.size in DatePlanner.MIN_IDEAS..DatePlanner.MAX_IDEAS)
        assertEquals("Rooftop cocktails at golden hour", ideas.first().title)
        assertTrue(ideas.any { it.title == "Sunrise trail + chai after" })
        assertTrue(ideas.all { "{city}" !in it.detail })
        assertTrue(ideas.first().detail.contains("Pune"))
    }

    @Test
    fun datePlannerAlwaysOffersThreeToFive() {
        repeat(20) { seed ->
            val none = DatePlanner.suggest(emptyList(), emptyList(), "", Random(seed))
            assertEquals(DatePlanner.MIN_IDEAS, none.size)
            assertTrue(none.all { "{city}" !in it.detail })
            val lots = DatePlanner.suggest(Catalog.interests, Catalog.interests, "Lisbon", Random(seed))
            assertEquals(DatePlanner.MAX_IDEAS, lots.size)
            assertEquals(lots.size, lots.map { it.title }.distinct().size)
        }
    }

    @Test
    fun everyInterestHasADateIdea() {
        Catalog.interests.forEach { interest ->
            val ideas = DatePlanner.suggest(listOf(interest), listOf(interest), "Goa", Random(1))
            assertTrue(interest, ideas.first().title.isNotBlank())
            assertTrue(interest, ideas.all { it.title.length <= 80 && it.detail.length <= 200 })
        }
    }

    @Test
    fun repliesQuoteAShortSummary() {
        val long = Message("m1", "a", MessageType.Text, "x".repeat(500), createdAt = null)
        assertEquals(ReplyRef.MAX_TEXT, ReplyRef.of(long).text.length)
        val emojiAtTheCut = Message("m0", "a", MessageType.Text, "x".repeat(ReplyRef.MAX_TEXT - 1) + "🔥", createdAt = null)
        assertEquals("x".repeat(ReplyRef.MAX_TEXT - 1), ReplyRef.of(emojiAtTheCut).text)
        val voice = Message("m2", "a", MessageType.Voice, "", createdAt = null, audioId = "v", durationMs = 4_000)
        assertEquals("Voice note", ReplyRef.of(voice).text)
        val viewOnce = Message("m3", "a", MessageType.Image, "", createdAt = null, viewOnce = true)
        assertEquals("View-once photo", ReplyRef.of(viewOnce).text)
    }

    @Test
    fun myUidComesFromTheMatchId() {
        val match = Match("alice_bob", "bob", "Bob", null, null, false, null, null, null, null, false)
        assertEquals("alice", match.myUid)
    }

    @Test
    fun durationsRoundToSeconds() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:05", formatDuration(4_600))
        assertEquals("1:00", formatDuration(60_000))
    }
}
