package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DurationTest {
    @Test
    fun parsesUnitsToNanoseconds() {
        assertEquals(30L * 1_000_000_000, ChatRepository.parseDurationNanos("30"))
        assertEquals(10L * 60 * 1_000_000_000, ChatRepository.parseDurationNanos("10m"))
        assertEquals(2L * 3600 * 1_000_000_000, ChatRepository.parseDurationNanos("2hours"))
        assertEquals(86_400L * 1_000_000_000, ChatRepository.parseDurationNanos("1d"))
        assertNull(ChatRepository.parseDurationNanos("soon"))
    }

    @Test
    fun hugeDurationsAreRejectedInsteadOfOverflowing() {
        assertNull(ChatRepository.parseDurationNanos("99999999999999999999999"))
        assertNull(ChatRepository.parseDurationNanos("9999999999999d"))
    }

    @Test
    fun validatesNicks() {
        assertEquals(true, ChatRepository.validNick("Destiny"))
        assertEquals(false, ChatRepository.validNick("ab"))
        assertEquals(false, ChatRepository.validNick("bad nick"))
    }
}
