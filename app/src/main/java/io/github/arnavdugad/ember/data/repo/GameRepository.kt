package io.github.arnavdugad.ember.data.repo

import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMove
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGame
import kotlinx.coroutines.flow.Flow

/**
 * Group Truth or Dare (docs/DATA_MODEL.md, "Group Truth or Dare"). Everyone writes only their own
 * part: the host creates and ends, invitees accept or decline themselves, the current player draws
 * and answers, and anyone may leave.
 */
interface GameRepository {
    /**
     * Live games I'm in or invited to (ended and expired ones are left out), with invites first.
     * Invites that would put me next to someone I've blocked, or who blocked me, never show.
     */
    fun observeGames(): Flow<List<GroupGame>>

    /** Null once the game is gone (deleted, or I'm no longer in it). */
    fun observeGame(gameId: String): Flow<GroupGame?>

    /** Newest first. Members only: invitees see nothing until they accept. */
    fun observeMoves(gameId: String): Flow<List<GameMove>>

    /** Matches I could invite: real people, no blocks either way. */
    suspend fun inviteCandidates(): List<GamePlayer>

    /** Sends the invites; returns the new game's id. Preview mode also allows a solo practice game. */
    suspend fun createGame(invitees: List<GamePlayer>): String

    suspend fun accept(gameId: String)
    suspend fun decline(gameId: String)

    /** Members leave; if it was their turn, the next member's starts. The host ends instead. */
    suspend fun leave(gameId: String)

    /** Host only: takes a member or invitee out. */
    suspend fun removePlayer(gameId: String, uid: String)

    /** Host only: ends it for everyone and deletes it. */
    suspend fun endGame(gameId: String)

    /** The current player's card for this turn. Once per turn. */
    suspend fun pickCard(gameId: String, card: GameCard)

    /** The current player's answer (or pass); the turn moves on in the same write. */
    suspend fun answer(gameId: String, text: String, pass: Boolean)

    /** Host only: moves past a player who's gone quiet. */
    suspend fun skipTurn(gameId: String)

    suspend fun sendChat(gameId: String, text: String)

    /** Sets my reaction to [moveId], or removes it when [emoji] is null. */
    suspend fun reactToMove(gameId: String, moveId: String, emoji: String?)

    /** Leaves, declines or ends every game shared with [uid] (block and unmatch). */
    suspend fun separateFrom(uid: String, onlyHostedTogether: Boolean)

    /** Deletes games that ended or went quiet, and declines dead invites. Best effort. */
    suspend fun sweep()

    /** Account deletion: out of every game, my moves gone, my hosted games ended and deleted. */
    suspend fun leaveAll()
}
