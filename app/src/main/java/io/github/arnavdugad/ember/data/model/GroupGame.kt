package io.github.arnavdugad.ember.data.model

import androidx.compose.runtime.Immutable
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

/**
 * What one player of a group game shows the others: a first name, and once they've joined, their
 * first photo. Nothing else of anyone's profile is part of a game.
 */
@Immutable
data class GamePlayer(val uid: String, val name: String, val photoUrl: String? = null, val blurHash: String? = null)

/**
 * Group Truth or Dare: the host and up to three of their matches take turns. [members] are in, in
 * turn order (the host first); [invited] haven't answered yet and see only an invite.
 */
@Immutable
data class GroupGame(
    val id: String,
    val hostUid: String,
    val members: List<String>,
    val invited: List<String>,
    /** uid -> card, for members and invitees. Invitees have a name and no photo. */
    val players: Map<String, GamePlayer>,
    val ended: Boolean,
    val turnUid: String,
    val turnNo: Int,
    /** The card the current player drew this turn, or null before they pick Truth or Dare. */
    val card: GameCard?,
    val createdAt: Instant?,
    val updatedAt: Instant?,
) {
    fun isLive(now: Instant = Instant.now()): Boolean = !ended && !GroupGames.isExpired(updatedAt, now)

    fun player(uid: String): GamePlayer = players[uid] ?: GamePlayer(uid, "Someone")

    val host: GamePlayer get() = player(hostUid)
}

enum class GameMoveType(val key: String) {
    Answer("answer"),
    Chat("chat"),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Chat
    }
}

/** An answer (the card it answers, the text, or a pass) or a line of game chat. */
@Immutable
data class GameMove(
    val id: String,
    val senderId: String,
    val type: GameMoveType,
    val text: String,
    val card: GameCard? = null,
    val pass: Boolean = false,
    val createdAt: Instant?,
    val pending: Boolean = false,
    /** uid -> one of [Reactions.all]. */
    val reactions: Map<String, String> = emptyMap(),
)

/** The pure rules of group games. The security rules hold the same numbers and turn order. */
object GroupGames {
    const val MAX_PLAYERS = 4
    const val MAX_INVITES = MAX_PLAYERS - 1
    const val MAX_TEXT = 500
    const val MAX_NAME = 40
    val IDLE: Duration = Duration.ofHours(24)

    /** Answers have deterministic ids, so each turn holds exactly one. */
    fun answerId(turnNo: Int) = "t$turnNo"

    /** Who plays after [uid], in member order. Same as `nextAfter` in firestore.rules. */
    fun nextAfter(members: List<String>, uid: String): String {
        require(members.isNotEmpty()) { "Nobody is playing." }
        val index = members.indexOf(uid)
        return if (index < 0) members.first() else members[(index + 1) % members.size]
    }

    /**
     * The game once [uid] has left, declined or been taken out: their card goes with them, and if
     * it was their turn the next member's starts. The host can't leave, only end the game.
     */
    fun without(game: GroupGame, uid: String): GroupGame {
        require(uid != game.hostUid) { "The host ends the game instead of leaving it." }
        val theirTurn = uid in game.members && game.turnUid == uid
        return game.copy(
            members = game.members - uid,
            invited = game.invited - uid,
            players = game.players - uid,
            turnUid = if (theirTurn) nextAfter(game.members, uid) else game.turnUid,
            turnNo = if (theirTurn) game.turnNo + 1 else game.turnNo,
            card = if (theirTurn) null else game.card,
        )
    }

    /** After an answer (or the host's skip): the next member, a fresh turn, no card yet. */
    fun advanced(game: GroupGame): GroupGame =
        game.copy(turnUid = nextAfter(game.members, game.turnUid), turnNo = game.turnNo + 1, card = null)

    /** A card from the same non-explicit deck as the 1:1 game, avoiding prompts this game already had. */
    fun draw(mode: GameMode, used: Collection<String>, random: Random = Random.Default): GameCard {
        val deck = if (mode == GameMode.Truth) Catalog.truths else Catalog.dares
        val fresh = deck.filterNot { it in used }
        return GameCard(mode, fresh.ifEmpty { deck }.random(random))
    }

    /** A game nobody has touched for [IDLE] is over. A missing timestamp (still being written) isn't. */
    fun isExpired(updatedAt: Instant?, now: Instant): Boolean = updatedAt != null && !updatedAt.plus(IDLE).isAfter(now)

    /** How long until a quiet game expires, or zero. */
    fun timeLeft(updatedAt: Instant?, now: Instant): Duration {
        val left = Duration.between(now, (updatedAt ?: now).plus(IDLE))
        return if (left.isNegative) Duration.ZERO else left
    }

    /** The first word of a display name, which is all a game shows. */
    fun firstName(name: String): String =
        name.trim().substringBefore(' ').take(MAX_NAME).ifBlank { "Someone" }

    /**
     * Matches the host may invite: real people only (a demo profile would never accept), nobody
     * blocked either way, and only those still matched.
     */
    fun invitable(matches: List<Match>, blockedEitherWay: Set<String>): List<Match> =
        matches.filter { !it.otherIsDemo && it.otherUid !in blockedEitherWay }

    /** Null when [invitees] can be sent, else the reason they can't. */
    fun inviteProblem(myUid: String, invitees: List<String>): String? = when {
        invitees.isEmpty() -> "Pick at least one match to play with."
        invitees.size > MAX_INVITES -> "A game has room for you and up to $MAX_INVITES others."
        invitees.toSet().size != invitees.size -> "Someone is on the list twice."
        myUid in invitees -> "You're already playing."
        else -> null
    }

    /** "Arjun invited you to Truth or Dare with 2 others". */
    fun inviteTitle(game: GroupGame, myUid: String): String {
        val others = (game.members + game.invited).count { it != myUid && it != game.hostUid }
        val with = when (others) {
            0 -> ""
            1 -> " with 1 other"
            else -> " with $others others"
        }
        return "${game.host.name} invited you to Truth or Dare$with"
    }

    /** First names of everyone else who's in or invited, host first: "Arjun, Maya and Sam". */
    fun othersLine(game: GroupGame, myUid: String): String =
        joinNames((game.members + game.invited).filter { it != myUid }.map { game.player(it).name })

    fun joinNames(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> names[0]
        else -> names.dropLast(1).joinToString(", ") + " and " + names.last()
    }

    /** "Your turn", "Maya's turn". */
    fun turnLabel(game: GroupGame, myUid: String): String =
        if (game.turnUid == myUid) "Your turn" else "${game.player(game.turnUid).name}'s turn"

    /** Live games first by latest activity; my invites before games I'm in. */
    fun sorted(games: List<GroupGame>, myUid: String): List<GroupGame> =
        games.sortedWith(compareBy<GroupGame> { myUid !in it.invited }.thenByDescending { it.updatedAt ?: Instant.MAX })
}
