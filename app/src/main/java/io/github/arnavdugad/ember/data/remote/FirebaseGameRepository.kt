package io.github.arnavdugad.ember.data.remote

import android.content.Context
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.core.util.Connectivity
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.GameMove
import io.github.arnavdugad.ember.data.model.GameMoveType
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.model.Reactions
import io.github.arnavdugad.ember.data.repo.GameRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Group Truth or Dare on Firestore (`games/{gameId}` and its `moves`). There is no server: every
 * write below is one the rules allow for exactly this caller, and anything that depends on the
 * current turn runs in a transaction so two phones can't both move the game on.
 */
@Singleton
class FirebaseGameRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val db: FirebaseFirestore,
    @ApplicationContext private val context: Context,
) : GameRepository {
    private val games get() = db.collection("games")
    private val users get() = db.collection("users")

    override fun observeGames(): Flow<List<GroupGame>> = auth.uidFlow().flatMapLatest { uid ->
        if (uid == null) {
            flowOf(emptyList())
        } else {
            // Two queries because the rules only answer queries scoped to members or to invited.
            combine(
                games.whereArrayContains("members", uid).snapshots(),
                games.whereArrayContains("invited", uid).snapshots(),
                blockedEitherWay(uid),
            ) { mine, invites, blocked ->
                val now = Instant.now()
                val all = (mine?.documents.orEmpty() + invites?.documents.orEmpty()).mapNotNull { it.toGame() }.distinctBy { it.id }
                GroupGames.sorted(all.filter { it.isLive(now) && !(uid in it.invited && it.hasAnyOf(blocked)) }, uid)
            }
        }
    }

    override fun observeGame(gameId: String): Flow<GroupGame?> {
        val uid = auth.currentUser?.uid.orEmpty()
        // Being taken out (or the game being deleted) reads as "gone".
        return games.document(gameId).snapshots().map { snap -> snap?.toGame()?.takeIf { uid in it.members || uid in it.invited } }
    }

    override fun observeMoves(gameId: String): Flow<List<GameMove>> =
        moves(gameId).orderBy("createdAt", Query.Direction.DESCENDING).limit(300)
            .snapshots(includeMetadata = true)
            .map { snap -> snap?.documents.orEmpty().map { it.toMove() } }

    override suspend fun inviteCandidates(): List<GamePlayer> {
        val uid = auth.requireUid()
        val blocked = blockedOnce(uid)
        return db.collection("matches").whereArrayContains("users", uid).get().await().documents.mapNotNull { doc ->
            val data = doc.data ?: return@mapNotNull null
            val other = data.strings("users").firstOrNull { it != uid } ?: return@mapNotNull null
            val profile = data.map("profiles")?.map(other)
            val demo = data.bool("isDemo") || profile?.bool("isDemo") == true
            if (demo || other in blocked) return@mapNotNull null
            GamePlayer(
                uid = other,
                name = GroupGames.firstName(profile?.str("name").orEmpty()),
                photoUrl = profile?.str("photoUrl"),
                blurHash = profile?.str("blurhash"),
            )
        }.sortedBy { it.name.lowercase() }
    }

    override suspend fun createGame(invitees: List<GamePlayer>): String {
        val uid = auth.requireUid()
        GroupGames.inviteProblem(uid, invitees.map { it.uid })?.let { error(it) }
        requireOnline()
        val me = myCard(uid)
        val ref = games.document()
        val profiles = mapOf(uid to me.toFirestore()) +
            invitees.associate { it.uid to GamePlayer(it.uid, GroupGames.firstName(it.name)).toFirestore() }
        explain("Couldn't send the invites. Someone may have unmatched, or there's a block between you.") {
            ref.set(
                mapOf(
                    "host" to uid,
                    "members" to listOf(uid),
                    "invited" to invitees.map { it.uid },
                    "profiles" to profiles,
                    "status" to "active",
                    "turn" to uid,
                    "turnNo" to 0,
                    "card" to null,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
        return ref.id
    }

    /** Joining adds my own first name and first photo; the rules let nobody else write them. */
    override suspend fun accept(gameId: String) {
        val uid = auth.requireUid()
        requireOnline()
        val me = myCard(uid)
        explain("You can't join this game anymore.") {
            games.document(gameId).update(
                mapOf(
                    "invited" to FieldValue.arrayRemove(uid),
                    "members" to FieldValue.arrayUnion(uid),
                    "profiles.$uid" to me.toFirestore(),
                    "updatedAt" to FieldValue.serverTimestamp(),
                ),
            ).await()
        }
    }

    override suspend fun decline(gameId: String) {
        val uid = auth.requireUid()
        commit(games.document(gameId).update(mapOf("invited" to FieldValue.arrayRemove(uid), "profiles.$uid" to FieldValue.delete())))
    }

    override suspend fun leave(gameId: String) = remove(gameId, auth.requireUid())

    override suspend fun removePlayer(gameId: String, uid: String) = remove(gameId, uid)

    /** Leaving, declining or being taken out, with the turn handed on if it was theirs. */
    private suspend fun remove(gameId: String, uid: String) {
        requireOnline()
        val ref = games.document(gameId)
        db.runTransaction { tx ->
            val game = tx.get(ref).toGame() ?: return@runTransaction null
            if (uid !in game.members && uid !in game.invited) return@runTransaction null
            val after = GroupGames.without(game, uid)
            val update = buildMap<String, Any?> {
                put("profiles.$uid", FieldValue.delete())
                if (uid in game.invited) {
                    put("invited", after.invited)
                } else {
                    put("members", after.members)
                    if (after.turnNo != game.turnNo) {
                        put("turn", after.turnUid)
                        put("turnNo", after.turnNo)
                        put("card", null)
                    }
                }
            }
            tx.update(ref, update)
            null
        }.await()
    }

    /** Status first, so play stops for everyone at once; then the moves and the game are deleted. */
    override suspend fun endGame(gameId: String) {
        requireOnline()
        val ref = games.document(gameId)
        explain("Only the host can end the game.") {
            ref.update(mapOf("status" to "ended", "updatedAt" to FieldValue.serverTimestamp())).await()
        }
        runCatching { deleteGame(ref) }
    }

    override suspend fun pickCard(gameId: String, card: GameCard) {
        commit(
            games.document(gameId).update(
                mapOf("card" to card.toFirestore(), "updatedAt" to FieldValue.serverTimestamp()),
            ),
        )
    }

    /** The answer (id `t{turnNo}`) and the turn advance in one transaction, as the rules require. */
    override suspend fun answer(gameId: String, text: String, pass: Boolean) {
        val uid = auth.requireUid()
        val trimmed = text.trim().take(GroupGames.MAX_TEXT)
        require(pass || trimmed.isNotEmpty()) { "Write an answer, or pass." }
        requireOnline()
        val ref = games.document(gameId)
        db.runTransaction { tx ->
            val game = tx.get(ref).toGame() ?: error("This game is over.")
            check(game.turnUid == uid) { "It's not your turn anymore." }
            val card = game.card ?: error("Pick Truth or Dare first.")
            val next = GroupGames.advanced(game)
            tx.set(
                ref.collection("moves").document(GroupGames.answerId(game.turnNo)),
                mapOf(
                    "senderId" to uid,
                    "type" to GameMoveType.Answer.key,
                    "text" to if (pass) "" else trimmed,
                    "mode" to card.mode.key,
                    "prompt" to card.prompt,
                    "pass" to pass,
                    "turnNo" to game.turnNo,
                    "createdAt" to FieldValue.serverTimestamp(),
                ),
            )
            tx.update(ref, mapOf("turn" to next.turnUid, "turnNo" to next.turnNo, "card" to null, "updatedAt" to FieldValue.serverTimestamp()))
            null
        }.await()
    }

    override suspend fun skipTurn(gameId: String) {
        requireOnline()
        val ref = games.document(gameId)
        db.runTransaction { tx ->
            val game = tx.get(ref).toGame() ?: error("This game is over.")
            val next = GroupGames.advanced(game)
            tx.update(ref, mapOf("turn" to next.turnUid, "turnNo" to next.turnNo, "card" to null, "updatedAt" to FieldValue.serverTimestamp()))
            null
        }.await()
    }

    /** A chat line plus a touch of the game's `updatedAt` (it keeps the game alive), in one batch. */
    override suspend fun sendChat(gameId: String, text: String) {
        val uid = auth.requireUid()
        val trimmed = text.trim().take(GroupGames.MAX_TEXT)
        if (trimmed.isEmpty()) return
        val ref = games.document(gameId)
        val batch = db.batch()
        batch.set(
            ref.collection("moves").document(),
            mapOf("senderId" to uid, "type" to GameMoveType.Chat.key, "text" to trimmed, "createdAt" to FieldValue.serverTimestamp()),
        )
        batch.update(ref, "updatedAt", FieldValue.serverTimestamp())
        commit(batch.commit())
    }

    override suspend fun reactToMove(gameId: String, moveId: String, emoji: String?) {
        require(emoji == null || emoji in Reactions.all) { "That reaction isn't available." }
        val uid = auth.requireUid()
        commit(moves(gameId).document(moveId).update("reactions.$uid", emoji ?: FieldValue.delete()))
    }

    override suspend fun separateFrom(uid: String, onlyHostedTogether: Boolean) {
        val me = auth.currentUser?.uid ?: return
        myGames(me).forEach { game ->
            if (uid !in game.members && uid !in game.invited) return@forEach
            when {
                game.hostUid == me -> runCatching { remove(game.id, uid) }
                game.hostUid == uid || !onlyHostedTogether -> runCatching { remove(game.id, me) }
            }
        }
    }

    override suspend fun sweep() {
        val me = auth.currentUser?.uid ?: return
        val blocked = runCatching { blockedOnce(me) }.getOrDefault(emptySet())
        val now = Instant.now()
        myGames(me).forEach { game ->
            runCatching {
                when {
                    me in game.invited && (!game.isLive(now) || game.hasAnyOf(blocked)) -> decline(game.id)
                    me in game.members && !game.isLive(now) -> deleteGame(games.document(game.id))
                }
            }
        }
    }

    /** Runs before the account doc is deleted, though leaving and declining don't need it. */
    override suspend fun leaveAll() {
        val me = auth.currentUser?.uid ?: return
        myGames(me).forEach { game ->
            runCatching {
                val ref = games.document(game.id)
                when {
                    game.hostUid == me -> {
                        if (game.isLive()) ref.update(mapOf("status" to "ended", "updatedAt" to FieldValue.serverTimestamp())).await()
                        deleteGame(ref)
                    }
                    me in game.members -> {
                        moves(game.id).whereEqualTo("senderId", me).get().await().documents.forEach { it.reference.delete().await() }
                        remove(game.id, me)
                    }
                    else -> decline(game.id)
                }
            }
        }
    }

    /** Moves first: once the game doc is gone, the rules can't tell who may delete them. */
    private suspend fun deleteGame(ref: DocumentReference) {
        val moves = ref.collection("moves")
        while (true) {
            val page = moves.limit(200).get().await()
            if (page.isEmpty) break
            val batch = db.batch()
            page.documents.forEach { batch.delete(it.reference) }
            batch.commit().await()
        }
        ref.delete().await()
    }

    private suspend fun myGames(uid: String): List<GroupGame> {
        val mine = games.whereArrayContains("members", uid).get().await().documents
        val invites = games.whereArrayContains("invited", uid).get().await().documents
        return (mine + invites).mapNotNull { it.toGame() }.distinctBy { it.id }
    }

    /** People I blocked and people who blocked me (their `hiddenBy` entry tells me). */
    private fun blockedEitherWay(uid: String): Flow<Set<String>> = combine(
        users.document(uid).collection("blocks").snapshots(),
        users.document(uid).collection("hiddenBy").snapshots(),
    ) { blocks, hiddenBy -> (blocks?.documents.orEmpty() + hiddenBy?.documents.orEmpty()).mapTo(HashSet()) { it.id } }

    private suspend fun blockedOnce(uid: String): Set<String> {
        val blocks = users.document(uid).collection("blocks").get().await().documents
        val hiddenBy = users.document(uid).collection("hiddenBy").get().await().documents
        return (blocks + hiddenBy).mapTo(HashSet()) { it.id }
    }

    /** My first name and first photo: the only part of my profile a game ever carries. */
    private suspend fun myCard(uid: String): GamePlayer {
        val data = users.document(uid).get().await().data ?: error("Finish your profile first.")
        val photo = data.maps("photos").firstOrNull()
        return GamePlayer(
            uid = uid,
            name = GroupGames.firstName(data.str("name").orEmpty()),
            // The rules only accept Ember's own image docs here, never an outside URL.
            photoUrl = photo?.str("url")?.takeIf { it.length <= MAX_URL && FirestoreImages.pathOf(it)?.startsWith("images/") == true },
            blurHash = photo?.str("blurhash")?.takeIf { it.length <= MAX_BLURHASH },
        )
    }

    private fun moves(gameId: String) = games.document(gameId).collection("moves")

    private fun GroupGame.hasAnyOf(uids: Set<String>) = (members + invited).any { it in uids }

    private fun GamePlayer.toFirestore() = mapOf("name" to name, "photoUrl" to photoUrl, "blurhash" to blurHash)

    private fun GameCard.toFirestore() = mapOf("mode" to mode.key, "prompt" to prompt)

    private fun DocumentSnapshot.toGame(): GroupGame? {
        val data = data ?: return null
        val members = data.strings("members")
        val invited = data.strings("invited")
        val profiles = data.map("profiles")
        return GroupGame(
            id = id,
            hostUid = data.str("host").orEmpty(),
            members = members,
            invited = invited,
            players = (members + invited).associateWith { uid ->
                val p = profiles?.map(uid)
                GamePlayer(uid, p?.str("name") ?: "Someone", p?.str("photoUrl"), p?.str("blurhash"))
            },
            ended = data.str("status") == "ended",
            turnUid = data.str("turn").orEmpty(),
            turnNo = data.int("turnNo") ?: 0,
            card = data.map("card")?.let { GameCard(GameMode.of(it.str("mode")), it.str("prompt").orEmpty()) },
            createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
            updatedAt = getTimestamp("updatedAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
        )
    }

    private fun DocumentSnapshot.toMove(): GameMove = GameMove(
        id = id,
        senderId = getString("senderId").orEmpty(),
        type = GameMoveType.of(getString("type")),
        text = getString("text").orEmpty(),
        card = getString("prompt")?.let { GameCard(GameMode.of(getString("mode")), it) },
        pass = getBoolean("pass") == true,
        createdAt = getTimestamp("createdAt", DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)?.toInstant(),
        pending = metadata.hasPendingWrites(),
        reactions = (get("reactions") as? Map<*, *>)?.entries
            ?.mapNotNull { (uid, emoji) -> if (uid is String && emoji is String) uid to emoji else null }
            ?.toMap()
            .orEmpty(),
    )

    /** Like the chat: written to the cache at once, with a short wait for the server when online. */
    private suspend fun commit(task: Task<Void>) {
        if (Connectivity.isOnline(context)) withTimeoutOrNull(ACK_TIMEOUT_MS) { task.await() }
    }

    private fun requireOnline() {
        check(Connectivity.isOnline(context)) { "Group games need a connection. Try again when you're back online." }
    }

    /** A rules rejection becomes [message]; anything else passes through. */
    private suspend fun <T> explain(message: String, block: suspend () -> T): T = try {
        block()
    } catch (e: FirebaseFirestoreException) {
        if (e.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) throw IllegalStateException(message, e) else throw e
    }

    private companion object {
        const val ACK_TIMEOUT_MS = 8_000L
        const val MAX_URL = 300
        const val MAX_BLURHASH = 100
    }
}
