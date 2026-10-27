package io.github.arnavdugad.ember.data.repo

import android.app.Activity
import android.net.Uri
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.model.VoiceClip
import kotlinx.coroutines.flow.Flow
import java.time.Instant
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/** Which backend the app is talking to. Preview is the offline, in-memory demo. */
enum class BackendMode { Firebase, Preview }

/** Thrown by [ProfileRepository.completeAgeGate] when the server says the user is under 18. */
class UnderageException : Exception("You must be 18 or older to use Ember.")

/** Reports are capped per reporter per UTC day; the security rules enforce it with a counter doc. */
object ReportLimits {
    const val PER_DAY = 20

    /** The counter doc id: the UTC date as yyyy-MM-dd. */
    fun dayId(now: Instant): String = now.atOffset(ZoneOffset.UTC).toLocalDate().toString()

    /** Report ids name the counter step they used, so a step can never carry two reports. */
    fun reportId(uid: String, day: String, n: Long): String = "${uid}_${day}_$n"
}

/** Thrown by [SafetyRepository.report] once today's report limit is used up. */
class ReportLimitException(blocked: Boolean) : Exception(
    if (blocked) {
        "You've reached today's limit of ${ReportLimits.PER_DAY} reports, so this one wasn't sent. We've still blocked them."
    } else {
        "You've reached today's limit of ${ReportLimits.PER_DAY} reports. You can still block anyone, and report again tomorrow."
    },
)

/** Firebase provider ids, as returned by [AuthRepository.signInMethods]. */
const val PROVIDER_GOOGLE = "google.com"
const val PROVIDER_PASSWORD = "password"

interface AuthRepository {
    val mode: BackendMode

    /** Null when signed out. */
    val user: Flow<AuthUser?>

    suspend fun signInWithEmail(email: String, password: String)
    suspend fun signUpWithEmail(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun signInWithGoogle(activity: Activity)

    /** Enters the offline demo (Preview mode only). */
    suspend fun enterPreview()

    /** "google.com", "password", or null (Preview / signed out). */
    fun signInProvider(): String?

    /** Fresh sign-in proof, required by Firebase before deleting or linking an account. */
    suspend fun reauthenticate(activity: Activity, password: String?)

    /** Every sign-in method linked to this login: [PROVIDER_GOOGLE] and/or [PROVIDER_PASSWORD]. Empty in Preview. */
    fun signInMethods(): Set<String>

    /** The login's email address, if it has one. */
    fun accountEmail(): String?

    /** For Google logins: confirms with Google, then adds an email + password sign-in. */
    suspend fun addPassword(activity: Activity, password: String)

    /** For email logins: links a Google account so either can sign in. */
    suspend fun linkGoogle(activity: Activity)

    /** For password logins: confirms the current password, then sets a new one. */
    suspend fun changePassword(currentPassword: String, newPassword: String)

    /** Re-reads the ID token so a freshly granted `adult` claim takes effect. */
    suspend fun refreshClaims(): Boolean
    suspend fun signOut()
}

interface ProfileRepository {
    fun observeMyProfile(): Flow<Profile?>
    fun observeMyMatchOnly(): Flow<MatchOnlyDetails?>

    /** Server-validated age gate. Throws [UnderageException] for under-18s. Returns the age. */
    suspend fun completeAgeGate(birthDate: LocalDate, termsVersion: String): Int
    suspend fun saveProfile(draft: ProfileDraft, finishOnboarding: Boolean)
    suspend fun uploadPhoto(uri: Uri): Photo
    suspend fun deletePhoto(photo: Photo)
    suspend fun getProfile(uid: String): Profile?

    /** Readable only when matched with [uid]; null otherwise. */
    suspend fun getMatchOnly(uid: String): MatchOnlyDetails?
    suspend fun touchLastActive()

    /** Shows the "Free tonight" badge for the next six hours, or clears it. */
    suspend fun setFreeTonight(enabled: Boolean)

    /** Stores a recorded voice intro (AAC/MPEG-4). The profile's `voiceDurationMs` is written on save. */
    suspend fun uploadVoice(file: File, durationMs: Int)
    suspend fun deleteVoice()

    /** [uid]'s voice intro as a local file, fetched on demand; null when there is none. */
    suspend fun voiceFile(uid: String): File?
}

interface DiscoveryRepository {
    suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile>
    /** Records the swipe; returns the match id when it made a match, else null. */
    suspend fun swipe(target: Profile, direction: SwipeDirection): String?

    /** True if the swipe was undone. Fails if it already produced a match. */
    suspend fun rewind(targetUid: String): Boolean

    /** People who liked me ("Likes you"), newest first, minus anyone blocked either way. */
    fun observeLikesYou(): Flow<List<Admirer>>
}

/**
 * Matches and chat. Sends never wait for the network: a message is written to the local cache
 * at once (it reads as [Message.pending] until the server has it) and syncs when it can. Photo
 * and voice uploads are the exception and need a connection.
 */
interface MatchRepository {
    fun observeMatches(): Flow<List<Match>>

    /** A one-off read of the user's matches, for background checks. Empty when signed out. */
    suspend fun fetchMatches(): List<Match>
    fun observeMatch(matchId: String): Flow<Match?>
    fun observeMessages(matchId: String): Flow<List<Message>>
    fun observeTyping(matchId: String, otherUid: String): Flow<Boolean>
    suspend fun setTyping(matchId: String, typing: Boolean)
    suspend fun sendText(matchId: String, text: String, replyTo: ReplyRef? = null)
    suspend fun sendImage(matchId: String, uri: Uri, caption: String, viewOnce: Boolean = false, replyTo: ReplyRef? = null)
    suspend fun sendGame(matchId: String, card: GameCard)
    suspend fun sendVoice(matchId: String, clip: VoiceClip, replyTo: ReplyRef? = null)
    suspend fun sendDate(matchId: String, idea: DateIdea)

    /** Sets my reaction to [messageId], or removes it when [emoji] is null. */
    suspend fun react(matchId: String, messageId: String, emoji: String?)

    /** The recipient closed a view-once photo: delete the image and mark the message opened. */
    suspend fun markViewOnceOpened(matchId: String, message: Message)

    /** A playable local copy of a voice note, downloaded on first use. */
    suspend fun voiceFile(matchId: String, message: Message): File

    /**
     * Moves my read marker to now. In a chat with read receipts off the marker stays on this
     * phone, so my unread badges still clear but the other person never sees "Read".
     */
    suspend fun markRead(matchId: String)

    /**
     * Turns my own message into a "Message unsent" tombstone, within [Message.UNSEND_WINDOW] of
     * sending, and deletes its photo or voice note. Needs a connection.
     */
    suspend fun unsend(matchId: String, message: Message)

    /** Posts an event row, e.g. that I took a screenshot. It never becomes the chat's preview. */
    suspend fun sendEvent(matchId: String, event: ChatEvent)
}

interface AlbumRepository {
    fun observeMyAlbum(): Flow<List<AlbumPhoto>>
    suspend fun addPhoto(uri: Uri)
    suspend fun removePhoto(photo: AlbumPhoto)

    /** Requests to view my album, newest first. */
    fun observeRequests(): Flow<List<AlbumAccessRequest>>
    suspend fun respond(viewerUid: String, approve: Boolean)
    suspend fun revoke(viewerUid: String)

    /** My access to someone else's album. */
    fun observeAccess(ownerUid: String): Flow<AccessStatus>
    suspend fun requestAccess(ownerUid: String)
    suspend fun withdrawAccess(ownerUid: String)

    /** Photos of an album I've been approved for. */
    fun observeAlbum(ownerUid: String): Flow<List<AlbumPhoto>>
}

interface SafetyRepository {
    suspend fun unmatch(matchId: String)
    suspend fun block(uid: String)
    suspend fun report(uid: String, reason: ReportReason, details: String, matchId: String?, alsoBlock: Boolean)
    suspend fun deleteAccount()
}

/** The Privacy screen's switches and the data export. */
interface PrivacyRepository {
    /** True: others see only my city, never "N km away". */
    suspend fun setHideDistance(hide: Boolean)

    /** True: I'm left out of everyone's discovery deck. Matches and chats carry on. */
    suspend fun setPaused(paused: Boolean)

    /** Everything Ember stores about me, plus the messages I sent, as pretty-printed JSON. */
    suspend fun exportMyData(): String
}
