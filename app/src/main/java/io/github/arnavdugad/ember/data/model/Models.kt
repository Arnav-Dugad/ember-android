package io.github.arnavdugad.ember.data.model

import androidx.compose.runtime.Immutable
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
) {
    val firstPhoto: Photo? get() = photos.firstOrNull()

    /** The distance to show on cards and profiles: null when this person hides it. */
    val shownDistanceKm: Int? get() = if (hideDistance) null else distanceKm

    val hasVoice: Boolean get() = voiceDurationMs != null

    fun isFreeTonight(now: Instant = Instant.now()): Boolean = freeTonightUntil?.isAfter(now) == true
}

/** A named point at city precision (two decimals), from the city picker or travel mode. */
@Immutable
data class Place(val name: String, val lat: Double, val lng: Double)

/** Someone who liked you ("Likes you"). Their profile is fetched separately by id. */
@Immutable
data class Admirer(val uid: String, val superLike: Boolean, val likedAt: Instant?)

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
}

enum class MessageType(val key: String) {
    Text("text"),
    Image("image"),
    Game("game"),
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
)

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
