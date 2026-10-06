package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.stream.Times
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class TimesTest {
    @Test
    fun parsesBothOffsetStyles() {
        val expected = Instant.parse("2026-10-05T17:00:33Z")
        assertEquals(expected, Times.parse("2026-10-05T17:00:33+00:00"))
        assertEquals(expected, Times.parse("2026-10-05T17:00:33+0000"))
        assertEquals(expected, Times.parse("2026-10-05T17:00:33Z"))
        assertEquals(expected, Times.parse("2026-10-05T19:00:33+02:00"))
    }
}
