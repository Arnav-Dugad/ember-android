package io.github.arnavdugad.ember.update

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
import io.github.arnavdugad.ember.data.settings.SettingsRepository
import io.github.arnavdugad.ember.push.Notifications
import java.util.concurrent.TimeUnit

/** Once a day, on any network: is there a newer GitHub release? If so, say so once per version. */
@HiltWorker
class UpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updates: UpdateRepository,
    private val settings: SettingsRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val update = runCatching { updates.latest() }.getOrElse { return Result.retry() } ?: return Result.success()
        val snoozed = settings.updateSnoozedUntil(update.version) > System.currentTimeMillis()
        if (settings.lastNotifiedUpdate() != update.version && (!snoozed || update.mandatory)) {
            Notifications.show(
                applicationContext,
                Notifications.CHANNEL_UPDATES,
                id = NOTIFICATION_ID,
                title = if (update.mandatory) "Important Ember update" else "Ember ${update.version} is here",
                body = "Tap to see what's new and update.",
                open = "update",
            )
            settings.setLastNotifiedUpdate(update.version)
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "ember-update-check"
        private const val NOTIFICATION_ID = 4041

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(6, TimeUnit.HOURS)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
