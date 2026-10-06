package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.ChatUser
import io.github.jordanwoodson.tinyscreen.chat.Emote
import io.github.jordanwoodson.tinyscreen.chat.EmoteSet
import io.github.jordanwoodson.tinyscreen.chat.MessageFormatter
import io.github.jordanwoodson.tinyscreen.chat.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MessageFormatterTest {
    private val monkaS = Emote("monkaS", "https://x/monkaS.png", "image/png", 59, 32, 59, 0, false, null, null)
    private val formatter = MessageFormatter(emotes = { EmoteSet(mapOf("monkaS" to monkaS)) }, isConnectedUser = { false })

    @Test
    fun susPostsShoutWithoutBreakingLinksOrEmotes() {
        val sub = ChatUser(nick = "fan", features = listOf("subscriber"))
        val f = formatter.format("ඞ look https://example.com/CaseSensitive monkaS", sub)
        assertTrue(f.sus)
        assertTrue(f.segments.any { it is Segment.Link && it.url == "https://example.com/CaseSensitive" })
        assertTrue(f.segments.any { it is Segment.EmoteSeg && it.emote.prefix == "monkaS" })
        assertTrue(f.segments.filterIsInstance<Segment.Text>().any { it.text.contains("LOOK") })
    }

    @Test
    fun hashIsChatGuisMd5OfTimestampUserIdAndText() {
        assertEquals(md5Of("170000000000042hello"), MessageFormatter.hash(1_700_000_000_000, 42, "hello"))
        assertEquals(md5Of("0a"), MessageFormatter.hash(0, null, "a"))
        assertEquals("0cc175b9c0f1b6a831c399e269772661", md5Of("a"))
    }

    private fun md5Of(text: String) = java.security.MessageDigest.getInstance("MD5").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
