package io.github.jordanwoodson.tinyscreen.chat

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
import io.github.jordanwoodson.tinyscreen.MainActivity
import io.github.jordanwoodson.tinyscreen.R

/** Posts whisper/highlight notifications while the app is in the background. */
class ChatNotifier(private val context: Context) {
    private var nextId = 2_000

    init {
        val channel = NotificationChannel(CHANNEL, "Chat mentions & whispers", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(title: String, text: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setTimeoutAfter(8_000)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(nextId++, notification) }
    }

    companion object {
        private const val CHANNEL = "chat"
    }
}
