package io.github.arnavdugad.ember.ui.matches

import android.os.SystemClock
import io.github.arnavdugad.ember.data.repo.SafetyRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** An unmatch that can still be undone until [deadline] (an [SystemClock.elapsedRealtime] time). */
data class PendingUnmatch(val matchId: String, val name: String, val deadline: Long)

/**
 * Unmatching is irreversible, so it waits [WINDOW_MS] for an Undo. The conversation disappears
 * everywhere at once (screens filter out [pending] matches) and [SafetyRepository.unmatch] only
 * runs when the window closes.
 *
 * Nothing here is persisted: if the app is killed inside the window, the unmatch simply never
 * happens and the match is still there next launch. Failing towards "nothing deleted" is the
 * safe direction for a destructive action.
 */
@Singleton
class UnmatchQueue @Inject constructor(private val safety: SafetyRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val jobs = mutableMapOf<String, Job>()

    private val _pending = MutableStateFlow<List<PendingUnmatch>>(emptyList())
    val pending: StateFlow<List<PendingUnmatch>> = _pending.asStateFlow()

    private val _failures = MutableSharedFlow<String>(extraBufferCapacity = 4)

    /** Names of people the unmatch failed for; their conversation is back. */
    val failures: SharedFlow<String> = _failures.asSharedFlow()

    fun unmatch(matchId: String, name: String) {
        if (matchId in jobs) return
        _pending.update { it + PendingUnmatch(matchId, name, SystemClock.elapsedRealtime() + WINDOW_MS) }
        jobs[matchId] = scope.launch {
            delay(WINDOW_MS)
            // Past the window: no undo from here on, and nothing may interrupt the deletes.
            jobs.remove(matchId)
            val result = withContext(NonCancellable) { runCatching { safety.unmatch(matchId) } }
            _pending.update { list -> list.filterNot { it.matchId == matchId } }
            if (result.isFailure) _failures.tryEmit(name)
        }
    }

    fun undo(matchId: String) {
        val job = jobs.remove(matchId) ?: return
        job.cancel()
        _pending.update { list -> list.filterNot { it.matchId == matchId } }
    }

    companion object {
        const val WINDOW_MS = 10_000L
    }
}
