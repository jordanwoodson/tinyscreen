package io.github.jordanwoodson.tinyscreen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.jordanwoodson.tinyscreen.chat.ChatAssets
import io.github.jordanwoodson.tinyscreen.chat.ChatLine
import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import io.github.jordanwoodson.tinyscreen.chat.InfoKind
import io.github.jordanwoodson.tinyscreen.settings.SettingsRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/** Drives the repository with socket frames; nothing touches the network. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ChatRepositoryTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    /** App-scope work (whisper refreshes, emote downloads, settings loading) never runs here. */
    private object Never : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private val appScope = CoroutineScope(SupervisorJob() + Never)
    private val chat = ChatRepository(app, appScope, SettingsRepository(app, appScope), ChatAssets(app))

    private fun msg(nick: String, text: String, ts: Long, id: Long = nick.hashCode().toLong()) =
        """MSG {"id":$id,"nick":"$nick","roles":[],"features":[],"timestamp":$ts,"data":"$text"}"""

    private fun userLines() = chat.lines.value.filterIsInstance<ChatLine.User>()

    @Test
    fun aMessageThatCannotBeSentStaysInTheBox() = runBlocking {
        chat.receive("""ME {"id":1,"nick":"Jordan","roles":[],"features":[]}""")
        assertFalse("not connected: keep the text", chat.submit("hello there"))
        assertTrue(chat.lines.value.any { it is ChatLine.Info && it.kind == InfoKind.Error })
        assertFalse("too long", chat.submit("x".repeat(513)))
    }

    @Test
    fun signedOutTextIsKeptForAfterLogin() = runBlocking {
        assertFalse(chat.submit("first message"))
        assertEquals("first message", app.getSharedPreferences(ChatRepository.PREFS, 0).getString("pre_login_text", null))
    }

    @Test
    fun chatStopsTrimmingWhileScrolledUp() = runBlocking {
        repeat(350) { chat.receive(msg("user$it", "line $it", 1_000L + it)) }
        assertEquals(300, userLines().size)
        chat.setPinned(false)
        chat.idle()
        repeat(100) { chat.receive(msg("later$it", "more $it", 5_000L + it)) }
        assertEquals("nothing trimmed while reading", 400, userLines().size)
        chat.setPinned(true)
        chat.idle()
        assertEquals(300, userLines().size)
        assertEquals("more 99", userLines().last().raw)
    }
}
