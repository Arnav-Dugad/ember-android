package io.github.arnavdugad.ember.data.model

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/**
 * "Online now" without a server: while the app is in the foreground it writes `lastActiveAt`
 * about every [HEARTBEAT], and anyone seen within [ONLINE_WINDOW] counts as online.
 *
 * With "Show when I'm active" off, `lastActiveAt` is written at most once per UTC day and only
 * ever as that day's midnight (the rules insist), so it says which day, never what time.
 */
object Presence {
    val HEARTBEAT: Duration = Duration.ofMinutes(5)

    /** Two missed beats and a little slack before someone stops being "online". */
    val ONLINE_WINDOW: Duration = Duration.ofMinutes(11)

    /** Foregrounding the app again this soon after a write doesn't write again. */
    val MIN_GAP: Duration = Duration.ofMinutes(2)

    /** "Active 3d ago" is the oldest label; past a week it would only discourage. */
    private val LABEL_LIMIT: Duration = Duration.ofDays(7)

    fun isOnline(lastActive: Instant?, now: Instant): Boolean {
        if (lastActive == null) return false
        val ago = Duration.between(lastActive, now)
        // A timestamp a little ahead of this phone's clock is still "now".
        return ago < ONLINE_WINDOW && ago > Duration.ofMinutes(-5)
    }

    fun label(lastActive: Instant?, now: Instant): String? {
        if (lastActive == null) return null
        if (isOnline(lastActive, now)) return "Active now"
        val ago = Duration.between(lastActive, now)
        return when {
            ago.isNegative || ago >= LABEL_LIMIT -> null
            ago.toHours() < 1 -> "Active ${ago.toMinutes()}m ago"
            ago.toDays() < 1 -> "Active ${ago.toHours()}h ago"
            else -> "Active ${ago.toDays()}d ago"
        }
    }

    /** Midnight UTC of [now]'s day: the only `lastActiveAt` a hidden profile writes. */
    fun dayStart(now: Instant): Instant = now.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC)

    /**
     * What the heartbeat should write now, or null to skip. [sinceLastWrite] is measured on this
     * device's monotonic clock (null when nothing was written this session).
     */
    fun nextStamp(hidden: Boolean, lastActive: Instant?, sinceLastWrite: Duration?, now: Instant): ActivityStamp? = when {
        hidden -> if (lastActive == dayStart(now)) null else ActivityStamp.At(dayStart(now))
        sinceLastWrite != null && sinceLastWrite < MIN_GAP -> null
        else -> ActivityStamp.ServerTime
    }
}

/** A `lastActiveAt` write: the server's clock, or a fixed instant (a hidden profile's midnight). */
sealed interface ActivityStamp {
    data object ServerTime : ActivityStamp

    data class At(val instant: Instant) : ActivityStamp
}

/** Spotlight: once per UTC day, a profile goes to the front of everyone's deck for half an hour. */
object Spotlight {
    val DURATION: Duration = Duration.ofMinutes(30)

    /**
     * Written a little short of [DURATION]: the rules cap it at the server's clock + 30 min, and a
     * phone whose clock runs slightly fast must not be refused.
     */
    val CLOCK_MARGIN: Duration = Duration.ofSeconds(30)

    /** The day's counter doc in `users/{uid}/limits`, next to the report counters. */
    fun counterId(now: Instant): String = "spotlight_" + now.atOffset(ZoneOffset.UTC).toLocalDate()

    fun until(now: Instant): Instant = now.plus(DURATION).minus(CLOCK_MARGIN)

    /** 1 at the start, 0 when it ends. */
    fun remainingFraction(until: Instant?, now: Instant): Float {
        if (until == null) return 0f
        val left = Duration.between(now, until).toMillis().toFloat()
        return (left / DURATION.toMillis()).coerceIn(0f, 1f)
    }

    /** "29:41", for the countdown. */
    fun remainingLabel(until: Instant, now: Instant): String {
        val seconds = Duration.between(now, until).seconds.coerceAtLeast(0)
        return "${seconds / 60}:" + (seconds % 60).toString().padStart(2, '0')
    }
}

/** People in the Spotlight first (soonest to end first), everyone else in the order they came. */
fun List<Profile>.spotlightFirst(now: Instant): List<Profile> {
    val (lit, rest) = partition { it.isSpotlit(now) }
    return lit.sortedBy { it.spotlightUntil } + rest
}
