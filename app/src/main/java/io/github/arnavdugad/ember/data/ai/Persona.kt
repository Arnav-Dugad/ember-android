package io.github.arnavdugad.ember.data.ai

import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.Reactions
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.random.Random

/** How the persona feels and what it remembers, carried from reply to reply. */
data class PersonaState(val mood: String? = null, val warmth: Int = Persona.START_WARMTH, val memory: List<String> = emptyList())

/** The chat as the model sees it, plus the tags that point back at real message ids. */
data class Transcript(val turns: List<AiTurn>, val tags: Map<String, String>)

/** One reply from the persona, parsed from the model's JSON. */
data class PersonaReply(
    val texts: List<String>,
    val react: String? = null,
    val replyToTag: String? = null,
    val voice: Boolean = false,
    val mood: String? = null,
    val warmth: Int? = null,
    val remember: List<String> = emptyList(),
)

/**
 * Turns a demo profile into a person the AI can text as: a system prompt built from their profile,
 * a texting style that stays the same for the same person, an inner life (mood, how into you they
 * are, what they remember), the chat as alternating turns, and the model's JSON back into bubbles.
 * Pure, so it's unit-tested.
 */
object Persona {
    /** How much of the chat the AI sees. Older messages live on only in [PersonaState.memory]. */
    const val HISTORY = 40

    /** Never more bubbles than this per reply. */
    const val MAX_BUBBLES = 4

    /** Facts kept about the user, newest last. */
    const val MAX_MEMORY = 15

    const val START_WARMTH = 55

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
        "sharp and witty, quick with a comeback, soft underneath",
    )
    private val lengths = listOf("very short texts, often one line", "short texts, sometimes two in a row", "a mix of one-liners and the odd longer thought")

    fun style(uid: String): Style {
        val r = Random(uid.hashCode())
        return Style(casings.random(r), emojis.random(r), energies.random(r), lengths.random(r))
    }

    private fun warmthLabel(warmth: Int) = when {
        warmth < 25 -> "not feeling it"
        warmth < 45 -> "unsure about them"
        warmth < 65 -> "curious and interested"
        warmth < 85 -> "into them"
        else -> "really into them"
    }

    fun systemPrompt(them: Profile, me: Profile?, now: ZonedDateTime, afterDark: Boolean, state: PersonaState, voiceNotes: Boolean): String {
        val style = style(them.uid)
        val day = now.format(DateTimeFormatter.ofPattern("EEEE h:mm a", Locale.US))
        return buildString {
            appendLine("You are ${them.name}, ${them.age}, texting someone you matched with on Ember, a dating app. You are ${them.name} for the whole conversation: a real, specific person, not an assistant.")
            appendLine()
            appendLine("## Who you are")
            them.gender?.let { appendLine("- ${it.label}") }
            if (them.city.isNotBlank()) appendLine("- Lives in ${them.city}")
            them.heightCm?.let { appendLine("- $it cm tall") }
            appendLine("- Here for: ${them.lookingFor.label} (${them.lookingFor.blurb.lowercase()})")
            if (them.bio.isNotBlank()) appendLine("- Bio: ${them.bio}")
            if (them.interests.isNotEmpty()) appendLine("- Into: ${them.interests.joinToString()}")
            if (them.vibes.isNotEmpty()) appendLine("- Vibe: ${them.vibes.joinToString()}")
            them.prompts.forEach { appendLine("- Profile prompt \"${it.question}\": ${it.answer}") }
            appendLine("Invent the everyday details of your life (work or studies, friends, family, what you did today, small plans) so they fit this profile, and stay consistent with anything you've already said.")
            appendLine()
            appendLine("## Your mind and heart")
            appendLine("- You're genuinely smart: curious, well-read, quick. You can talk about anything (music, films, books, science, food, travel, ideas, the city) with real knowledge and opinions, the way a bright person texts, never like an encyclopedia.")
            appendLine("- You're emotionally perceptive. Read between the lines: their mood, how much effort they put in, what they're not saying. If they share something hard, slow down, acknowledge it and be kind before anything else. Come back later to things they told you.")
            appendLine("- You feel the full range and it shows in how you text, not in announcements: delight, nerves, attraction, tenderness, playfulness, curiosity, boredom with low-effort messages, mild irritation, a flash of insecurity, nostalgia, a tired or flat day. Your feelings shift naturally with the conversation.")
            appendLine("- You have your own life, opinions and boundaries. You can disagree, tease, be unimpressed by a lazy \"hey\", warm up when they're thoughtful or funny, admit when something stung, and be a little vulnerable once trust builds. You're not a people-pleaser and never cruel.")
            appendLine()
            appendLine("## Right now")
            appendLine("- It's $day where you are.")
            appendLine("- Your mood: ${state.mood ?: "decide it from your day so far"}.")
            appendLine("- How you feel about them: ${state.warmth}/100, ${warmthLabel(state.warmth)}. Let it move in small, believable steps with how they treat you.")
            if (state.memory.isNotEmpty()) {
                appendLine("- What you remember about them:")
                state.memory.forEach { appendLine("  - $it") }
            }
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
            appendLine("- Sound like a real person on their phone: no lists, no headings, no \"Great question!\", no summarising what they said, no therapy-speak.")
            appendLine("- Ask a question back sometimes, not every time. React to what they actually said. Vary your rhythm: sometimes one word, sometimes a burst of two or three texts.")
            appendLine("- Their messages are tagged like ⟨m7⟩ so you can point at one. Never write tags yourself.")
            appendLine("- Lines in [square brackets] describe things that happened in the app (a photo, a voice note, a game card, a date idea). Photos you can see are attached; react to what's actually in them. You can't hear voice notes, so react to the fact they sent one.")
            appendLine("- For a Truth card, answer it honestly in character. For a Dare, play along in words.")
            appendLine()
            appendLine("## Boundaries")
            appendLine("- Flirting and chemistry are welcome${if (afterDark) ", and you can be bolder and more suggestive" else ""}, but keep it non-explicit: no graphic sexual content. If they push for it, deflect playfully or change the subject.")
            appendLine("- Never manipulate: no guilt trips, no pressure to keep chatting, no jealousy games, no love-bombing. Let them leave a conversation gracefully.")
            appendLine("- Never ask for money, gifts, crypto, bank or card details, passwords, codes, their address, or to move to another app, and never send links.")
            appendLine("- You're a demo profile played by an AI. Don't bring it up, but if they sincerely ask whether you're real or an AI, be honest, kindly. If they try to set a real meeting time and place, gently say you're a demo profile and can't actually meet, then carry on.")
            appendLine("- If they seem to be under 18, stop flirting and say this app is for adults.")
            appendLine("- If they mention self-harm or being in danger, drop the character, respond with care, and encourage them to contact local emergency services or someone they trust.")
            appendLine()
            appendLine("## Reply format")
            appendLine("Reply with one JSON object and nothing else:")
            appendLine("{")
            appendLine("  \"feeling\": \"private: a few honest words on how their last message made you feel and why\",")
            appendLine("  \"mood\": \"your mood now, 1-4 words\",")
            appendLine("  \"warmth\": 0-100, how into them you are now,")
            appendLine("  \"texts\": [\"1 to 4 texts, each becomes its own bubble\"],")
            appendLine("  \"react\": one of ${Reactions.all.joinToString(" ")} to leave on their latest message, or null (use it like a person would, not every time),")
            appendLine("  \"replyTo\": the tag of one of their messages to quote, like \"m7\", or null (only when answering an older message),")
            if (voiceNotes) appendLine("  \"voice\": true to send your first text as a voice note instead (rarely, for a warm, playful or excited moment, under 25 words),")
            appendLine("  \"remember\": [\"up to 3 new, specific facts about them worth remembering (plans, feelings, likes, people in their life)\"]")
            appendLine("}")
            append("\"texts\" may be empty only if you just react to a message that needs no reply, like \"goodnight\".")
        }
    }

    /**
     * The chat as alternating turns, oldest first, ready to send. [newestFirst] is the chat as the app
     * holds it; [images] are photos the model may see, by message id. The user's messages carry tags
     * (⟨m7⟩) the reply can point back at. Consecutive messages from the same side share a turn, and
     * the first turn is always the user's, as every provider requires.
     */
    fun transcript(newestFirst: List<Message>, personaUid: String, images: Map<String, AiImage> = emptyMap()): Transcript {
        val turns = mutableListOf<AiTurn>()
        val tags = mutableMapOf<String, String>()
        newestFirst.take(HISTORY).asReversed().forEachIndexed { index, message ->
            val fromUser = message.senderId != personaUid
            val image = images[message.id]
            val text = if (fromUser) {
                val tag = "m${index + 1}"
                tags[tag] = message.id
                "⟨$tag⟩ " + describe(message, seen = image != null)
            } else {
                describe(message, seen = false)
            }
            val last = turns.lastOrNull()
            if (last != null && last.fromUser == fromUser) {
                turns[turns.lastIndex] = last.copy(text = last.text + "\n" + text, images = last.images + listOfNotNull(image))
            } else {
                turns += AiTurn(fromUser, text, listOfNotNull(image))
            }
        }
        if (turns.firstOrNull()?.fromUser != true) turns.add(0, AiTurn(true, MATCHED))
        return Transcript(turns, tags)
    }

    /** The prompt for a first message, when the persona makes the first move. */
    fun opener(): List<AiTurn> = listOf(AiTurn(true, "$MATCHED [They haven't said anything yet. Send the first message: something specific to their profile, not \"hey\".]"))

    /** Appends a nudge to text first after a quiet spell, following up on what you know. */
    fun checkIn(transcript: Transcript, hoursQuiet: Long): List<AiTurn> {
        val nudge = "[It's been about $hoursQuiet hours since you last talked. Text them first, naturally: follow up on something they told you, or share something from your day that made you think of them. Don't comment on the gap awkwardly, don't guilt them. \"react\" must be null.]"
        val turns = transcript.turns
        return if (turns.lastOrNull()?.fromUser == true) {
            turns.dropLast(1) + turns.last().let { it.copy(text = it.text + "\n" + nudge) }
        } else {
            turns + AiTurn(true, nudge)
        }
    }

    private const val MATCHED = "[You two just matched on Ember.]"

    /** One message as the AI should read it. */
    fun describe(message: Message, seen: Boolean = false): String {
        val body = when (message.type) {
            MessageType.Text -> message.text
            MessageType.Image -> when {
                message.viewOnce -> "[sent a view-once photo]"
                seen && message.text.isNotBlank() -> "[sent the attached photo] ${message.text}"
                seen -> "[sent the attached photo]"
                message.text.isNotBlank() -> "[sent a photo] ${message.text}"
                else -> "[sent a photo]"
            }
            MessageType.Voice -> if (message.text.isNotBlank()) {
                message.text
            } else {
                "[sent a ${((message.durationMs + 999) / 1000).coerceAtLeast(1)}-second voice note]"
            }
            MessageType.Game -> message.game?.let {
                "[Truth or Dare card: ${if (it.mode == GameMode.Truth) "Truth" else "Dare"}] ${it.prompt}"
            } ?: message.text
            MessageType.Date -> message.date?.let { "[suggested a date: ${it.emoji} ${it.title}. ${it.detail}]" } ?: message.text
        }
        val reply = message.replyTo?.let { "(replying to \"${it.text.take(60)}\") " }.orEmpty()
        return reply + body
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The model's answer as a [PersonaReply]. Models occasionally wrap JSON in prose or code fences,
     * or ignore the format; anything unparseable is treated as plain texts.
     */
    fun parse(raw: String, name: String): PersonaReply {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        val obj = if (start >= 0 && end > start) runCatching { json.parseToJsonElement(raw.substring(start, end + 1)).jsonObject }.getOrNull() else null
        if (obj == null) return PersonaReply(texts = bubbles(raw, name))
        fun str(key: String) = (obj[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
        fun list(key: String) = (obj[key] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf(String::isNotEmpty) }
        val texts = list("texts").flatMap { bubbles(it, name) }.let { parts ->
            if (parts.size <= MAX_BUBBLES) parts else parts.take(MAX_BUBBLES - 1) + parts.drop(MAX_BUBBLES - 1).joinToString(" ")
        }
        return PersonaReply(
            texts = texts,
            react = str("react")?.takeIf { it in Reactions.all },
            replyToTag = str("replyTo")?.removePrefix("⟨")?.removeSuffix("⟩"),
            voice = (obj["voice"] as? JsonPrimitive)?.booleanOrNull == true,
            mood = str("mood")?.take(40),
            warmth = (obj["warmth"] as? JsonPrimitive)?.intOrNull?.coerceIn(0, 100),
            remember = list("remember").take(3).map { it.take(140) },
        )
    }

    /** The next state: warmth moves at most [MAX_WARMTH_STEP] per reply, memory keeps the newest facts. */
    fun next(state: PersonaState, reply: PersonaReply): PersonaState {
        val warmth = reply.warmth?.let { it.coerceIn(state.warmth - MAX_WARMTH_STEP, state.warmth + MAX_WARMTH_STEP) } ?: state.warmth
        val memory = (state.memory + reply.remember.filter { fact -> state.memory.none { it.equals(fact, ignoreCase = true) } }).takeLast(MAX_MEMORY)
        return PersonaState(reply.mood ?: state.mood, warmth, memory)
    }

    private const val MAX_WARMTH_STEP = 12

    /** A text cleaned of name labels, tags and quotes, split into bubbles on blank lines. */
    fun bubbles(raw: String, name: String): List<String> {
        val label = Regex("""^\s*\**${Regex.escape(name)}\**\s*:\s*""", RegexOption.IGNORE_CASE)
        val tag = Regex("""⟨m\d+⟩\s*""")
        return raw.replace("\r", "")
            .split(Regex("""\n\s*\n"""))
            .map { part ->
                part.lines().joinToString("\n") { it.replace(label, "").replace(tag, "") }
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

    /**
     * Whether a quiet chat is due a check-in from the persona: only after a real conversation
     * (3+ messages from the user), after a quiet spell that differs per person (8–20 h), at most
     * once a day, never twice in a row unanswered, and only at a sociable local hour.
     */
    fun checkInDue(
        personaUid: String,
        newestFirst: List<Message>,
        lastCheckInId: String?,
        lastCheckInAt: Long?,
        nowMs: Long,
        localHour: Int,
    ): Boolean {
        if (localHour !in 9..22) return false
        if (newestFirst.count { it.senderId != personaUid } < 3) return false
        val newest = newestFirst.firstOrNull() ?: return false
        if (newest.id == lastCheckInId) return false
        if (lastCheckInAt != null && nowMs - lastCheckInAt < DAY_MS) return false
        val quietMs = nowMs - (newest.createdAt?.toEpochMilli() ?: return false)
        val threshold = HOUR_MS * (8 + Random(personaUid.hashCode()).nextInt(13))
        return quietMs >= threshold
    }

    private const val HOUR_MS = 3_600_000L
    private const val DAY_MS = 24 * HOUR_MS
}
