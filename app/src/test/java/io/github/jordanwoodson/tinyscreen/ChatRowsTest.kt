package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.Censors
import io.github.jordanwoodson.tinyscreen.chat.ChatFilter
import io.github.jordanwoodson.tinyscreen.chat.ChatLine
import io.github.jordanwoodson.tinyscreen.chat.ChatUser
import io.github.jordanwoodson.tinyscreen.chat.InfoKind
import io.github.jordanwoodson.tinyscreen.chat.LinkTag
import io.github.jordanwoodson.tinyscreen.chat.RowBuilder
import io.github.jordanwoodson.tinyscreen.chat.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private var nextKey = 1L

fun userLine(nick: String, text: String, timestamp: Long = nextKey * 1000, whisper: Boolean = false) = ChatLine.User(
    key = nextKey++,
    timestamp = timestamp,
    user = ChatUser(nick = nick),
    raw = text,
    segments = listOf(Segment.Text(text)),
    action = false,
    greentext = false,
    sus = false,
    linkTag = null,
    mentions = emptySet(),
    hash = "h$nextKey",
    whisper = whisper,
)

class ChatRowsTest {
    @Test
    fun continuationFollowsTheVisibleLines() {
        val lines = listOf(
            userLine("alice", "first"),
            userLine("bob", "ignored in between"),
            userLine("alice", "> anyway"),
        )
        val rows = RowBuilder().build(lines, ChatFilter(ignored = setOf("bob")), Censors(), null).reversed()
        assertEquals(listOf("first", "> anyway"), rows.map { (it.line as ChatLine.User).raw })
        assertFalse(rows[0].continued)
        assertTrue(rows[1].continued)
    }

    @Test
    fun aHiddenFirstLineDoesNotLeaveTheNextOneNameless() {
        val lines = listOf(
            userLine("alice", "hello"),
            userLine("carol", "nsfw link").copy(linkTag = LinkTag.Nsfw),
            userLine("carol", "and another thing"),
        )
        val rows = RowBuilder().build(lines, ChatFilter(hideNsfw = true), Censors(), null).reversed()
        assertEquals(2, rows.size)
        assertFalse("first visible carol line shows the nick", rows[1].continued)
    }

    @Test
    fun infoLinesBreakAContinuation() {
        val lines = listOf(
            userLine("alice", "one"),
            ChatLine.Info(nextKey++, 0, InfoKind.Info, "notice"),
            userLine("alice", "two"),
        )
        val rows = RowBuilder().build(lines, ChatFilter(), Censors(), null)
        assertFalse(rows.first().continued)
    }

    @Test
    fun rowsAreReusedWhenNothingChanged() {
        val builder = RowBuilder()
        val lines = listOf(userLine("alice", "one"), userLine("bob", "two"))
        val first = builder.build(lines, ChatFilter(), Censors(), null)
        val second = builder.build(lines + userLine("carol", "three"), ChatFilter(), Censors(), null)
        assertEquals(3, second.size)
        assertSame(first[0], second[1])
        assertSame(first[1], second[2])
    }

    @Test
    fun newestRowComesFirst() {
        val lines = listOf(userLine("alice", "old"), userLine("alice", "new"))
        val rows = RowBuilder().build(lines, ChatFilter(), Censors(), null)
        assertEquals("new", (rows.first().line as ChatLine.User).raw)
    }

    @Test
    fun watchingFilterKeepsOwnLines() {
        val lines = listOf(userLine("me", "mine"), userLine("alice", "elsewhere"), userLine("bob", "same stream"))
        val rows = RowBuilder().build(lines, ChatFilter(meKey = "me"), Censors(), watching = setOf("bob"))
        assertEquals(listOf("same stream", "mine"), rows.map { (it.line as ChatLine.User).raw })
    }
}
