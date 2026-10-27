package io.github.arnavdugad.ember.data.ai

import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Profile
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/**
 * Turns a demo profile into a character the AI can text as: a system prompt built from their
 * profile, a texting style that stays the same for the same person, the chat as alternating turns,
 * and the reply split back into separate messages. Pure, so it's unit-tested.
 */
object Persona {
    /** How much of the chat the AI sees. Older messages fall out of its memory. */
    const val HISTORY = 40

    /** Never more bubbles than this per reply. */
    const val MAX_BUBBLES = 4

    /** A texting style, fixed per person so they don't change character between chats. */
    data class Style(val casing: String, val emoji: String, val energy: String, val length: String)

    private val casings = listOf(
        "mostly lowercase, light punctuation",
        "normal capitalisation, relaxed punctuation",
        "lowercase, and you skip full stops at the end",
    )
    private val emojis = listOf("almost never use emoji", "an occasional emoji, at most one per message", "emoji now and then when it fits")
    private val energies = listOf(
        "dry, deadpan humour and gentle teasing",
        "warm, bubbly and openly curious",
        "playful and flirty, a little cheeky",
        "calm and thoughtful, with a quiet sense of humour",
        "chaotic, spontaneous and enthusiastic",
    )
    private val lengths = listOf("very short texts, often one line", "short texts, sometimes two in a row", "a mix of one-liners and the odd longer thought")

    fun style(uid: String): Style {
        val r = Random(uid.hashCode())
        return Style(casings.random(r), emojis.random(r), energies.random(r), lengths.random(r))
    }

    fun systemPrompt(them: Profile, me: Profile?, now: ZonedDateTime, afterDark: Boolean): String {
        val style = style(them.uid)
        val day = now.format(DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.US))
        return buildString {
            appendLine("You are ${them.name}, ${them.age}, texting someone you just matched with on Ember, a dating app. Stay in character as ${them.name} for the whole conversation.")
            appendLine()
            appendLine("## Who you are")
            them.gender?.let { appendLine("- ${it.label}") }
            if (them.city.isNotBlank()) appendLine("- Lives in ${them.city}")
            them.heightCm?.let { appendLine("- ${it} cm tall") }
            appendLine("- Here for: ${them.lookingFor.label} (${them.lookingFor.blurb.lowercase()})")
            if (them.bio.isNotBlank()) appendLine("- Bio: ${them.bio}")
            if (them.interests.isNotEmpty()) appendLine("- Into: ${them.interests.joinToString()}")
            if (them.vibes.isNotEmpty()) appendLine("- Vibe: ${them.vibes.joinToString()}")
            them.prompts.forEach { appendLine("- Profile prompt \"${it.question}\": ${it.answer}") }
            appendLine("Invent small everyday details (work, friends, what you did today) that fit this profile, and stay consistent with them once said.")
            appendLine()
            appendLine("## Who you're talking to")
            if (me == null) {
                appendLine("- Someone you matched with. You don't know much yet.")
            } else {
                appendLine("- ${me.name.ifBlank { "Your match" }}, ${me.age}${if (me.city.isNotBlank()) ", ${me.city}" else ""}")
                if (me.bio.isNotBlank()) appendLine("- Their bio: ${me.bio}")
                if (me.interests.isNotEmpty()) appendLine("- Their interests: ${me.interests.joinToString()}")
                me.prompts.take(3).forEach { appendLine("- Their prompt \"${it.question}\": ${it.answer}") }
                val shared = them.interests.intersect(me.interests.toSet())
                if (shared.isNotEmpty()) appendLine("- You both like: ${shared.joinToString()}")
            }
            appendLine()
            appendLine("## How you text")
            appendLine("- ${style.casing}; ${style.emoji}; ${style.energy}; ${style.length}.")
            appendLine("- Sound like a real person on their phone, not an assistant: no lists, no headings, no \"Great question!\", no summarising what they said.")
            appendLine("- Write one to three separate texts. Put a blank line between texts; each becomes its own bubble.")
            appendLine("- Have opinions and preferences. Ask a question back sometimes, not every time. React to what they actually said.")
            appendLine("- Never write your own name as a label and never put your texts in quotes.")
            appendLine("- Lines in [square brackets] describe things that happened in the app (a photo, a voice note, a game card). Respond to them naturally; you can't actually see photos or hear voice notes, so react to the fact they sent one.")
            appendLine("- For a Truth card, answer it honestly in character. For a Dare, play along in words.")
            appendLine("- It's $day where you are.")
            appendLine()
            appendLine("## Boundaries")
            appendLine("- Flirting and chemistry are welcome${if (afterDark) ", and you can be bolder and more suggestive" else ""}, but keep it non-explicit: no graphic sexual content. If they push for it, deflect playfully or change the subject.")
            appendLine("- Never ask for money, gifts, crypto, bank or card details, passwords, codes, their address, or to move to another app, and never send links.")
            appendLine("- You're a demo profile played by an AI. Don't bring it up yourself, but if they sincerely ask whether you're real or an AI, be honest. If they try to set a real meeting time and place, gently say you're a demo profile and can't actually meet, then carry on.")
            appendLine("- If they seem to be under 18, stop flirting and say this app is for adults.")
            appendLine("- If they mention self-harm or being in danger, drop the character, respond with care, and encourage them to contact local emergency services or someone they trust.")
            append("- Reply only with your texts.")
        }
    }

    /**
     * The chat as alternating turns, oldest first, ready to send. [newestFirst] is the chat as the
     * app holds it. Consecutive messages from the same side are joined into one turn, and the
     * first turn is always the user's, as every provider requires.
     */
    fun transcript(newestFirst: List<Message>, personaUid: String): List<AiTurn> {
        val turns = mutableListOf<AiTurn>()
        newestFirst.take(HISTORY).asReversed().forEach { message ->
            val fromUser = message.senderId != personaUid
            val text = describe(message)
            val last = turns.lastOrNull()
            if (last != null && last.fromUser == fromUser) {
                turns[turns.lastIndex] = last.copy(text = last.text + "\n" + text)
            } else {
                turns += AiTurn(fromUser, text)
            }
        }
        if (turns.firstOrNull()?.fromUser != true) turns.add(0, AiTurn(true, MATCHED))
        return turns
    }

    /** The prompt for a first message, when the persona makes the first move. */
    fun opener(): List<AiTurn> = listOf(AiTurn(true, "$MATCHED [They haven't said anything yet. Send the first message: something specific to their profile, not \"hey\".]"))

    private const val MATCHED = "[You two just matched on Ember.]"

    /** One message as the AI should read it. */
    fun describe(message: Message): String {
        val body = when (message.type) {
            MessageType.Text -> message.text
            MessageType.Image -> when {
                message.viewOnce -> "[sent a view-once photo]"
                message.text.isNotBlank() -> "[sent a photo] ${message.text}"
                else -> "[sent a photo]"
            }
            MessageType.Voice -> "[sent a ${((message.durationMs + 999) / 1000).coerceAtLeast(1)}-second voice note]"
            MessageType.Game -> message.game?.let {
                "[Truth or Dare card: ${if (it.mode == GameMode.Truth) "Truth" else "Dare"}] ${it.prompt}"
            } ?: message.text
            MessageType.Date -> message.date?.let { "[suggested a date: ${it.emoji} ${it.title}. ${it.detail}]" } ?: message.text
        }
        val reply = message.replyTo?.let { "(replying to \"${it.text.take(60)}\") " }.orEmpty()
        return reply + body
    }

    /** The reply split into bubbles, with assistant-isms and name labels cleaned off. */
    fun bubbles(raw: String, name: String): List<String> {
        val label = Regex("""^\s*\**${Regex.escape(name)}\**\s*:\s*""", RegexOption.IGNORE_CASE)
        return raw.replace("\r", "")
            .split(Regex("""\n\s*\n"""))
            .map { part ->
                part.lines().joinToString("\n") { it.replace(label, "") }
                    .trim()
                    .removeSurrounding("\"")
                    .trim()
            }
            .filter { it.isNotBlank() }
            .let { parts ->
                // Too many pieces: keep the first few, fold the rest into the last bubble.
                if (parts.size <= MAX_BUBBLES) parts else parts.take(MAX_BUBBLES - 1) + parts.drop(MAX_BUBBLES - 1).joinToString(" ")
            }
            .map { it.take(MAX_BUBBLE_CHARS) }
    }

    private const val MAX_BUBBLE_CHARS = 1_000

    /** How long they "read" before typing starts. */
    fun readDelayMs(pace: AiPace, random: Random): Long = when (pace) {
        AiPace.Natural -> random.nextLong(1_500, 6_000)
        AiPace.Quick -> random.nextLong(300, 900)
    }

    /** How long the typing dots show for a bubble: roughly thumb speed, within sane bounds. */
    fun typingMs(text: String, pace: AiPace, random: Random): Long = when (pace) {
        AiPace.Natural -> (text.length * 55L + random.nextLong(400, 1_200)).coerceIn(1_200, 8_000)
        AiPace.Quick -> (text.length * 12L).coerceIn(400, 1_500)
    }

    /** The pause between two bubbles of the same reply. */
    fun gapMs(pace: AiPace, random: Random): Long = when (pace) {
        AiPace.Natural -> random.nextLong(500, 1_600)
        AiPace.Quick -> random.nextLong(150, 400)
    }
}
