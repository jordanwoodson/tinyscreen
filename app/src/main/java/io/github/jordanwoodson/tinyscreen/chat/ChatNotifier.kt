package io.github.jordanwoodson.tinyscreen.chat

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.content.ContextCompat
import io.github.jordanwoodson.tinyscreen.MainActivity
import io.github.jordanwoodson.tinyscreen.R

/**
 * Whisper and mention notifications, as conversations: one per person who whispers you, and one
 * for mentions in chat. They stay until opened or swiped away, and each keeps its last few lines.
 */
class ChatNotifier(private val context: Context) {
    private class Line(val from: String, val text: String, val timestamp: Long)

    private val whispers = HashMap<String, ArrayDeque<Line>>()
    private val mentions = ArrayDeque<Line>()
    /** The socket and the background check can both see the same message; only notify once. */
    private val recent = LinkedHashMap<String, Long>()

    init {
        val channel = NotificationChannel(CHANNEL, "Chat mentions & whispers", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    @Synchronized
    fun show(alert: ChatAlert, me: String?) {
        if (!canNotify()) return
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > DEDUPE_MS }
        val dedupe = "${alert::class.simpleName}|${alert.from.lowercase()}|${alert.text}"
        if (recent.put(dedupe, now) != null) return
        val line = Line(alert.from, alert.text, alert.timestamp)
        when (alert) {
            is ChatAlert.Whisper -> {
                val key = alert.from.lowercase()
                val lines = whispers.getOrPut(key) { ArrayDeque() }
                lines.addLast(line)
                while (lines.size > MAX_LINES) lines.removeFirst()
                post(whisperId(key), conversation(me, lines, title = null), alert.from, open(whisperId(key), alert.from))
            }
            is ChatAlert.Mention -> {
                mentions.addLast(line)
                while (mentions.size > MAX_LINES) mentions.removeFirst()
                post(MENTIONS_ID, conversation(me, mentions, title = "Mentions in chat"), "Mentions in chat", open(MENTIONS_ID, null))
            }
        }
    }

    /** You've opened the conversation, so its notification is read. */
    @Synchronized
    fun clearWhisper(nick: String) {
        val key = nick.lowercase()
        whispers.remove(key)
        runCatching { NotificationManagerCompat.from(context).cancel(whisperId(key)) }
    }

    /** Back in the app, chat itself shows the mentions. */
    @Synchronized
    fun clearMentions() {
        mentions.clear()
        runCatching { NotificationManagerCompat.from(context).cancel(MENTIONS_ID) }
    }

    /** Notifications are on for the app, and on Android 13+ the permission is granted (older versions have none). */
    fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    }

    private fun conversation(me: String?, lines: Collection<Line>, title: String?): NotificationCompat.MessagingStyle {
        val style = NotificationCompat.MessagingStyle(Person.Builder().setName(me ?: "You").build())
        if (title != null) {
            style.conversationTitle = title
            style.isGroupConversation = true
        }
        lines.forEach { style.addMessage(it.text, it.timestamp, Person.Builder().setName(it.from).setKey(it.from.lowercase()).build()) }
        return style
    }

    private fun post(id: Int, style: NotificationCompat.MessagingStyle, title: String, intent: PendingIntent) {
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        // Checked again here: the permission can be revoked at any time
        if (!canNotify()) return
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            Log.w("ChatNotifier", "notification permission was revoked", e)
        }
    }

    private fun open(requestCode: Int, whisperFrom: String?): PendingIntent = PendingIntent.getActivity(
        context,
        requestCode,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .apply { whisperFrom?.let { putExtra(EXTRA_WHISPER_FROM, it) } },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun whisperId(key: String) = WHISPER_ID_BASE + (key.hashCode() and 0xFFFF)

    companion object {
        private const val CHANNEL = "chat"
        private const val MENTIONS_ID = 2_000
        private const val WHISPER_ID_BASE = 10_000
        private const val MAX_LINES = 8
        private const val DEDUPE_MS = 30 * 60_000L

        /** On a whisper notification's intent: who sent it, so the app can open that conversation. */
        const val EXTRA_WHISPER_FROM = "io.github.jordanwoodson.tinyscreen.WHISPER_FROM"
    }
}
