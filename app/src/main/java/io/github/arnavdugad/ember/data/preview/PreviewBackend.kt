package io.github.arnavdugad.ember.data.preview

import android.app.Activity
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.Uri
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Ages
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.LastMessage
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.PhoneVerification
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import io.github.arnavdugad.ember.data.repo.UnderageException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole backend, in memory, for builds without a Firebase project. It lets anyone install a
 * release APK and feel the app (onboarding, the deck, matches, chat, albums) before setting up
 * Firebase. Demo people can match with you but never message you: no code path here writes a
 * message from anyone but the local user.
 */
@Singleton
class PreviewBackend @Inject constructor(@ApplicationContext private val context: Context) :
    AuthRepository,
    ProfileRepository,
    DiscoveryRepository,
    MatchRepository,
    AlbumRepository,
    SafetyRepository {

    override val mode = BackendMode.Preview

    // Built on first use (from the deck, off the startup path): it reads the shared demo copy.
    private val people by lazy { DemoData.generate(DemoContent.load(context)) }
    private val peopleById by lazy { people.associateBy { it.profile.uid } }
    private val authUser = MutableStateFlow<AuthUser?>(null)
    private val me = MutableStateFlow<Profile?>(null)
    private val myMatchOnly = MutableStateFlow<MatchOnlyDetails?>(null)
    private val swipes = LinkedHashMap<String, SwipeDirection>()
    private val blocked = mutableSetOf<String>()
    private val matches = MutableStateFlow<Map<String, Match>>(emptyMap())
    private val messages = MutableStateFlow<Map<String, List<Message>>>(emptyMap())
    private val album = MutableStateFlow<List<AlbumPhoto>>(emptyList())
    private val access = MutableStateFlow<Map<String, AccessStatus>>(emptyMap())
    private var myAge = 0
    private val store = PreviewStore(context)
    private var stored: StoredProfile? = null
    private var restored = false

    /** Restores the saved Preview profile once per process. */
    private suspend fun restore() {
        if (restored) return
        restored = true
        val saved = store.load() ?: return
        stored = saved
        myAge = saved.age
        album.value = saved.album.map { AlbumPhoto(it.id, path = "", url = it.url) }
        me.value = saved.toProfile(ME, saved.album.size).takeIf { saved.name.isNotBlank() }
        myMatchOnly.value = saved.matchOnly()
    }

    private suspend fun persist(block: (StoredProfile) -> StoredProfile) {
        val next = block(stored ?: StoredProfile(age = myAge))
        stored = next
        store.save(next)
    }

    // --- Auth ---

    override val user: Flow<AuthUser?> = authUser

    override suspend fun enterPreview() {
        restore()
        authUser.value = AuthUser(ME, email = null, phone = null, isAdult = myAge >= Ages.MINIMUM)
    }

    override suspend fun signInWithEmail(email: String, password: String) = unsupported()
    override suspend fun signUpWithEmail(email: String, password: String) = unsupported()
    override suspend fun sendPasswordReset(email: String) = unsupported()
    override suspend fun signInWithGoogle(activity: Activity) = unsupported()
    override suspend fun startPhoneVerification(activity: Activity, phoneNumber: String): PhoneVerification = unsupported()
    override suspend fun confirmPhoneCode(verificationId: String, code: String) = unsupported()

    override suspend fun refreshClaims(): Boolean = authUser.value?.isAdult == true

    override suspend fun signOut() {
        authUser.value = null
    }

    private fun unsupported(): Nothing =
        throw UnsupportedOperationException("Sign-in needs a Firebase project. Use Preview mode, or see docs/FIREBASE_SETUP.md.")

    // --- Profile ---

    override fun observeMyProfile(): Flow<Profile?> = me
    override fun observeMyMatchOnly(): Flow<MatchOnlyDetails?> = myMatchOnly

    override suspend fun completeAgeGate(birthDate: LocalDate, termsVersion: String): Int {
        delay(400)
        val age = Ages.ageOn(birthDate, LocalDate.now())
        if (age < Ages.MINIMUM) {
            authUser.value = null
            throw UnderageException()
        }
        require(age <= Ages.MAXIMUM) { "Please check your date of birth." }
        myAge = age
        persist { it.copy(age = age) }
        authUser.update { it?.copy(isAdult = true) }
        return age
    }

    override suspend fun saveProfile(draft: ProfileDraft, finishOnboarding: Boolean) {
        delay(300)
        persist { s ->
            s.copy(
                age = myAge,
                onboarded = finishOnboarding || s.onboarded,
                name = draft.name.trim(),
                gender = draft.gender?.key,
                interestedIn = draft.interestedIn.map { it.key },
                bio = draft.bio.trim(),
                photos = draft.photos.map { it.toStored() },
                prompts = draft.prompts.map { StoredPrompt(it.id, it.question, it.answer) },
                interests = draft.interests,
                vibes = draft.vibes,
                lookingFor = draft.lookingFor.key,
                heightCm = draft.heightCm,
                city = draft.city,
                lat = draft.lat,
                lng = draft.lng,
                afterDark = draft.afterDark,
                boundaries = draft.matchOnly.boundaries,
                turnOns = draft.matchOnly.turnOns,
            )
        }
        me.value = stored?.toProfile(ME, album.value.size)
        myMatchOnly.value = draft.matchOnly
    }

    override suspend fun uploadPhoto(uri: Uri): Photo = store.copyIn(uri).toPhoto()

    override suspend fun deletePhoto(photo: Photo) = store.delete(photo.url)

    override suspend fun getProfile(uid: String): Profile? = if (uid == ME) me.value else peopleById[uid]?.profile

    override suspend fun getMatchOnly(uid: String): MatchOnlyDetails? =
        if (matches.value.containsKey(matchId(uid))) peopleById[uid]?.matchOnly else null

    override suspend fun touchLastActive() = Unit

    // --- Discovery ---

    override suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile> {
        delay(700)
        val mine = me.value
        return people.asSequence()
            .map { it.profile }
            .filter { it.uid !in swipes && it.uid !in blocked }
            .filter { it.age in filters.minAge..filters.maxAge }
            .filter { (it.distanceKm ?: 0) <= filters.maxDistanceKm }
            .filter { filters.lookingFor.isEmpty() || it.lookingFor in filters.lookingFor }
            .filter { !afterDark || it.afterDark || it.lookingFor.isAdventurous }
            .filter { mine?.interestedIn.isNullOrEmpty() || it.gender in mine.interestedIn }
            .take(30)
            .toList()
    }

    override suspend fun swipe(target: Profile, direction: SwipeDirection) {
        swipes[target.uid] = direction
        val person = peopleById[target.uid] ?: return
        if (direction != SwipeDirection.Nope && person.likesBack) {
            delay(350)
            val id = matchId(target.uid)
            matches.update {
                it + (
                    id to Match(
                        id = id,
                        otherUid = target.uid,
                        otherName = target.name,
                        otherPhotoUrl = target.firstPhoto?.url,
                        otherBlurHash = null,
                        otherIsDemo = true,
                        createdAt = Instant.now(),
                        lastMessage = null,
                        myLastRead = null,
                        otherLastRead = null,
                        superLike = direction == SwipeDirection.Super,
                    )
                    )
            }
        }
    }

    override suspend fun rewind(targetUid: String): Boolean {
        if (matches.value.containsKey(matchId(targetUid))) return false
        return swipes.remove(targetUid) != null
    }

    // --- Matches and chat ---

    override fun observeMatches(): Flow<List<Match>> =
        matches.map { all -> all.values.sortedByDescending { it.lastMessage?.sentAt ?: it.createdAt } }

    override fun observeMatch(matchId: String): Flow<Match?> = matches.map { it[matchId] }

    override fun observeMessages(matchId: String): Flow<List<Message>> = messages.map { it[matchId].orEmpty() }

    override fun observeTyping(matchId: String, otherUid: String): Flow<Boolean> = flowOf(false)

    override suspend fun setTyping(matchId: String, typing: Boolean) = Unit

    override suspend fun sendText(matchId: String, text: String) = append(matchId, Message(newId(), ME, MessageType.Text, text.trim(), createdAt = Instant.now()))

    override suspend fun sendImage(matchId: String, uri: Uri, caption: String) =
        append(matchId, Message(newId(), ME, MessageType.Image, caption.trim(), imageUrl = uri.toString(), createdAt = Instant.now()))

    override suspend fun sendGame(matchId: String, card: GameCard) =
        append(matchId, Message(newId(), ME, MessageType.Game, card.prompt, game = card, createdAt = Instant.now()))

    override suspend fun markRead(matchId: String) = Unit

    private fun append(matchId: String, message: Message) {
        messages.update { it + (matchId to (listOf(message) + it[matchId].orEmpty())) }
        matches.update { all ->
            val match = all[matchId] ?: return@update all
            all + (matchId to match.copy(lastMessage = LastMessage(message.text.ifBlank { "Photo" }, ME, message.type, message.createdAt)))
        }
    }

    // --- Albums ---

    override fun observeMyAlbum(): Flow<List<AlbumPhoto>> = album

    override suspend fun addPhoto(uri: Uri) {
        val copy = store.copyIn(uri)
        album.update { listOf(AlbumPhoto(copy.id, path = "", url = copy.url)) + it }
        saveAlbum()
    }

    override suspend fun removePhoto(photo: AlbumPhoto) {
        album.update { list -> list.filterNot { it.id == photo.id } }
        photo.url?.let { store.delete(it) }
        saveAlbum()
    }

    private suspend fun saveAlbum() {
        persist { s -> s.copy(album = album.value.map { StoredPhoto(it.id, it.url.orEmpty()) }) }
        me.update { it?.copy(privateAlbumCount = album.value.size) }
    }

    /** Demo people never ask to see your album. */
    override fun observeRequests(): Flow<List<AlbumAccessRequest>> = flowOf(emptyList())
    override suspend fun respond(viewerUid: String, approve: Boolean) = Unit
    override suspend fun revoke(viewerUid: String) = Unit

    override fun observeAccess(ownerUid: String): Flow<AccessStatus> = access.map { it[ownerUid] ?: AccessStatus.None }

    /** Stays pending forever: a demo profile can't consent, so it never approves. */
    override suspend fun requestAccess(ownerUid: String) = access.update { it + (ownerUid to AccessStatus.Pending) }

    override suspend fun withdrawAccess(ownerUid: String) = access.update { it - ownerUid }

    override fun observeAlbum(ownerUid: String): Flow<List<AlbumPhoto>> = flowOf(emptyList())

    // --- Safety ---

    override suspend fun unmatch(matchId: String) {
        matches.update { it - matchId }
        messages.update { it - matchId }
    }

    override suspend fun block(uid: String) {
        blocked += uid
        unmatch(matchId(uid))
    }

    override suspend fun report(uid: String, reason: ReportReason, details: String, matchId: String?, alsoBlock: Boolean) {
        delay(300)
        if (alsoBlock) block(uid)
    }

    override suspend fun deleteAccount() {
        delay(500)
        me.value = null
        myMatchOnly.value = null
        swipes.clear()
        blocked.clear()
        matches.value = emptyMap()
        messages.value = emptyMap()
        album.value = emptyList()
        access.value = emptyMap()
        myAge = 0
        stored = null
        store.wipe()
        authUser.value = null
    }

    private fun matchId(other: String) = listOf(ME, other).sorted().joinToString("_")
    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val ME = "preview-me"
    }
}
