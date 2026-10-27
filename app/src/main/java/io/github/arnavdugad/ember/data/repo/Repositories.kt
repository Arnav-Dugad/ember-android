package io.github.arnavdugad.ember.data.repo

import android.app.Activity
import android.net.Uri
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.model.SwipeDirection
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.time.LocalDate

/** Which backend the app is talking to. Preview is the offline, in-memory demo. */
enum class BackendMode { Firebase, Preview }

/** Thrown by [ProfileRepository.completeAgeGate] when the server says the user is under 18. */
class UnderageException : Exception("You must be 18 or older to use Ember.")

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
    suspend fun swipe(target: Profile, direction: SwipeDirection)

    /** True if the swipe was undone. Fails if it already produced a match. */
    suspend fun rewind(targetUid: String): Boolean

    /** People who liked me ("Likes you"), newest first, minus anyone blocked either way. */
    fun observeLikesYou(): Flow<List<Admirer>>
}

interface MatchRepository {
    fun observeMatches(): Flow<List<Match>>
    fun observeMatch(matchId: String): Flow<Match?>
    fun observeMessages(matchId: String): Flow<List<Message>>
    fun observeTyping(matchId: String, otherUid: String): Flow<Boolean>
    suspend fun setTyping(matchId: String, typing: Boolean)
    suspend fun sendText(matchId: String, text: String)
    suspend fun sendImage(matchId: String, uri: Uri, caption: String)
    suspend fun sendGame(matchId: String, card: GameCard)
    suspend fun markRead(matchId: String)
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
