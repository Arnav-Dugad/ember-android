package io.github.arnavdugad.ember.data.ai

import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The user's own AI, helping them write: reply ideas in a chat ("wingman") and bio or prompt
 * drafts in the profile editor. It only ever suggests; the user edits and sends. Needs a saved key
 * but not demo replies switched on.
 */
@Singleton
class AiAssist @Inject constructor(
    private val settings: AiSettingsRepository,
    private val client: AiClient,
    /** The app's repository, so demo chats include the persona's side. */
    private val backend: MatchRepository,
    private val profiles: ProfileRepository,
) {
    /** Whether a usable key is saved. */
    suspend fun available(): Boolean = settings.current().usable

    /** Three replies the user could send next in [matchId], in their own voice. */
    suspend fun replies(matchId: String): List<String> {
        val match = backend.observeMatch(matchId).first() ?: return emptyList()
        val messages = backend.observeMessages(matchId).first()
        val me = profiles.observeMyProfile().first()
        val them = runCatching { profiles.getProfile(match.otherUid) }.getOrNull()
        val chat = messages.take(Persona.HISTORY).asReversed().joinToString("\n") { m ->
            (if (m.senderId == match.otherUid) match.otherName else "Me") + ": " + Persona.describe(m)
        }.ifBlank { "(no messages yet)" }
        val system = buildString {
            appendLine("You're a warm, witty, emotionally intelligent dating coach helping the user text their match on Ember, a dating app.")
            appendLine("Suggest three different next messages the user could send, written in the user's own voice: natural, specific to the conversation and the match's profile, never cheesy pickup lines, never pushy.")
            appendLine("Make them distinct: one playful, one curious (a good question), one warm or bold. Keep each under 200 characters. Non-explicit.")
            appendLine("If the match seems upset or shared something serious, all three should be kind and supportive.")
            appendLine()
            appendLine("The user: ${describe(me)}")
            appendLine("Their match: ${describe(them) ?: match.otherName}")
            appendLine()
            append("Reply with JSON only: {\"replies\": [\"...\", \"...\", \"...\"]}")
        }
        return ask(system, "The conversation so far, oldest first:\n$chat")
    }

    /** Three bio drafts from what the user has filled in so far. */
    suspend fun bios(draft: ProfileDraft): List<String> {
        val system = """
            You write dating-profile bios that sound like a real, specific person: confident, warm, a little playful, never cringe or generic.
            Write three different bios for the user from their details. Each under 280 characters, first person, no hashtags, no lists, at most one emoji, non-explicit.
            Keep any facts to what they've given; don't invent jobs or achievements. If they wrote a bio already, offer improved takes on it.
            Reply with JSON only: {"replies": ["...", "...", "..."]}
        """.trimIndent()
        return ask(system, draftDetails(draft))
    }

    /** Three answers to a profile prompt, in the user's voice. */
    suspend fun promptAnswers(question: String, draft: ProfileDraft): List<String> {
        val system = """
            You help someone answer a dating-profile prompt so it's memorable and makes it easy for others to start a conversation.
            Write three different answers in first person, each under 150 characters: specific, funny or charming, never generic, non-explicit.
            Use only details they've given or harmless everyday ones.
            Reply with JSON only: {"replies": ["...", "...", "..."]}
        """.trimIndent()
        return ask(system, "Prompt: \"$question\"\n\n${draftDetails(draft)}")
    }

    private suspend fun ask(system: String, prompt: String): List<String> {
        val credentials = settings.credentials(forDemoReplies = false)
            ?: throw AiException("Add an AI key in Settings → AI chat first.", AiException.Kind.Setup)
        val result = client.complete(credentials, system, listOf(AiTurn(true, prompt)), jsonReply = true, effort = "low")
        settings.recordUse(credentials.provider, credentials.model, result.usage, reply = false)
        return parseList(result.text)
    }

    private fun describe(profile: Profile?): String? = profile?.let { p ->
        listOfNotNull(
            "${p.name}, ${p.age}",
            p.city.ifBlank { null },
            p.bio.ifBlank { null }?.let { "bio: $it" },
            p.interests.takeIf { it.isNotEmpty() }?.let { "into ${it.joinToString()}" },
            p.prompts.takeIf { it.isNotEmpty() }?.joinToString("; ") { "\"${it.question}\": ${it.answer}" },
        ).joinToString(" · ")
    }

    private fun draftDetails(d: ProfileDraft): String = buildString {
        appendLine("Name: ${d.name.ifBlank { "(not given)" }}")
        d.gender?.let { appendLine("Gender: ${it.label}") }
        if (d.city.isNotBlank()) appendLine("City: ${d.city}")
        appendLine("Looking for: ${d.lookingFor.label}")
        if (d.interests.isNotEmpty()) appendLine("Interests: ${d.interests.joinToString()}")
        if (d.vibes.isNotEmpty()) appendLine("Vibes: ${d.vibes.joinToString()}")
        if (d.bio.isNotBlank()) appendLine("Current bio: ${d.bio}")
        d.prompts.forEach { appendLine("Prompt \"${it.question}\": ${it.answer}") }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        /** The `replies` array, tolerating prose or code fences around it; falls back to lines. */
        fun parseList(raw: String): List<String> {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            val fromJson = if (start >= 0 && end > start) {
                runCatching {
                    (json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject["replies"] as? JsonArray).orEmpty()
                        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
                }.getOrNull()
            } else {
                null
            }
            val list = fromJson ?: raw.lines().map { it.trim().trimStart('-', '*', '•', ' ').removeSurrounding("\"") }.filter { it.length > 3 }
            return list.distinct().take(3)
        }
    }
}
