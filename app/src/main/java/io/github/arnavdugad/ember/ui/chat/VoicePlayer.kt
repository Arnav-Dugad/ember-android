package io.github.arnavdugad.ember.ui.chat

import android.media.MediaPlayer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/** Plays one voice note at a time; starting another stops the first. Get one from [rememberVoicePlayer]. */
@Stable
class VoicePlayer internal constructor(private val scope: CoroutineScope, private val onError: (String) -> Unit) {
    /** The voice note that is loading, playing or paused. */
    var activeId by mutableStateOf<String?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var isLoading by mutableStateOf(false)
        private set
    private var progress by mutableFloatStateOf(0f)

    private var player: MediaPlayer? = null
    private var ticker: Job? = null
    private var loading: Job? = null

    fun progressOf(id: String): Float = if (id == activeId) progress else 0f

    fun toggle(id: String, load: suspend () -> File) {
        val current = player
        if (id == activeId && current != null) {
            if (current.isPlaying) pause() else resume()
            return
        }
        if (id == activeId) return // still loading
        stop()
        activeId = id
        isLoading = true
        loading = scope.launch {
            val file = runCatching { load() }.getOrElse { e ->
                stop()
                onError(e.message ?: FAILED)
                return@launch
            }
            val next = MediaPlayer()
            val prepared = runCatching {
                next.setDataSource(file.path)
                next.prepare()
            }.isSuccess
            if (!prepared) {
                next.release()
                stop()
                onError(FAILED)
                return@launch
            }
            next.setOnCompletionListener { stop() }
            player = next
            isLoading = false
            resume()
        }
    }

    fun stop() {
        loading?.cancel()
        ticker?.cancel()
        player?.release()
        player = null
        activeId = null
        isPlaying = false
        isLoading = false
        progress = 0f
    }

    private fun pause() {
        player?.pause()
        ticker?.cancel()
        isPlaying = false
    }

    private fun resume() {
        val current = player ?: return
        current.start()
        isPlaying = true
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                if (current.duration > 0) progress = current.currentPosition.toFloat() / current.duration
                delay(40)
            }
        }
    }

    private companion object {
        const val FAILED = "Couldn't play that voice note."
    }
}

@Composable
fun rememberVoicePlayer(onError: (String) -> Unit): VoicePlayer {
    val scope = rememberCoroutineScope()
    val latestOnError by rememberUpdatedState(onError)
    val player = remember(scope) { VoicePlayer(scope) { latestOnError(it) } }
    DisposableEffect(player) { onDispose { player.stop() } }
    return player
}
