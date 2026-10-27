package io.github.arnavdugad.ember.data.preview

import android.app.Activity
import android.content.Context
import io.github.arnavdugad.ember.data.remote.nearFirst
import io.github.arnavdugad.ember.data.remote.withDistanceFrom
import dagger.hilt.android.qualifiers.ApplicationContext
import android.net.Uri
import io.github.arnavdugad.ember.data.model.AccessStatus
import io.github.arnavdugad.ember.data.model.Admirer
import io.github.arnavdugad.ember.data.model.Ages
import io.github.arnavdugad.ember.data.model.AlbumAccessRequest
import io.github.arnavdugad.ember.data.model.AlbumPhoto
import io.github.arnavdugad.ember.data.model.AuthUser
import io.github.arnavdugad.ember.data.model.ChatEvent
import io.github.arnavdugad.ember.data.model.DateIdea
import io.github.arnavdugad.ember.data.model.DiscoveryFilters
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMove
import io.github.arnavdugad.ember.data.model.GameMoveType
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.model.LastMessage
import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.LikeNoteKind
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MatchOnlyDetails
import io.github.arnavdugad.ember.data.model.Message
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.model.Photo
import io.github.arnavdugad.ember.data.model.Profile
import io.github.arnavdugad.ember.data.model.ProfileDraft
import io.github.arnavdugad.ember.data.model.Reactions
import io.github.arnavdugad.ember.data.model.ReplyRef
import io.github.arnavdugad.ember.data.model.ReportReason
import io.github.arnavdugad.ember.data.model.Spotlight
import io.github.arnavdugad.ember.data.model.SwipeDirection
import io.github.arnavdugad.ember.data.model.VoiceClip
import io.github.arnavdugad.ember.data.model.spotlightFirst
import io.github.arnavdugad.ember.data.repo.AlbumRepository
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.BackendMode
import io.github.arnavdugad.ember.data.repo.DiscoveryRepository
import io.github.arnavdugad.ember.data.repo.GameRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.repo.PrivacyRepository
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import io.github.arnavdugad.ember.data.repo.SpotlightUsedException
import io.github.arnavdugad.ember.data.repo.UnderageException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The whole backend, in memory, for builds without a Firebase project. It lets anyone install a
 * release APK and feel the app (onboarding, the deck, matches, chat, albums) before setting up
 * Firebase. Demo people can match with you but never message you: no code path here writes a
 * message (or a reaction) from anyone but the local user.
 */
@Singleton
class PreviewBackend @Inject constructor(@ApplicationContext private val context: Context) :
    AuthRepository,
    ProfileRepository,
    DiscoveryRepository,
    MatchRepository,
    AlbumRepository,
    SafetyRepository,
    PrivacyRepository,
    GameRepository {

    override val mode = BackendMode.Preview

    // Built on first use (from the deck, off the startup path): it reads the shared demo copy.
    private val people by lazy { DemoData.generate(DemoContent.load(context)) }
    private val peopleById by lazy { people.associateBy { it.profile.uid } }
    private val authUser = MutableStateFlow<AuthUser?>(null)
    private val me = MutableStateFlow<Profile?>(null)
    private val myMatchOnly = MutableStateFlow<MatchOnlyDetails?>(null)
    private val swipes = LinkedHashMap<String, SwipeDirection>()

    /** The prompt or photo notes my likes carried. */
    private val notes = HashMap<String, LikeNote>()
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

    override fun signInProvider(): String? = null
    override suspend fun reauthenticate(activity: Activity, password: String?) = Unit

    override fun signInMethods(): Set<String> = emptySet()
    override fun accountEmail(): String? = null
    override suspend fun addPassword(activity: Activity, password: String) = noLogin()
    override suspend fun linkGoogle(activity: Activity) = noLogin()
    override suspend fun changePassword(currentPassword: String, newPassword: String) = noLogin()

    private fun noLogin(): Nothing =
        throw UnsupportedOperationException("Preview mode has no login to manage: everything stays on this phone. Sign-in methods need a Firebase project.")

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
                voiceDurationMs = draft.voiceDurationMs,
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

    override suspend fun lastActive(uids: Collection<String>): Map<String, Instant> =
        uids.mapNotNull { uid -> peopleById[uid]?.profile?.takeUnless { it.hideActivity }?.lastActiveAt?.let { uid to it } }.toMap()

    override suspend fun startSpotlight(): Instant {
        delay(300)
        val now = Instant.now()
        if (spotlightUsedToday()) throw SpotlightUsedException()
        val until = Spotlight.until(now)
        persist { it.copy(spotlightUntil = until.toEpochMilli(), spotlightDay = Spotlight.counterId(now)) }
        me.update { it?.copy(spotlightUntil = until) }
        return until
    }

    override suspend fun spotlightUsedToday(): Boolean = stored?.spotlightDay == Spotlight.counterId(Instant.now())

    override suspend fun setFreeTonight(enabled: Boolean) {
        val until = if (enabled) Instant.now().plus(Duration.ofHours(6)) else null
        persist { it.copy(freeTonightUntil = until?.toEpochMilli()) }
        me.update { it?.copy(freeTonightUntil = until) }
    }

    override suspend fun uploadVoice(file: File, durationMs: Int) {
        withContext(Dispatchers.IO) { file.copyTo(store.voice, overwrite = true) }
    }

    override suspend fun deleteVoice() {
        withContext(Dispatchers.IO) { store.voice.delete() }
    }

    /** Only the Preview user has a voice intro; demo people never recorded one. */
    override suspend fun voiceFile(uid: String): File? = if (uid == ME) store.voice.takeIf { it.exists() } else null

    // --- Discovery ---

    override suspend fun loadDeck(filters: DiscoveryFilters, afterDark: Boolean): List<Profile> {
        delay(700)
        val mine = me.value
        val origin = filters.browseFrom?.let { it.lat to it.lng } ?: mine?.lat?.let { lat -> mine.lng?.let { lng -> lat to lng } }
        val now = Instant.now()
        return people.asSequence()
            .map { it.profile }
            .filter { it.uid !in swipes && it.uid !in blocked }
            .filter { it.age in filters.minAge..filters.maxAge }
            .filter { filters.lookingFor.isEmpty() || it.lookingFor in filters.lookingFor }
            .filter { !afterDark || it.afterDark || it.lookingFor.isAdventurous }
            .filter { !filters.freeTonightOnly || it.isFreeTonight(now) }
            .filter { !filters.onlineOnly || mine?.hideActivity == true || it.isOnline(now = now) }
            .filter { mine?.interestedIn.isNullOrEmpty() || it.gender in mine.interestedIn }
            .map { it.withDistanceFrom(origin) }
            .toList()
            .nearFirst(filters.maxDistanceKm, kotlin.random.Random(swipes.size))
            .take(40)
            .spotlightFirst(now)
    }

    override suspend fun swipe(target: Profile, direction: SwipeDirection, note: LikeNote?): String? {
        swipes[target.uid] = direction
        if (direction != SwipeDirection.Nope && note != null) notes[target.uid] = note else notes.remove(target.uid)
        val person = peopleById[target.uid] ?: return null
        if (direction == SwipeDirection.Nope || !person.likesBack) return null
        run {
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
        return matchId(target.uid)
    }

    override suspend fun myNote(targetUid: String): LikeNote? = notes[targetUid]

    override suspend fun rewind(targetUid: String): Boolean {
        if (matches.value.containsKey(matchId(targetUid))) return false
        notes.remove(targetUid)
        return swipes.remove(targetUid) != null
    }

    /**
     * Five demo people who "like you": ones who like back, so liking them from the deck matches
     * (and their tile unblurs). Picked once, preferring people you'd see in your deck. The first
     * liked one of your prompts, so Likes you shows what a note looks like.
     */
    override fun observeLikesYou(): Flow<List<Admirer>> = matches.map {
        val wanted = me.value?.interestedIn.orEmpty()
        val firstPrompt = me.value?.prompts?.firstOrNull()
        val admirers = likesYou ?: people
            .filter { it.likesBack && (wanted.isEmpty() || it.profile.gender in wanted) }
            .ifEmpty { people.filter { it.likesBack } }
            .take(5)
            .mapIndexed { i, p ->
                Admirer(
                    p.profile.uid,
                    superLike = i == 1,
                    likedAt = Instant.now().minus(Duration.ofHours(3L + i * 7L)),
                    note = if (i == 0 && firstPrompt != null) LikeNote.of(LikeNoteKind.Prompt, firstPrompt.id, "Okay, this one made me laugh out loud.") else null,
                )
            }
            .also { likesYou = it }
        admirers.filterNot { it.uid in blocked }
    }

    private var likesYou: List<Admirer>? = null

    // --- Matches and chat ---

    override fun observeMatches(): Flow<List<Match>> = matches.map { it.newestFirst() }

    override suspend fun fetchMatches(): List<Match> = matches.value.newestFirst()

    private fun Map<String, Match>.newestFirst() = values.sortedByDescending { it.lastMessage?.sentAt ?: it.createdAt }

    override fun observeMatch(matchId: String): Flow<Match?> = matches.map { it[matchId] }

    override fun observeMessages(matchId: String): Flow<List<Message>> = messages.map { it[matchId].orEmpty() }

    override fun observeTyping(matchId: String, otherUid: String): Flow<Boolean> = flowOf(false)

    override suspend fun setTyping(matchId: String, typing: Boolean) = Unit

    override suspend fun sendText(matchId: String, text: String, replyTo: ReplyRef?) =
        append(matchId, Message(newId(), ME, MessageType.Text, text.trim(), createdAt = Instant.now(), replyTo = replyTo))

    override suspend fun sendImage(matchId: String, uri: Uri, caption: String, viewOnce: Boolean, replyTo: ReplyRef?) = append(
        matchId,
        Message(newId(), ME, MessageType.Image, caption.trim(), imageUrl = uri.toString(), createdAt = Instant.now(), viewOnce = viewOnce, replyTo = replyTo),
    )

    override suspend fun sendGame(matchId: String, card: GameCard) =
        append(matchId, Message(newId(), ME, MessageType.Game, card.prompt, game = card, createdAt = Instant.now()))

    /** The recording is kept as a local file; nothing leaves the device in Preview mode. */
    override suspend fun sendVoice(matchId: String, clip: VoiceClip, replyTo: ReplyRef?) {
        val id = newId()
        withContext(Dispatchers.IO) {
            val target = voiceFileFor(id)
            target.parentFile?.mkdirs()
            if (!clip.file.renameTo(target)) {
                clip.file.copyTo(target, overwrite = true)
                clip.file.delete()
            }
        }
        append(matchId, Message(id, ME, MessageType.Voice, "", createdAt = Instant.now(), audioId = id, durationMs = clip.durationMs, replyTo = replyTo))
    }

    override suspend fun sendDate(matchId: String, idea: DateIdea) =
        append(matchId, Message(newId(), ME, MessageType.Date, idea.title, createdAt = Instant.now(), date = idea))

    override suspend fun react(matchId: String, messageId: String, emoji: String?) {
        require(emoji == null || emoji in Reactions.all) { "That reaction isn't available." }
        updateMessage(matchId, messageId) { m -> m.copy(reactions = if (emoji == null) m.reactions - ME else m.reactions + (ME to emoji)) }
    }

    override suspend fun markViewOnceOpened(matchId: String, message: Message) =
        updateMessage(matchId, message.id) { it.copy(viewedAt = it.viewedAt ?: Instant.now()) }

    override suspend fun voiceFile(matchId: String, message: Message): File {
        val file = voiceFileFor(message.audioId ?: error("Not a voice note."))
        check(file.exists()) { "This voice note isn't available anymore." }
        return file
    }

    override suspend fun markRead(matchId: String) = Unit

    /** The same tombstone the Firebase rules allow, and the preview follows if it was the latest. */
    override suspend fun unsend(matchId: String, message: Message) {
        check(message.canUnsend(ME, Instant.now())) { "It's been more than 15 minutes, so this one can't be unsent." }
        updateMessage(matchId, message.id) {
            Message(it.id, ME, MessageType.Text, Message.UNSENT_TEXT, createdAt = it.createdAt, unsentAt = Instant.now())
        }
        message.audioId?.let { id -> withContext(Dispatchers.IO) { voiceFileFor(id).delete() } }
        matches.update { all ->
            val match = all[matchId] ?: return@update all
            val last = match.lastMessage?.takeIf { it.senderId == ME && it.sentAt == message.createdAt } ?: return@update all
            all + (matchId to match.copy(lastMessage = last.copy(text = Message.UNSENT_TEXT, type = MessageType.Text)))
        }
    }

    /** Event rows never become the chat's preview. */
    override suspend fun sendEvent(matchId: String, event: ChatEvent) = messages.update {
        val row = Message(newId(), ME, MessageType.Event, event.fallback, createdAt = Instant.now(), event = event)
        it + (matchId to (listOf(row) + it[matchId].orEmpty()))
    }

    private fun voiceFileFor(id: String) = File(context.filesDir, "preview/voice/$id.m4a")

    private fun append(matchId: String, message: Message) {
        messages.update { it + (matchId to (listOf(message) + it[matchId].orEmpty())) }
        matches.update { all ->
            val match = all[matchId] ?: return@update all
            all + (matchId to match.copy(lastMessage = LastMessage(message.summary, ME, message.type, message.createdAt)))
        }
    }

    private fun updateMessage(matchId: String, messageId: String, change: (Message) -> Message) = messages.update { all ->
        val thread = all[matchId] ?: return@update all
        all + (matchId to thread.map { if (it.id == messageId) change(it) else it })
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
        likesYou = null
        me.value = null
        myMatchOnly.value = null
        swipes.clear()
        notes.clear()
        blocked.clear()
        matches.value = emptyMap()
        messages.value = emptyMap()
        album.value = emptyList()
        access.value = emptyMap()
        games.value = emptyMap()
        gameMoves.value = emptyMap()
        myAge = 0
        stored = null
        store.wipe()
        authUser.value = null
    }

    // --- Group Truth or Dare ---
    //
    // In memory only. Every Preview match is a demo profile, and demo profiles can't be invited
    // (they could never accept), so Preview offers a solo practice game: the same screen, turns
    // and cards, with you as the only player.

    private val games = MutableStateFlow<Map<String, GroupGame>>(emptyMap())
    private val gameMoves = MutableStateFlow<Map<String, List<GameMove>>>(emptyMap())

    override fun observeGames(): Flow<List<GroupGame>> =
        games.map { all -> GroupGames.sorted(all.values.filter { it.isLive() }, ME) }

    override fun observeGame(gameId: String): Flow<GroupGame?> = games.map { it[gameId] }

    override fun observeMoves(gameId: String): Flow<List<GameMove>> = gameMoves.map { it[gameId].orEmpty() }

    override suspend fun inviteCandidates(): List<GamePlayer> =
        GroupGames.invitable(matches.value.values.toList(), blocked).map {
            GamePlayer(it.otherUid, GroupGames.firstName(it.otherName), it.otherPhotoUrl, it.otherBlurHash)
        }

    override suspend fun createGame(invitees: List<GamePlayer>): String {
        if (invitees.isNotEmpty()) GroupGames.inviteProblem(ME, invitees.map { it.uid })?.let { error(it) }
        val profile = me.value
        val mine = GamePlayer(ME, GroupGames.firstName(profile?.name.orEmpty()), profile?.firstPhoto?.url, profile?.firstPhoto?.blurHash)
        val now = Instant.now()
        val game = GroupGame(
            id = newId(),
            hostUid = ME,
            members = listOf(ME),
            invited = invitees.map { it.uid },
            players = mapOf(ME to mine) + invitees.associate { it.uid to GamePlayer(it.uid, GroupGames.firstName(it.name)) },
            ended = false,
            turnUid = ME,
            turnNo = 0,
            card = null,
            createdAt = now,
            updatedAt = now,
        )
        games.update { it + (game.id to game) }
        return game.id
    }

    /** Nobody invites the Preview user, so there's never anything to accept. */
    override suspend fun accept(gameId: String) = Unit

    override suspend fun decline(gameId: String) = leave(gameId)

    override suspend fun leave(gameId: String) = removePlayer(gameId, ME)

    override suspend fun removePlayer(gameId: String, uid: String) = updateGame(gameId) { GroupGames.without(it, uid) }

    override suspend fun endGame(gameId: String) {
        games.update { it - gameId }
        gameMoves.update { it - gameId }
    }

    override suspend fun pickCard(gameId: String, card: GameCard) = updateGame(gameId) { game ->
        check(game.turnUid == ME && game.card == null) { "It's not your turn to draw." }
        game.copy(card = card, updatedAt = Instant.now())
    }

    override suspend fun answer(gameId: String, text: String, pass: Boolean) {
        val game = games.value[gameId] ?: error("This game is over.")
        val card = game.card ?: error("Pick Truth or Dare first.")
        check(game.turnUid == ME) { "It's not your turn anymore." }
        val trimmed = text.trim().take(GroupGames.MAX_TEXT)
        require(pass || trimmed.isNotEmpty()) { "Write an answer, or pass." }
        val move = GameMove(
            GroupGames.answerId(game.turnNo), ME, GameMoveType.Answer, if (pass) "" else trimmed,
            card = card, pass = pass, createdAt = Instant.now(),
        )
        gameMoves.update { it + (gameId to (listOf(move) + it[gameId].orEmpty())) }
        updateGame(gameId) { GroupGames.advanced(it).copy(updatedAt = Instant.now()) }
    }

    override suspend fun skipTurn(gameId: String) = updateGame(gameId) { GroupGames.advanced(it).copy(updatedAt = Instant.now()) }

    override suspend fun sendChat(gameId: String, text: String) {
        val trimmed = text.trim().take(GroupGames.MAX_TEXT)
        if (trimmed.isEmpty()) return
        val move = GameMove(newId(), ME, GameMoveType.Chat, trimmed, createdAt = Instant.now())
        gameMoves.update { it + (gameId to (listOf(move) + it[gameId].orEmpty())) }
        updateGame(gameId) { it.copy(updatedAt = Instant.now()) }
    }

    override suspend fun reactToMove(gameId: String, moveId: String, emoji: String?) {
        require(emoji == null || emoji in Reactions.all) { "That reaction isn't available." }
        gameMoves.update { all ->
            val list = all[gameId] ?: return@update all
            all + (gameId to list.map { m ->
                if (m.id != moveId) m else m.copy(reactions = if (emoji == null) m.reactions - ME else m.reactions + (ME to emoji))
            })
        }
    }

    override suspend fun separateFrom(uid: String, onlyHostedTogether: Boolean) {
        games.value.values.filter { uid in it.members || uid in it.invited }.forEach { removePlayer(it.id, uid) }
    }

    override suspend fun sweep() {
        games.value.values.filterNot { it.isLive() }.forEach { endGame(it.id) }
    }

    override suspend fun leaveAll() {
        games.value = emptyMap()
        gameMoves.value = emptyMap()
    }

    private fun updateGame(gameId: String, change: (GroupGame) -> GroupGame) = games.update { all ->
        val game = all[gameId] ?: return@update all
        all + (gameId to change(game))
    }

    // --- Privacy ---

    override suspend fun setHideDistance(hide: Boolean) {
        persist { it.copy(hideDistance = hide) }
        me.update { it?.copy(hideDistance = hide) }
    }

    override suspend fun setPaused(paused: Boolean) {
        persist { it.copy(paused = paused) }
        me.update { it?.copy(paused = paused) }
    }

    override suspend fun setHideActivity(hide: Boolean) {
        persist { it.copy(hideActivity = hide) }
        me.update { it?.copy(hideActivity = hide) }
    }

    /** Preview keeps everything on the phone; the export is the saved profile plus this session's chats. */
    override suspend fun exportMyData(): String {
        val root = buildJsonObject {
            put("format", "Ember data export, version 1 (Preview mode)")
            put("exportedAt", Instant.now().toString())
            put("profile", stored?.let { Json.encodeToJsonElement(StoredProfile.serializer(), it) } ?: JsonNull)
            putJsonObject("swipes") { swipes.forEach { (uid, direction) -> put(uid, direction.key) } }
            putJsonArray("blocks") { blocked.forEach { add(JsonPrimitive(it)) } }
            putJsonArray("matches") {
                matches.value.values.forEach { match ->
                    add(
                        buildJsonObject {
                            put("id", match.id)
                            put("with", match.otherName)
                            put("createdAt", match.createdAt?.toString())
                            putJsonArray("messagesISent") {
                                messages.value[match.id].orEmpty().filter { it.senderId == ME }.sortedBy { it.createdAt }.forEach { m ->
                                    add(
                                        buildJsonObject {
                                            put("type", m.type.key)
                                            put("text", m.text)
                                            m.game?.let { put("game", "${it.mode.key}: ${it.prompt}") }
                                            put("createdAt", m.createdAt?.toString())
                                        },
                                    )
                                }
                            }
                        },
                    )
                }
            }
        }
        return PrettyJson.encodeToString(JsonObject.serializer(), root)
    }

    private fun matchId(other: String) = listOf(ME, other).sorted().joinToString("_")
    private fun newId() = UUID.randomUUID().toString()

    companion object {
        const val ME = "preview-me"
        private val PrettyJson = Json { prettyPrint = true }
    }
}
