package io.github.jordanwoodson.tinyscreen

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import androidx.test.core.app.ApplicationProvider
import io.github.jordanwoodson.tinyscreen.chat.ChatAlert
import io.github.jordanwoodson.tinyscreen.chat.ChatNotifier
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatNotifierTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Before
    fun grant() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun whispersStackPerPersonAndDoNotTimeOut() {
        val notifier = ChatNotifier(app)
        notifier.show(ChatAlert.Whisper("Alice", "hi", 1_000), me = "Jordan")
        notifier.show(ChatAlert.Whisper("alice", "you there?", 2_000), me = "Jordan")
        notifier.show(ChatAlert.Whisper("Bob", "yo", 3_000), me = "Jordan")
        val posted = shadowOf(manager).allNotifications
        assertEquals(2, posted.size)
        posted.forEach { assertEquals(0L, it.timeoutAfter) }
        val alice = posted.first { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it)!!.messages.size == 2 }
        assertEquals(listOf("hi", "you there?"), NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(alice)!!.messages.map { it.text })
    }

    @Test
    fun theSameMessageFromTheSocketAndTheBackgroundCheckNotifiesOnce() {
        val notifier = ChatNotifier(app)
        notifier.show(ChatAlert.Whisper("Alice", "hi", 1_234), me = null)
        notifier.show(ChatAlert.Whisper("Alice", "hi", 1_000), me = null)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(shadowOf(manager).allNotifications.single())!!
        assertEquals(1, style.messages.size)
    }

    @Test
    fun openingTheConversationClearsItsNotification() {
        val notifier = ChatNotifier(app)
        notifier.show(ChatAlert.Whisper("Alice", "hi", 1_000), me = null)
        notifier.show(ChatAlert.Mention("Bob", "jordan look", 2_000), me = null)
        notifier.clearWhisper("ALICE")
        assertEquals(1, shadowOf(manager).allNotifications.size)
        notifier.clearMentions()
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }
}
