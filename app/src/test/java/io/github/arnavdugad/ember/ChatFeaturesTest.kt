package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.ui.chat.BubbleArrivals
import io.github.arnavdugad.ember.ui.chat.ChatSearch
import io.github.arnavdugad.ember.ui.chat.TYPING_RAMP_MS
import io.github.arnavdugad.ember.ui.chat.copyText
import io.github.arnavdugad.ember.ui.chat.dayLabel
import io.github.arnavdugad.ember.ui.chat.dotLift
import io.github.arnavdugad.ember.ui.chat.firstReplyAt
import io.github.arnavdugad.ember.ui.chat.startsNewDay
import io.github.arnavdugad.ember.ui.chat.typingCycleMs
import io.github.arnavdugad.ember.ui.chat.unseenSince
import io.github.arnavdugad.ember.ui.chat.veilsPhoto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class ChatFeaturesTest {
    private val now: Instant = Instant.parse("2026-10-27T18:00:00Z")
    private fun msg(id: String, from: String, text: String = "hi", type: MessageType = MessageType.Text, at: Instant? = now) =
        Message(id, from, type, text, createdAt = at)

    @Test
    fun unsendIsMineDeliveredAndUnderFifteenMinutes() {
        val sent = msg("1", "me", at = now.minus(Duration.ofMinutes(14)))
        assertTrue(sent.canUnsend("me", now))
        assertFalse(sent.canUnsend("them", now))
        assertFalse(sent.canUnsend("me", now.plus(Duration.ofMinutes(1))))
        assertFalse(sent.copy(pending = true).canUnsend("me", now))
        assertFalse(sent.copy(unsentAt = now).canUnsend("me", now))
        assertFalse(sent.copy(createdAt = null).canUnsend("me", now))
        assertFalse(msg("2", "me", type = MessageType.Event).canUnsend("me", now))
    }

    @Test
    fun unknownTypesAndEventCodesDegradeGracefully() {
        assertEquals(MessageType.Event, MessageType.of("event"))
        // What an older build does with a type it doesn't know: plain text, so it never crashes.
        assertEquals(MessageType.Text, MessageType.of("sticker"))
        assertEquals(ChatEvent.Screenshot, ChatEvent.of("screenshot"))
        assertNull(ChatEvent.of("recording"))
        val row = msg("e", "them", ChatEvent.Screenshot.fallback, MessageType.Event).copy(event = ChatEvent.Screenshot)
        assertEquals("Took a screenshot of the chat", row.summary)
    }

    @Test
    fun searchFindsWordsIgnoringCaseAndSkipsWhatHasNone() {
        val list = listOf(
            msg("1", "a", "Pizza tonight?"),
            msg("2", "b", "", MessageType.Voice),
            msg("3", "a", "PIZZA place on 5th"),
            msg("4", "b", Message.UNSENT_TEXT).copy(unsentAt = now),
            msg("5", "b", "Rooftop", MessageType.Date).copy(date = DateIdea("Rooftop", "Pizza and a view", "🍕")),
            msg("6", "b", ChatEvent.Screenshot.fallback, MessageType.Event),
        )
        assertEquals(listOf("1", "3", "5"), ChatSearch.matches(list, " pizza "))
        assertEquals(emptyList<String>(), ChatSearch.matches(list, "   "))
        assertEquals(emptyList<String>(), ChatSearch.matches(list, "unsent"))
        assertEquals(emptyList<String>(), ChatSearch.matches(list, "screenshot"))
    }

    @Test
    fun searchHighlightsEveryOccurrence() {
        assertEquals(listOf(0..2, 8..10), ChatSearch.ranges("Hey you hey", "hey"))
        assertEquals(listOf(0..1, 2..3), ChatSearch.ranges("aaaa", "aa"))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("hello", "bye"))
        assertEquals(emptyList<IntRange>(), ChatSearch.ranges("hello", ""))
    }

    @Test
    fun copyIsOnlyOfferedForWords() {
        assertEquals("hi", copyText(msg("1", "a")))
        assertNull(copyText(msg("2", "a", "", MessageType.Image)))
        assertEquals("look", copyText(msg("3", "a", "look", MessageType.Image)))
        assertNull(copyText(msg("4", "a", "", MessageType.Voice)))
        assertNull(copyText(msg("5", "a").copy(unsentAt = now)))
        assertEquals("Rooftop\nWith a view", copyText(msg("6", "a", "Rooftop", MessageType.Date).copy(date = DateIdea("Rooftop", "With a view", "🍸"))))
    }

    @Test
    fun daySeparatorsAndLabels() {
        val zone = ZoneOffset.UTC
        val day = Instant.parse("2026-10-27T09:00:00Z")
        assertTrue(startsNewDay(null, day, zone))
        assertFalse(startsNewDay(day, day.plus(Duration.ofHours(14)), zone))
        assertTrue(startsNewDay(day, day.plus(Duration.ofHours(15)), zone))
        assertFalse(startsNewDay(day, null, zone))

        val today = LocalDate.of(2026, 10, 27) // a Tuesday
        assertEquals("Today", dayLabel(today, today))
        assertEquals("Yesterday", dayLabel(today.minusDays(1), today))
        assertEquals("Thursday", dayLabel(today.minusDays(5), today))
        assertEquals("20 Oct", dayLabel(today.minusDays(7), today))
        assertEquals("3 Dec 2025", dayLabel(LocalDate.of(2025, 12, 3), today))
    }

    @Test
    fun typingDotsQuickenTheLongerTheyType() {
        assertEquals(1_300f, typingCycleMs(0), 0.01f)
        assertEquals(1_300f, typingCycleMs(-50), 0.01f)
        assertEquals(650f, typingCycleMs(TYPING_RAMP_MS), 0.01f)
        assertEquals(650f, typingCycleMs(TYPING_RAMP_MS * 3), 0.01f)
        var last = Float.MAX_VALUE
        for (ms in 0L..TYPING_RAMP_MS step 1_000L) {
            val cycle = typingCycleMs(ms)
            assertTrue(cycle < last)
            last = cycle
        }
        // Each dot lifts in turn and stays within 0..1.
        for (step in 0..100) {
            val phase = step / 100f
            repeat(3) { assertTrue(dotLift(phase, it) in 0f..1f) }
        }
        assertTrue(dotLift(0.25f, 0) > dotLift(0.25f, 2))
    }

    @Test
    fun scrollButtonCountsOnlyTheirNewMessages() {
        val list = listOf(
            msg("5", "them"),
            msg("4", "me"),
            msg("3", "them", ChatEvent.Screenshot.fallback, MessageType.Event),
            msg("2", "them"),
            msg("1", "them"),
        )
        assertEquals(2, unseenSince(list, "1", "them"))
        assertEquals(0, unseenSince(list, "5", "them"))
        assertEquals(0, unseenSince(list, null, "them"))
    }

    @Test
    fun arrivalsReportHowManyJustLanded() {
        val arrivals = BubbleArrivals()
        assertEquals(0, arrivals.observe(listOf("b", "a"), now = 0))
        assertEquals(2, arrivals.observe(listOf("d", "c", "b", "a"), now = 5_000))
        assertEquals(0, arrivals.observe(listOf("d", "c", "b", "a"), now = 6_000))
    }

    @Test
    fun photosAreVeiledUntilIveReplied() {
        val list = listOf(
            msg("4", "them", "", MessageType.Image, now),
            msg("3", "me", "hey", at = now.minusSeconds(60)),
            msg("2", "me", ChatEvent.Screenshot.fallback, MessageType.Event, now.minusSeconds(120)),
            msg("1", "them", "", MessageType.Image, now.minusSeconds(300)),
        )
        val replied = firstReplyAt(list, "me")
        assertEquals(now.minusSeconds(60), replied)
        assertTrue(veilsPhoto(now.minusSeconds(300), replied, blurAll = false))
        assertFalse(veilsPhoto(now, replied, blurAll = false))
        assertTrue(veilsPhoto(now, replied, blurAll = true))
        assertTrue(veilsPhoto(now, firstReplyAt(list.drop(1).filter { it.type != MessageType.Text }, "me"), blurAll = false))
    }
}
