package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.ChatFilter
import io.github.jordanwoodson.tinyscreen.chat.ChatUser
import io.github.jordanwoodson.tinyscreen.chat.HighlightRules
import io.github.jordanwoodson.tinyscreen.chat.Segment
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightRulesTest {
    private val rules = HighlightRules(meNick = "Jordan", mentions = true)

    @Test
    fun yourNickHighlights() {
        assertTrue(rules.matches(userLine("alice", "hey jordan look")))
        assertFalse(rules.matches(userLine("alice", "jordanless")))
    }

    @Test
    fun yourNickInsideALinkDoesNot() {
        val line = userLine("alice", "https://example.com/jordan").copy(
            segments = listOf(Segment.Link("https://example.com/jordan", "example.com/jordan", null)),
        )
        assertFalse(rules.matches(line))
    }

    @Test
    fun mentionsOffStillLeavesWordsAndNicks() {
        val off = HighlightRules(meNick = "Jordan", mentions = false, words = setOf("tinyscreen"), nicks = setOf("Bob"))
        assertFalse(off.matches(userLine("alice", "jordan hi")))
        assertTrue(off.mentionsMe(userLine("alice", "jordan hi")))
        assertTrue(off.matches(userLine("alice", "I use Tinyscreen")))
        assertTrue(off.matches(userLine("bob", "anything")))
    }

    @Test
    fun ownLinesAndWhispersNeverHighlight() {
        assertFalse(rules.matches(userLine("jordan", "jordan talking about jordan")))
        assertFalse(rules.matches(userLine("alice", "jordan", whisper = true)))
    }

    @Test
    fun ignoredUsersAreFilteredBeforeNotifying() {
        val settings = AppSettings(ignoredUsers = setOf("Troll"), highlightMentions = true)
        val filter = ChatFilter.from(settings, ChatUser(nick = "Jordan"))
        val line = userLine("troll", "jordan jordan jordan")
        assertTrue(filter.highlights.matches(line))
        assertTrue("an ignored user's mention is hidden, so it never notifies", filter.hidesText(line))
    }

    @Test
    fun mentionsOfIgnoredUsersAreHiddenWhenAsked() {
        val filter = ChatFilter.from(AppSettings(ignoredUsers = setOf("Troll"), hideIgnoredMentions = true), null)
        assertTrue(filter.hidesText(userLine("alice", "what did troll say")))
        assertFalse(filter.hidesText(userLine("alice", "trolling is bad")))
    }
}
