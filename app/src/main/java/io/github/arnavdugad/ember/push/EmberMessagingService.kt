package io.github.arnavdugad.ember.push

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dagger.hilt.android.AndroidEntryPoint
import io.github.arnavdugad.ember.data.repo.ProfileRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Receives match, message and album pushes sent by the Cloud Functions. */
@AndroidEntryPoint
class EmberMessagingService : FirebaseMessagingService() {
    @Inject lateinit var profiles: ProfileRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // FCM 26+: tokens arrive here after FirebaseMessaging.register() (see RootViewModel).
    override fun onRegistered(token: String) {
        scope.launch { runCatching { profiles.registerPushToken(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val type = data["type"] ?: return
        val title = data["title"] ?: message.notification?.title ?: return
        val body = data["body"] ?: message.notification?.body.orEmpty()
        val matchId = data["matchId"]
        val channel = when (type) {
            "match" -> Notifications.CHANNEL_MATCHES
            "album" -> Notifications.CHANNEL_ALBUM
            else -> Notifications.CHANNEL_MESSAGES
        }
        // One notification per conversation, replaced as new messages arrive.
        val id = (matchId ?: type).hashCode()
        Notifications.show(this, channel, id, title, body, open = type, matchId = matchId)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
