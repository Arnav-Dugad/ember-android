package io.github.arnavdugad.ember

import io.github.arnavdugad.ember.data.model.Catalog
import io.github.arnavdugad.ember.data.model.GameCard
import io.github.arnavdugad.ember.data.model.GameMode
import io.github.arnavdugad.ember.data.model.GamePlayer
import io.github.arnavdugad.ember.data.model.GroupGame
import io.github.arnavdugad.ember.data.model.GroupGames
import io.github.arnavdugad.ember.data.model.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import kotlin.random.Random

class GroupGameLogicTest {
    private val now = Instant.parse("2026-10-27T20:00:00Z")

    private fun game(
        members: List<String> = listOf("arjun", "maya", "sam"),
        invited: List<String> = emptyList(),
        turn: String = "arjun",
        turnNo: Int = 0,
        card: GameCard? = null,
        updatedAt: Instant? = now,
        ended: Boolean = false,
    ) = GroupGame(
        id = "g1",
        hostUid = "arjun",
        members = members,
        invited = invited,
        players = (members + invited).associateWith { GamePlayer(it, it.replaceFirstChar(Char::uppercase)) },
        ended = ended,
        turnUid = turn,
        turnNo = turnNo,
        card = card,
        createdAt = now,
        updatedAt = updatedAt,
    )

    private fun match(uid: String, demo: Boolean = false) = Match(
        id = "me_$uid", otherUid = uid, otherName = uid, otherPhotoUrl = null, otherBlurHash = null, otherIsDemo = demo,
        createdAt = null, lastMessage = null, myLastRead = null, otherLastRead = null, superLike = false,
    )

    @Test
    fun turnsPassInMemberOrderAndWrapAround() {
        val members = listOf("a", "b", "c", "d")
        assertEquals("b", GroupGames.nextAfter(members, "a"))
        assertEquals("c", GroupGames.nextAfter(members, "b"))
        assertEquals("d", GroupGames.nextAfter(members, "c"))
        assertEquals("a", GroupGames.nextAfter(members, "d"))
        assertEquals("a", GroupGames.nextAfter(listOf("a"), "a"))
        assertEquals("a", GroupGames.nextAfter(listOf("a", "b"), "b"))
        // Someone no longer in the list hands over to the first member, as the rules do.
        assertEquals("a", GroupGames.nextAfter(listOf("a", "b"), "gone"))
    }

    @Test
    fun answeringMovesToTheNextPlayerWithAFreshTurn() {
        val after = GroupGames.advanced(game(turn = "sam", turnNo = 5, card = GameCard(GameMode.Dare, "x")))
        assertEquals("arjun", after.turnUid)
        assertEquals(6, after.turnNo)
        assertNull(after.card)
        assertEquals("t5", GroupGames.answerId(5))
    }

    @Test
    fun leavingOnYourTurnHandsItOn() {
        val card = GameCard(GameMode.Truth, "x")
        val mine = GroupGames.without(game(turn = "maya", turnNo = 2, card = card), "maya")
        assertEquals(listOf("arjun", "sam"), mine.members)
        assertEquals("sam", mine.turnUid)
        assertEquals(3, mine.turnNo)
        assertNull(mine.card)
        assertFalse("maya" in mine.players)

        // Not their turn: the turn and its card stay put.
        val theirs = GroupGames.without(game(turn = "sam", turnNo = 2, card = card), "maya")
        assertEquals("sam", theirs.turnUid)
        assertEquals(2, theirs.turnNo)
        assertEquals(card, theirs.card)

        // The last member before the wrap-around hands back to the host.
        assertEquals("arjun", GroupGames.without(game(turn = "sam"), "sam").turnUid)

        // Declining just drops the invite.
        val declined = GroupGames.without(game(invited = listOf("lee")), "lee")
        assertEquals(emptyList<String>(), declined.invited)
        assertEquals(listOf("arjun", "maya", "sam"), declined.members)
    }

    @Test(expected = IllegalArgumentException::class)
    fun theHostEndsInsteadOfLeaving() {
        GroupGames.without(game(), "arjun")
    }

    @Test
    fun gamesExpireAfterADayOfQuiet() {
        assertFalse(GroupGames.isExpired(now.minus(Duration.ofHours(23)), now))
        assertTrue(GroupGames.isExpired(now.minus(Duration.ofHours(24)), now))
        assertTrue(GroupGames.isExpired(now.minus(Duration.ofDays(3)), now))
        assertFalse(GroupGames.isExpired(null, now)) // still being written
        assertTrue(game(updatedAt = now.minus(Duration.ofHours(25))).let { !it.isLive(now) })
        assertFalse(game(ended = true).isLive(now))
        assertTrue(game().isLive(now))
        assertEquals(Duration.ofHours(4), GroupGames.timeLeft(now.minus(Duration.ofHours(20)), now))
        assertEquals(Duration.ZERO, GroupGames.timeLeft(now.minus(Duration.ofHours(30)), now))
    }

    @Test
    fun invitesNeedOneToThreeDistinctOtherPeople() {
        assertNull(GroupGames.inviteProblem("me", listOf("a")))
        assertNull(GroupGames.inviteProblem("me", listOf("a", "b", "c")))
        assertNotNull(GroupGames.inviteProblem("me", emptyList()))
        assertNotNull(GroupGames.inviteProblem("me", listOf("a", "b", "c", "d")))
        assertNotNull(GroupGames.inviteProblem("me", listOf("a", "a")))
        assertNotNull(GroupGames.inviteProblem("me", listOf("a", "me")))
    }

    @Test
    fun demoProfilesAndBlocksNeverReachThePicker() {
        val matches = listOf(match("ana"), match("demo-7", demo = true), match("blocked"), match("blocker"), match("raj"))
        val picked = GroupGames.invitable(matches, blockedEitherWay = setOf("blocked", "blocker"))
        assertEquals(listOf("ana", "raj"), picked.map { it.otherUid })
    }

    @Test
    fun invitesShowFirstNamesOnly() {
        assertEquals("Arjun", GroupGames.firstName("  Arjun Mehta "))
        assertEquals("Someone", GroupGames.firstName(" "))
        assertEquals(GroupGames.MAX_NAME, GroupGames.firstName("x".repeat(60)).length)

        val invite = game(members = listOf("arjun", "maya"), invited = listOf("sam", "lee"))
        assertEquals("Arjun invited you to Truth or Dare with 2 others", GroupGames.inviteTitle(invite, "sam"))
        assertEquals("Arjun, Maya and Lee", GroupGames.othersLine(invite, "sam"))
        val solo = game(members = listOf("arjun"), invited = listOf("sam"))
        assertEquals("Arjun invited you to Truth or Dare", GroupGames.inviteTitle(solo, "sam"))
        assertEquals("Arjun invited you to Truth or Dare with 1 other", GroupGames.inviteTitle(game(members = listOf("arjun"), invited = listOf("sam", "lee")), "sam"))
    }

    @Test
    fun turnLabels() {
        assertEquals("Your turn", GroupGames.turnLabel(game(turn = "maya"), "maya"))
        assertEquals("Maya's turn", GroupGames.turnLabel(game(turn = "maya"), "sam"))
    }

    @Test
    fun cardsComeFromTheCatalogAndAvoidRepeats() {
        val used = Catalog.truths.drop(1)
        repeat(20) {
            val card = GroupGames.draw(GameMode.Truth, used, Random(it))
            assertEquals(GameMode.Truth, card.mode)
            assertEquals(Catalog.truths.first(), card.prompt)
        }
        // Once the whole deck has been played, it starts over.
        assertTrue(GroupGames.draw(GameMode.Dare, Catalog.dares, Random(1)).prompt in Catalog.dares)
    }

    @Test
    fun invitesComeFirstThenTheMostRecentGame() {
        val older = game().copy(id = "old", updatedAt = now.minus(Duration.ofHours(3)))
        val newer = game().copy(id = "new", updatedAt = now)
        val invite = game(invited = listOf("me")).copy(id = "invite", updatedAt = now.minus(Duration.ofHours(10)))
        assertEquals(listOf("invite", "new", "old"), GroupGames.sorted(listOf(older, newer, invite), "me").map { it.id })
    }
}
