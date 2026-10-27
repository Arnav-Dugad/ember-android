package io.github.arnavdugad.ember.data.repo

import io.github.arnavdugad.ember.data.model.LikeNote
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.Profile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns a prompt or photo note into the liker's first chat message once there's a match. It's
 * always posted by the liker's own app, as the liker (the rules allow nothing else):
 *  - right away when my like completes the match (including a demo profile that likes back);
 *  - otherwise when a fresh match shows up that they completed, from the note kept on my swipe.
 *
 * A match is only written to while it has no messages yet, so the note never lands mid-conversation,
 * and each match is handled once per process.
 */
@Singleton
class LikeNoteCourier @Inject constructor(
    private val discovery: DiscoveryRepository,
    private val matches: MatchRepository,
    private val profiles: ProfileRepository,
) {
    private val handled = HashSet<String>()

    @Synchronized
    private fun claim(matchId: String): Boolean = handled.add(matchId)

    @Synchronized
    private fun release(matchId: String) {
        handled.remove(matchId)
    }

    /** My like with [note] just created [matchId] with [target]. */
    suspend fun deliver(matchId: String, target: Profile, note: LikeNote) {
        if (!claim(matchId)) return
        runCatching { matches.sendText(matchId, note.messageText, note.quote(target)) }.onFailure { release(matchId) }
    }

    /** Fresh matches (no messages yet) whose other person I liked with a note. */
    suspend fun deliverPending(list: List<Match>) {
        for (match in list) {
            if (match.lastMessage != null || !claim(match.id)) continue
            val sent = runCatching {
                val note = discovery.myNote(match.otherUid) ?: return@runCatching
                val target = profiles.getProfile(match.otherUid)
                matches.sendText(match.id, note.messageText, target?.let(note::quote))
            }
            if (sent.isFailure) release(match.id)
        }
    }
}
