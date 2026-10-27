package io.github.arnavdugad.ember.data.model

import androidx.compose.runtime.Immutable
import java.io.File
import java.time.Instant
import java.time.LocalDate

enum class Gender(val key: String, val label: String, val plural: String) {
    Woman("woman", "Woman", "Women"),
    Man("man", "Man", "Men"),
    NonBinary("nonbinary", "Non-binary", "Non-binary people"),
    ;

    companion object {
        fun of(key: String?): Gender? = entries.firstOrNull { it.key == key }
    }
}

enum class LookingFor(val key: String, val label: String, val emoji: String, val blurb: String) {
    Casual("casual", "Something casual", "🔥", "Fun, flirty, no pressure"),
    Dating("dating", "Dating", "🍷", "See where it goes"),
    Friends("friends", "New friends", "🤝", "Good company first"),
    Exploring("exploring", "Open to exploring", "✨", "Curious and open-minded"),
    ;

    /** Intents surfaced by After Dark mode. */
    val isAdventurous: Boolean get() = this == Casual || this == Exploring

    companion object {
        fun of(key: String?): LookingFor = entries.firstOrNull { it.key == key } ?: Dating
    }
}

@Immutable
data class Photo(
    val id: String,
    val url: String,
    val path: String = "",
    val blurHash: String? = null,
)

@Immutable
data class ProfilePrompt(val id: String, val question: String, val answer: String)

@Immutable
data class Profile(
    val uid: String,
    val name: String,
    val age: Int,
    val gender: Gender?,
    val interestedIn: Set<Gender>,
    val bio: String,
    val photos: List<Photo>,
    val prompts: List<ProfilePrompt>,
    val interests: List<String>,
    val vibes: List<String>,
    val lookingFor: LookingFor,
    val heightCm: Int?,
    val city: String,
    val distanceKm: Int? = null,
    val afterDark: Boolean = false,
    val privateAlbumCount: Int = 0,
    val isDemo: Boolean = false,
    val onboarded: Boolean = false,
    val lat: Double? = null,
    val lng: Double? = null,
    /** Privacy: show others only the city, never "N km away". */
    val hideDistance: Boolean = false,
    /** Privacy: left out of everyone's discovery deck. */
    val paused: Boolean = false,
    /** "Free tonight" until this moment; null or past means not. */
    val freeTonightUntil: Instant? = null,
    /** Length of their voice intro, or null when they haven't recorded one. */
    val voiceDurationMs: Int? = null,
    /** Last heartbeat. Only ever a UTC midnight (the day, nothing finer) while [hideActivity] is on. */
    val lastActiveAt: Instant? = null,
    /** Privacy: don't show "Active now" or "Active 2h ago" to anyone. */
    val hideActivity: Boolean = false,
    /** Spotlight: shown first in everyone's deck until this moment; null or past means not. */
    val spotlightUntil: Instant? = null,
) {
    val firstPhoto: Photo? get() = photos.firstOrNull()

    /** The distance to show on cards and profiles: null when this person hides it. */
    val shownDistanceKm: Int? get() = if (hideDistance) null else distanceKm

    val hasVoice: Boolean get() = voiceDurationMs != null

    fun isFreeTonight(now: Instant = Instant.now()): Boolean = freeTonightUntil?.isAfter(now) == true

    fun isSpotlit(now: Instant = Instant.now()): Boolean = spotlightUntil?.isAfter(now) == true

    /** Online now, as far as [viewerHidesActivity] may see: hiding your own activity hides everyone's. */
    fun isOnline(viewerHidesActivity: Boolean = false, now: Instant = Instant.now()): Boolean =
        !hideActivity && !viewerHidesActivity && Presence.isOnline(lastActiveAt, now)

    /** "Active now" or "Active 2h ago"; null when either side hides activity, or it was long ago. */
    fun activityLabel(viewerHidesActivity: Boolean = false, now: Instant = Instant.now()): String? =
        if (hideActivity || viewerHidesActivity) null else Presence.label(lastActiveAt, now)
}

/** A named point at city precision (two decimals), from the city picker or travel mode. */
@Immutable
data class Place(val name: String, val lat: Double, val lng: Double)

/** Someone who liked you ("Likes you"). Their profile is fetched separately by id. */
@Immutable
data class Admirer(val uid: String, val superLike: Boolean, val likedAt: Instant?, val note: LikeNote? = null)

enum class LikeNoteKind(val key: String) {
    Prompt("prompt"),
    Photo("photo"),
    ;

    companion object {
        fun of(key: String?): LikeNoteKind? = entries.firstOrNull { it.key == key }
    }
}

/**
 * A like aimed at one prompt or photo, with an optional comment. It rides on the like itself
 * (`swipes` and `likedBy`) and becomes the liker's first message once they match.
 */
@Immutable
data class LikeNote(val kind: LikeNoteKind, val ref: String, val text: String) {
    /** The first chat message: the comment, or a heart when there isn't one. */
    val messageText: String get() = text.trim().ifEmpty { "❤️" }

    /** Quotes [owner]'s prompt or photo in that message; null once it's gone from their profile. */
    fun quote(owner: Profile): ReplyRef? = when (kind) {
        LikeNoteKind.Prompt -> owner.prompts.firstOrNull { it.id == ref }?.let { p ->
            ReplyRef("profile-prompt-$ref", owner.uid, excerpt("${p.question}: ${p.answer}", ReplyRef.MAX_TEXT), MessageType.Text)
        }
        LikeNoteKind.Photo -> owner.photos.firstOrNull { it.id == ref }?.let { ReplyRef("profile-photo-$ref", owner.uid, "Photo", MessageType.Image) }
    }

    /** One string, for a navigation result. */
    fun encode(): String = listOf(kind.key, ref, text).joinToString(SEPARATOR)

    companion object {
        /** The rules cap the comment at 140 chars. */
        const val MAX_TEXT = 140

        /** Prompt and photo ids are short tokens; the rules check the same pattern. */
        private val REF = Regex("^[A-Za-z0-9_-]{1,128}$")
        private const val SEPARATOR = "\u001F"

        fun isValidRef(ref: String): Boolean = REF.matches(ref)

        /** A note the rules will accept, or null. The comment is trimmed and cut to [MAX_TEXT]. */
        fun of(kind: LikeNoteKind, ref: String, text: String): LikeNote? =
            if (isValidRef(ref)) LikeNote(kind, ref, excerpt(text.trim(), MAX_TEXT)) else null

        fun decode(encoded: String?): LikeNote? {
            val parts = encoded?.split(SEPARATOR, limit = 3)?.takeIf { it.size == 3 } ?: return null
            return LikeNoteKind.of(parts[0])?.let { of(it, parts[1], parts[2]) }
        }

        /** At most [max] chars, never cutting an emoji (a surrogate pair) in half. */
        internal fun excerpt(text: String, max: Int): String {
            if (text.length <= max) return text
            val cut = text.take(max)
            return if (cut.last().isHighSurrogate()) cut.dropLast(1) else cut
        }
    }
}

/** Boundaries and turn-ons: only ever shown to people you've matched with. */
@Immutable
data class MatchOnlyDetails(val boundaries: List<String> = emptyList(), val turnOns: List<String> = emptyList())

/** Everything the profile editor can change. Server-owned fields (age, isDemo) are absent on purpose. */
data class ProfileDraft(
    val name: String = "",
    val gender: Gender? = null,
    val interestedIn: Set<Gender> = emptySet(),
    val bio: String = "",
    val photos: List<Photo> = emptyList(),
    val prompts: List<ProfilePrompt> = emptyList(),
    val interests: List<String> = emptyList(),
    val vibes: List<String> = emptyList(),
    val lookingFor: LookingFor = LookingFor.Dating,
    val heightCm: Int? = null,
    val city: String = "",
    val lat: Double? = null,
    val lng: Double? = null,
    val afterDark: Boolean = false,
    val voiceDurationMs: Int? = null,
    val matchOnly: MatchOnlyDetails = MatchOnlyDetails(),
)

fun Profile.toDraft(matchOnly: MatchOnlyDetails?) = ProfileDraft(
    name = name,
    gender = gender,
    interestedIn = interestedIn,
    bio = bio,
    photos = photos,
    prompts = prompts,
    interests = interests,
    vibes = vibes,
    lookingFor = lookingFor,
    heightCm = heightCm,
    city = city,
    lat = lat,
    lng = lng,
    afterDark = afterDark,
    voiceDurationMs = voiceDurationMs,
    matchOnly = matchOnly ?: MatchOnlyDetails(),
)

enum class SwipeDirection(val key: String) { Like("like"), Nope("nope"), Super("super") }

@Immutable
data class LastMessage(val text: String, val senderId: String, val type: MessageType, val sentAt: Instant?)

@Immutable
data class Match(
    val id: String,
    val otherUid: String,
    val otherName: String,
    val otherPhotoUrl: String?,
    val otherBlurHash: String?,
    val otherIsDemo: Boolean,
    val createdAt: Instant?,
    val lastMessage: LastMessage?,
    val myLastRead: Instant?,
    val otherLastRead: Instant?,
    val superLike: Boolean,
) {
    val hasUnread: Boolean
        get() {
            val last = lastMessage ?: return false
            if (last.senderId != otherUid) return false
            val sent = last.sentAt ?: return false
            return myLastRead == null || sent.isAfter(myLastRead)
        }

    /** The signed-in user's uid: a match id is the two uids, sorted, joined by `_`. */
    val myUid: String get() = id.split('_').firstOrNull { it != otherUid }.orEmpty()
}

enum class MessageType(val key: String) {
    Text("text"),
    Image("image"),
    Game("game"),
    Voice("voice"),
    Date("date"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Text
    }
}

enum class GameMode(val key: String, val label: String) {
    Truth("truth", "Truth"),
    Dare("dare", "Dare"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Truth
    }
}

@Immutable
data class GameCard(val mode: GameMode, val prompt: String)

/** A date idea from the planner, sent as a card. */
@Immutable
data class DateIdea(val title: String, val detail: String, val emoji: String)

/** The quoted message a reply points at. [text] is a short excerpt. */
@Immutable
data class ReplyRef(val id: String, val senderId: String, val text: String, val type: MessageType) {
    companion object {
        /** The rules cap the excerpt at 120 chars. */
        const val MAX_TEXT = 120

        fun of(message: Message): ReplyRef {
            // Never cut an emoji (a surrogate pair) in half.
            val excerpt = message.summary.take(MAX_TEXT).let { if (it.lastOrNull()?.isHighSurrogate() == true) it.dropLast(1) else it }
            return ReplyRef(message.id, message.senderId, excerpt, message.type)
        }
    }
}

/** A finished recording, ready to send. */
class VoiceClip(val file: File, val durationMs: Long)

/** The reactions a message can carry. The security rules hold the same list. */
object Reactions {
    val all = listOf("🔥", "❤️", "😂", "😮", "👍")
}

@Immutable
data class Message(
    val id: String,
    val senderId: String,
    val type: MessageType,
    val text: String,
    val imagePath: String? = null,
    val imageUrl: String? = null,
    val imageBlurHash: String? = null,
    val game: GameCard? = null,
    val createdAt: Instant?,
    val pending: Boolean = false,
    /** uid -> one of [Reactions.all]. */
    val reactions: Map<String, String> = emptyMap(),
    val replyTo: ReplyRef? = null,
    val viewOnce: Boolean = false,
    val viewedAt: Instant? = null,
    val audioId: String? = null,
    val durationMs: Long = 0,
    val date: DateIdea? = null,
) {
    /** One line describing the message, for quotes, previews and notifications. */
    val summary: String
        get() = when (type) {
            MessageType.Text -> text
            MessageType.Image -> if (viewOnce) "View-once photo" else text.ifBlank { "Photo" }
            MessageType.Game -> game?.prompt ?: text
            MessageType.Voice -> "Voice note"
            MessageType.Date -> date?.title ?: text
        }
}

enum class AccessStatus(val key: String) {
    None("none"),
    Pending("pending"),
    Approved("approved"),
    Denied("denied"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: None
    }
}

@Immutable
data class AlbumPhoto(val id: String, val path: String, val url: String?)

@Immutable
data class AlbumAccessRequest(
    val viewerId: String,
    val viewerName: String,
    val viewerPhotoUrl: String?,
    val status: AccessStatus,
    val requestedAt: Instant?,
)

data class DiscoveryFilters(
    val maxDistanceKm: Int = 80,
    val minAge: Int = 18,
    val maxAge: Int = 45,
    val lookingFor: Set<LookingFor> = emptySet(),
    val freeTonightOnly: Boolean = false,
    /** Only people active in the last few minutes. Ignored while you hide your own activity. */
    val onlineOnly: Boolean = false,
    /** Travel mode: distances and nearby-first ordering start here instead of at your city. */
    val browseFrom: Place? = null,
)

enum class ReportReason(val key: String, val label: String) {
    FakeProfile("fake_profile", "Fake profile or catfish"),
    InappropriatePhotos("inappropriate_photos", "Inappropriate photos"),
    Harassment("harassment", "Harassment or bullying"),
    Underage("underage", "May be under 18"),
    SpamScam("spam_scam", "Spam, scam or selling"),
    OffensiveMessages("offensive_messages", "Offensive messages"),
    SafetyConcern("safety_concern", "I feel unsafe"),
    Other("other", "Something else"),
}

/** The signed-in account, as far as the app needs to know. */
data class AuthUser(val uid: String, val email: String?, val phone: String?, val isAdult: Boolean)

object Ages {
    const val MINIMUM = 18
    const val MAXIMUM = 100

    fun ageOn(birthDate: LocalDate, today: LocalDate): Int {
        var age = today.year - birthDate.year
        if (today.monthValue < birthDate.monthValue ||
            (today.monthValue == birthDate.monthValue && today.dayOfMonth < birthDate.dayOfMonth)
        ) {
            age--
        }
        return age
    }
}
