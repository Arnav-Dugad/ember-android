package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.ai.AiPace
import io.github.arnavdugad.ember.data.ai.AiProvider
import io.github.arnavdugad.ember.data.ai.ModelPicker
import io.github.arnavdugad.ember.data.ai.Persona
import io.github.arnavdugad.ember.data.ai.StoredAiChat
import io.github.arnavdugad.ember.data.ai.StoredAiMessage
import io.github.arnavdugad.ember.data.ai.mergeAiMessages
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Gender
import io.github.arnavdugad.ember.data.model.LookingFor
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfilePrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.random.Random

class AiChatTest {
    private val persona = "demo1"
    private val me = "me"

    private fun msg(id: String, from: String, text: String, at: Long, type: MessageType = MessageType.Text, game: GameCard? = null) =
        Message(id = id, senderId = from, type = type, text = text, createdAt = Instant.ofEpochSecond(at), game = game)

    private fun profile(uid: String, name: String) = Profile(
        uid = uid, name = name, age = 24, gender = Gender.Woman, interestedIn = setOf(Gender.Man),
        bio = "Pottery on Sundays, chaos the rest of the week.", photos = emptyList(),
        prompts = listOf(ProfilePrompt("p1", "My most irrational fear", "Geese.")),
        interests = listOf("Pottery", "Hiking", "Jazz"), vibes = listOf("Night owl"),
        lookingFor = LookingFor.Dating, heightCm = 165, city = "Pune",
    )

    @Test
    fun transcriptIsOldestFirstStartsWithUserAndJoinsRuns() {
        val newestFirst = listOf(
            msg("4", me, "and you?", 40),
            msg("3", me, "I love hiking", 30),
            msg("2", persona, "hii", 20),
            msg("1", persona, "you look fun", 10),
        )
        val turns = Persona.transcript(newestFirst, persona)
        assertEquals(3, turns.size)
        assertTrue(turns[0].fromUser) // the "you matched" stub, since the persona spoke first
        assertFalse(turns[1].fromUser)
        assertEquals("you look fun\nhii", turns[1].text)
        assertEquals("I love hiking\nand you?", turns[2].text)
        assertTrue(turns[2].fromUser)
    }

    @Test
    fun transcriptKeepsOnlyRecentHistory() {
        val many = (100 downTo 1).map { msg("$it", if (it % 2 == 0) me else persona, "m$it", it.toLong()) }
        val turns = Persona.transcript(many, persona)
        assertTrue(turns.size <= Persona.HISTORY + 1)
        assertEquals("m100", turns.last().text)
    }

    @Test
    fun appEventsAreDescribedInBrackets() {
        assertEquals("[sent a photo]", Persona.describe(msg("1", me, "", 1, MessageType.Image)))
        val card = msg("2", me, "", 1, MessageType.Game, GameCard(GameMode.Truth, "Worst date ever?"))
        assertEquals("[Truth or Dare card: Truth] Worst date ever?", Persona.describe(card))
        assertEquals("[sent a 3-second voice note]", Persona.describe(msg("3", me, "", 1, MessageType.Voice).copy(durationMs = 2_400)))
    }

    @Test
    fun bubblesSplitOnBlankLinesAndDropLabelsAndQuotes() {
        val raw = "Aanya: \"haha wait\"\n\nyou actually like geese??\n\n\n  ok tell me more  "
        assertEquals(listOf("haha wait", "you actually like geese??", "ok tell me more"), Persona.bubbles(raw, "Aanya"))
    }

    @Test
    fun bubblesAreCapped() {
        val raw = (1..9).joinToString("\n\n") { "line $it" }
        val bubbles = Persona.bubbles(raw, "X")
        assertEquals(Persona.MAX_BUBBLES, bubbles.size)
        assertTrue(bubbles.last().endsWith("line 9"))
    }

    @Test
    fun styleIsStablePerPerson() {
        assertEquals(Persona.style("demo1"), Persona.style("demo1"))
        val styles = (1..40).map { Persona.style("demo$it") }.toSet()
        assertTrue("styles should vary between people", styles.size > 5)
    }

    @Test
    fun systemPromptCarriesProfileAndBoundaries() {
        val prompt = Persona.systemPrompt(
            profile(persona, "Aanya"),
            profile(me, "Arjun"),
            ZonedDateTime.of(2026, 10, 27, 21, 15, 0, 0, ZoneId.of("Asia/Kolkata")),
            afterDark = false,
        )
        listOf("Aanya", "24", "Pune", "Geese.", "Pottery", "Arjun", "Tuesday 9:15 PM", "non-explicit", "never send links", "be honest")
            .forEach { assertTrue(it, prompt.contains(it, ignoreCase = true)) }
        assertTrue(prompt.contains("You both like: Pottery, Hiking, Jazz"))
    }

    @Test
    fun timingStaysWithinBounds() {
        val r = Random(1)
        repeat(50) {
            assertTrue(Persona.typingMs("x".repeat(it * 20), AiPace.Natural, r) in 1_200..8_000)
            assertTrue(Persona.typingMs("x".repeat(it * 20), AiPace.Quick, r) in 400..1_500)
            assertTrue(Persona.readDelayMs(AiPace.Quick, r) < Persona.readDelayMs(AiPace.Natural, Random(it)))
        }
    }

    @Test
    fun aiMessagesMergeByTime() {
        val chat = StoredAiChat(
            myUid = me,
            personaUid = persona,
            messages = listOf(StoredAiMessage("ai_1", "hey you", 25_000, reaction = "🔥")),
        )
        val base = listOf(msg("3", me, "later", 30), msg("1", me, "first", 20))
        val merged = mergeAiMessages(base, chat)
        assertEquals(listOf("3", "ai_1", "1"), merged.map { it.id })
        assertEquals(persona, merged[1].senderId)
        assertEquals(mapOf(me to "🔥"), merged[1].reactions)
        assertEquals(base, mergeAiMessages(base, null))
    }

    @Test
    fun modelPickerPrefersSensibleDefaults() {
        val claude = listOf("claude-haiku-5-5", "claude-opus-5-5", "claude-sonnet-5-5", "claude-opus-4-8")
        assertEquals("claude-opus-5-5", ModelPicker.preferred(AiProvider.Claude, claude, ""))
        assertEquals("claude-haiku-5-5", ModelPicker.preferred(AiProvider.Claude, claude, "claude-haiku-5-5"))
        assertEquals("claude-opus-5-5", ModelPicker.chatModels(AiProvider.Claude, claude).first())

        val gemini = listOf("gemini-3.0-pro", "gemini-3.0-flash", "gemini-3.0-flash-lite", "gemini-3.1-flash-image", "text-embedding-004")
        assertEquals("gemini-3.0-flash", ModelPicker.preferred(AiProvider.Gemini, gemini, ""))
        assertFalse("text-embedding-004" in ModelPicker.chatModels(AiProvider.Gemini, gemini))

        val openai = listOf("gpt-5", "gpt-5-mini", "gpt-4.1-mini", "gpt-realtime", "whisper-1", "gpt-5-mini-2025-08-07")
        assertEquals("gpt-5-mini", ModelPicker.preferred(AiProvider.OpenAI, openai, ""))
        assertEquals(listOf("gpt-5", "gpt-5-mini", "gpt-4.1-mini"), ModelPicker.chatModels(AiProvider.OpenAI, openai))
    }

    @Test
    fun effortOnlyGoesToModelsThatTakeIt() {
        listOf("claude-opus-5-5", "claude-sonnet-5-5", "claude-haiku-5-5", "claude-fable-5-1", "claude-opus-4-8", "claude-sonnet-4-6")
            .forEach { assertTrue(it, ModelPicker.claudeSupportsEffort(it)) }
        listOf("claude-haiku-4-5", "claude-sonnet-4-5", "claude-3-5-sonnet-20241022")
            .forEach { assertFalse(it, ModelPicker.claudeSupportsEffort(it)) }
    }
}
