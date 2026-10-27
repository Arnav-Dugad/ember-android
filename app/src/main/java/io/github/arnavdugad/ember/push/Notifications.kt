package io.github.arnavdugad.ember.push

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.arnavdugad.ember.MainActivity
import io.github.arnavdugad.ember.R

object Notifications {
    const val CHANNEL_MATCHES = "matches"
    const val CHANNEL_MESSAGES = "messages"
    const val CHANNEL_ALBUM = "album"
    const val CHANNEL_UPDATES = "updates"

    const val EXTRA_MATCH_ID = "ember.matchId"
    const val EXTRA_OPEN = "ember.open"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannels(
            listOf(
                NotificationChannel(CHANNEL_MATCHES, context.getString(R.string.channel_matches), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_MESSAGES, context.getString(R.string.channel_messages), NotificationManager.IMPORTANCE_HIGH),
                NotificationChannel(CHANNEL_ALBUM, context.getString(R.string.channel_album), NotificationManager.IMPORTANCE_DEFAULT),
                NotificationChannel(CHANNEL_UPDATES, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_LOW),
            ),
        )
    }

    fun show(context: Context, channel: String, id: Int, title: String, body: String, open: String, matchId: String? = null) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU
        ) {
            return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN, open)
            matchId?.let { putExtra(EXTRA_MATCH_ID, it) }
        }
        val pending = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_stat_ember)
            .setColor(ContextCompat.getColor(context, R.color.ember_orange))
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(pending)
            // Message previews stay hidden on the lock screen: a dating app shouldn't out anyone.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, channel)
                    .setSmallIcon(R.drawable.ic_stat_ember)
                    .setContentTitle(context.getString(R.string.app_name))
                    .setContentText("You have a new notification")
                    .build(),
            )
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
