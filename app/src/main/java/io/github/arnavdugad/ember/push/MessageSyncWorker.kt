package io.github.arnavdugad.ember.push

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.arnavdugad.ember.data.ai.AiChatEngine
import io.github.arnavdugad.ember.data.repo.MatchRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * The no-server stand-in for push: every 15 minutes (WorkManager's minimum), on any network,
 * read the user's matches once and notify about unread incoming messages not yet notified.
 * Signed out, or with the app open, it does nothing.
 */
@HiltWorker
class MessageSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val matches: MatchRepository,
    private val notifier: ChatNotifier,
    private val ai: AiChatEngine,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (withContext(Dispatchers.Main) { ChatNotifier.isForeground() }) return Result.success()
        // Demo profiles may text first after a quiet spell; the notification below then shows it.
        runCatching { ai.checkIn() }
        val list = runCatching { matches.fetchMatches() }.getOrElse { return Result.retry() }
        notifier.notifyUnread(list, background = true)
        return Result.success()
    }

    companion object {
        private const val NAME = "ember-message-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<MessageSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
