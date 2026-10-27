package io.github.arnavdugad.ember.ui.root

import android.content.Intent
import io.github.arnavdugad.ember.push.Notifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed interface DeepLink {
    data class Chat(val matchId: String) : DeepLink
    data object Matches : DeepLink
    data object Album : DeepLink
    data object Update : DeepLink
}

/** Notification taps, held until the signed-in UI is ready to consume them. */
@Singleton
class DeepLinks @Inject constructor() {
    private val _pending = MutableStateFlow<DeepLink?>(null)
    val pending: StateFlow<DeepLink?> = _pending.asStateFlow()

    fun handle(intent: Intent?) {
        val open = intent?.getStringExtra(Notifications.EXTRA_OPEN) ?: return
        val matchId = intent.getStringExtra(Notifications.EXTRA_MATCH_ID)
        _pending.value = when (open) {
            "message" -> matchId?.let(DeepLink::Chat) ?: DeepLink.Matches
            "match" -> DeepLink.Matches
            "album" -> DeepLink.Album
            "update" -> DeepLink.Update
            else -> null
        }
    }

    fun consume() {
        _pending.value = null
    }
}
