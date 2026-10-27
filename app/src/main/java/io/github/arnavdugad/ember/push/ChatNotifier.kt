package io.github.arnavdugad.ember.push

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.arnavdugad.ember.data.model.Match
import io.github.arnavdugad.ember.data.model.MessageType
import io.github.arnavdugad.ember.data.repo.AuthRepository
import io.github.arnavdugad.ember.data.repo.MatchRepository
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for new messages and matches, without a server (FCM pushes need one).
 *
 * While the process is alive, [start] keeps one listener on the user's matches and, whenever the
 * app is in the background, notifies about each new incoming message and each new match. When the
 * process is gone, [MessageSyncWorker] checks every 15 minutes instead. Both go through
 * [notifyUnread], which remembers per match the newest message already notified (or seen while
 * the app was open), so nothing is announced twice and the user's own messages never are.
 */
@Singleton
class ChatNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val matches: MatchRepository,
    private val settings: SettingsRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lock = Mutex()
    private var started = false

    /** Match ids already known for the signed-in user; null until their first snapshot arrives. */
    private var knownMatches: Set<String>? = null

    /** Starts the in-process listener. Firebase mode only: demo profiles never write. */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            // Matches are readable only once the age gate has passed.
            auth.user.map { user -> user?.uid?.takeIf { user.isAdult } }.distinctUntilChanged().flatMapLatest { uid ->
                // A new account starts a new baseline, so its existing matches aren't "new".
                knownMatches = null
                if (uid == null) emptyFlow() else matches.observeMatches()
            }.retryWhen { _, attempt ->
                delay(RETRY_DELAY_MS * (attempt + 1).coerceAtMost(10))
                true
            }.collect { list ->
                val known = knownMatches
                knownMatches = list.mapTo(HashSet()) { it.id }
                val background = !isForeground()
                if (known != null && background) {
                    list.filter { it.id !in known && it.lastMessage == null }.forEach(::notifyMatch)
                }
                notifyUnread(list, background)
            }
        }
    }

    /**
     * Notifies about unread incoming messages newer than the last one notified for that match.
     * With [background] false (the app is open) nothing is shown, but the messages are recorded
     * as seen, so leaving the app later doesn't announce what the user already saw.
     */
    suspend fun notifyUnread(list: List<Match>, background: Boolean) = lock.withLock {
        val notified = settings.notifiedMessages()
        val updates = HashMap<String, Long>()
        list.forEach { match ->
            // hasUnread is only ever true for the other person's message.
            if (!match.hasUnread) return@forEach
            val last = match.lastMessage ?: return@forEach
            val sentAt = last.sentAt?.toEpochMilli() ?: return@forEach
            if (sentAt <= (notified[match.id] ?: 0L)) return@forEach
            if (background) notifyMessage(match)
            updates[match.id] = sentAt
        }
        settings.setNotifiedMessages(updates)
    }

    private fun notifyMessage(match: Match) {
        val last = match.lastMessage ?: return
        val body = when (last.type) {
            MessageType.Image -> "📷 " + last.text
            MessageType.Voice -> "🎤 Voice note"
            MessageType.Game -> "🎲 " + last.text
            MessageType.Date -> "📅 " + last.text
            MessageType.Text, MessageType.Event -> last.text
        }
        Notifications.show(
            context,
            Notifications.CHANNEL_MESSAGES,
            id = match.id.hashCode(),
            title = match.otherName,
            body = body,
            open = "message",
            matchId = match.id,
        )
    }

    private fun notifyMatch(match: Match) {
        Notifications.show(
            context,
            Notifications.CHANNEL_MATCHES,
            id = "match:${match.id}".hashCode(),
            title = "It's a match 🔥",
            body = "You and ${match.otherName} like each other. Say something bold.",
            open = "match",
            matchId = match.id,
        )
    }

    companion object {
        private const val RETRY_DELAY_MS = 5_000L

        /** True while any Ember activity is visible. Read on the main thread. */
        fun isForeground(): Boolean = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }
}
