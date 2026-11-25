package io.github.jordanwoodson.tinyscreen

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import io.github.jordanwoodson.tinyscreen.player.ContinueWatching
import io.github.jordanwoodson.tinyscreen.stream.Embed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContinueWatchingTest {
    private val app: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun positionsSurviveRestartAndFinishedVideosLeaveHistory() {
        val history = ContinueWatching(app)
        val embed = Embed("youtube", "video")
        history.record(embed, "Title", 12_345, false)
        history.record(embed.copy(startSeconds = 12), "Title", 20_456, false)
        val reopened = ContinueWatching(app)
        assertEquals(20_456L, reopened.recent.value.single().positionMs)
        assertEquals(embed.copy(startSeconds = 20), reopened.recent.value.single().embed)
        reopened.record(embed, "Title", 0, true)
        assertTrue(ContinueWatching(app).recent.value.isEmpty())
    }

    @Test
    fun historyKeepsTheMostRecentTwelveAndClearIsDurable() {
        val history = ContinueWatching(app)
        for (id in 1..13) history.record(Embed("twitch-vod", id.toString()), "Video $id", 1000, false)
        history.record(Embed("twitch-vod", "9"), "Nine", 2000, false)
        assertEquals(listOf("9", "13", "12", "11", "10", "8", "7", "6", "5", "4", "3", "2"), history.recent.value.map { it.id })
        history.clear()
        assertTrue(ContinueWatching(app).recent.value.isEmpty())
    }
}
