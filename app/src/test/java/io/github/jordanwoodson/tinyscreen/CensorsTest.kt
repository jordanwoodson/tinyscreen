package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.Censors
import io.github.jordanwoodson.tinyscreen.chat.ChatFilter
import io.github.jordanwoodson.tinyscreen.chat.ChatLine
import io.github.jordanwoodson.tinyscreen.chat.RowBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CensorsTest {
    @Test
    fun aMuteOnlyHidesWhatWasSaidBeforeIt() {
        val before = userLine("regular", "spam", timestamp = 1_000)
        val after = userLine("regular", "back after the mute", timestamp = 700_000)
        val censors = Censors().censor("regular", at = 2_000)
        assertTrue(censors.isCensored(before))
        assertFalse(censors.isCensored(after))
    }

    @Test
    fun otherNicksAreUnaffected() {
        val censors = Censors().censor("regular", at = 2_000)
        assertFalse(censors.isCensored(userLine("someone", "hi", timestamp = 1_000)))
    }

    @Test
    fun revealingShowsTheOldLinesButALaterMuteHidesNewOnes() {
        val old = userLine("regular", "old", timestamp = 1_000)
        val newer = userLine("regular", "newer", timestamp = 5_000)
        var censors = Censors().censor("regular", at = 2_000).reveal("regular")
        assertFalse(censors.isCensored(old))
        censors = censors.censor("regular", at = 6_000)
        assertFalse(censors.isCensored(old))
        assertTrue(censors.isCensored(newer))
    }

    @Test
    fun anOlderMuteReplayedFromHistoryDoesNotWidenTheCensor() {
        val line = userLine("regular", "between", timestamp = 3_000)
        val censors = Censors().censor("regular", at = 2_000).censor("regular", at = 1_000)
        assertFalse(censors.isCensored(line))
    }

    @Test
    fun whispersAndOwnLinesAreNeverCensored() {
        val censors = Censors().censor("me", at = 9_000).censor("friend", at = 9_000)
        assertFalse(censors.isCensored(userLine("friend", "psst", timestamp = 1_000, whisper = true)))
        val rows = RowBuilder().build(listOf(userLine("me", "my line", timestamp = 1_000)), ChatFilter(meKey = "me"), censors, null)
        assertFalse(rows.single().censored)
    }

    @Test
    fun censoredLinesAreRemovedUnlessShowRemovedIsOn() {
        val lines = listOf(userLine("regular", "spam", timestamp = 1_000), userLine("regular", "later", timestamp = 9_000))
        val censors = Censors().censor("regular", at = 2_000)
        assertEquals(listOf("later"), RowBuilder().build(lines, ChatFilter(), censors, null).map { (it.line as ChatLine.User).raw })
        val shown = RowBuilder().build(lines, ChatFilter(showRemoved = true), censors, null)
        assertEquals(listOf(false, true), shown.map { it.censored })
    }
}
