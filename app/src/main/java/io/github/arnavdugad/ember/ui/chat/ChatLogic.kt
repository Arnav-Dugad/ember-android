package io.github.arnavdugad.ember.ui.chat

import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** In-chat search. Runs on the loaded messages only; nothing is sent anywhere. */
internal object ChatSearch {
    /** What a message can be found by, or null for messages search skips. */
    fun searchableText(message: Message): String? {
        if (message.unsent) return null
        return when (message.type) {
            MessageType.Text -> message.text
            MessageType.Image -> message.text.ifBlank { null }
            MessageType.Game -> message.game?.prompt ?: message.text
            MessageType.Date -> message.date?.let { "${it.title}\n${it.detail}" } ?: message.text
            MessageType.Voice, MessageType.Event -> null
        }
    }

    /** Ids of the messages containing [query], ignoring case, in [messages]' order. A blank query finds nothing. */
    fun matches(messages: List<Message>, query: String): List<String> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return messages.filter { searchableText(it)?.contains(q, ignoreCase = true) == true }.map { it.id }
    }

    /** Every place [query] occurs in [text], ignoring case, for highlighting. */
    fun ranges(text: String, query: String): List<IntRange> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val found = mutableListOf<IntRange>()
        var from = 0
        while (true) {
            val at = text.indexOf(q, from, ignoreCase = true)
            if (at < 0) break
            found += at until at + q.length
            from = at + q.length
        }
        return found
    }
}

/** What "Copy" puts on the clipboard, or null when there's nothing worth copying. */
internal fun copyText(message: Message): String? {
    if (message.unsent) return null
    return when (message.type) {
        MessageType.Text -> message.text
        MessageType.Image -> message.text.ifBlank { null }
        MessageType.Game -> message.game?.prompt ?: message.text
        MessageType.Date -> message.date?.let { if (it.detail.isBlank()) it.title else "${it.title}\n${it.detail}" } ?: message.text
        MessageType.Voice, MessageType.Event -> null
    }
}

/** True when [current] falls on a different local day from the message shown above it. */
internal fun startsNewDay(previous: Instant?, current: Instant?, zone: ZoneId): Boolean {
    if (current == null) return false
    if (previous == null) return true
    return previous.atZone(zone).toLocalDate() != current.atZone(zone).toLocalDate()
}

/** "Today", "Yesterday", the weekday within the last week, "12 Oct" this year, "12 Oct 2025" before. */
internal fun dayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> "Today"
    day == today.minusDays(1) -> "Yesterday"
    day.isAfter(today.minusDays(7)) && !day.isAfter(today) -> day.format(DateTimeFormatter.ofPattern("EEEE"))
    day.year == today.year -> day.format(DateTimeFormatter.ofPattern("d MMM"))
    else -> day.format(DateTimeFormatter.ofPattern("d MMM yyyy"))
}

/**
 * One cycle of the typing dots, in ms. The longer they've been typing, the quicker it goes:
 * an easy 1.3 s at first, settling at 0.65 s after [TYPING_RAMP_MS].
 */
internal fun typingCycleMs(typingForMs: Long): Float {
    val t = (typingForMs.coerceAtLeast(0L).toFloat() / TYPING_RAMP_MS).coerceAtMost(1f)
    val eased = t * (2f - t)
    return 1_300f - 650f * eased
}

internal const val TYPING_RAMP_MS = 20_000L

/** How high dot [index] floats (0 to 1) at [phase] of the cycle: each rises and settles in turn. */
internal fun dotLift(phase: Float, index: Int): Float {
    val local = ((phase - index * 0.16f) % 1f + 1f) % 1f
    return if (local < 0.5f) kotlin.math.sin(local * 2f * Math.PI.toFloat()).coerceAtLeast(0f) else 0f
}

/** Their messages newer than [anchorId], the newest message when the reader scrolled away. [newestFirst] as the list shows it. */
internal fun unseenSince(newestFirst: List<Message>, anchorId: String?, otherUid: String?): Int {
    if (anchorId == null || otherUid == null) return 0
    var count = 0
    for (message in newestFirst) {
        if (message.id == anchorId) break
        if (message.senderId == otherUid && message.type != MessageType.Event) count++
    }
    return count
}

/** When I first wrote in this thread (events don't count), or null if I haven't yet. */
internal fun firstReplyAt(newestFirst: List<Message>, myUid: String): Instant? =
    newestFirst.lastOrNull { it.senderId == myUid && it.type != MessageType.Event }?.createdAt

/**
 * Whether a received photo starts out blurred: always with [blurAll]; otherwise only when it
 * arrived before I'd written anything, the unsolicited opener being the one worth guarding.
 */
internal fun veilsPhoto(sentAt: Instant?, firstReply: Instant?, blurAll: Boolean): Boolean =
    blurAll || firstReply == null || sentAt == null || sentAt.isBefore(firstReply)
