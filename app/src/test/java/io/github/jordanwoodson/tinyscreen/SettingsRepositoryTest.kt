package io.github.jordanwoodson.tinyscreen

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import io.github.jordanwoodson.tinyscreen.settings.AppSettings
import io.github.jordanwoodson.tinyscreen.settings.PlatformPreference
import io.github.jordanwoodson.tinyscreen.settings.Quality
import io.github.jordanwoodson.tinyscreen.settings.SettingsRepository
import io.github.jordanwoodson.tinyscreen.settings.settingsCorruptionHandler
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SettingsRepositoryTest {
    private val failures = mutableListOf<Throwable>()
    private val scopes = mutableListOf<CoroutineScope>()
    private val file = File.createTempFile("settings", ".preferences_pb").apply { delete() }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> failures += e })
        .also { scopes += it }

    private fun repository(scope: CoroutineScope = newScope()) = SettingsRepository(
        PreferenceDataStoreFactory.create(corruptionHandler = settingsCorruptionHandler, scope = scope) { file },
        scope,
    )

    @After
    fun tearDown() {
        scopes.forEach { it.cancel() }
        file.delete()
    }

    /** Every setting changed from its default. */
    private val custom = AppSettings(
        dynamicColor = false,
        pureBlack = true,
        chatOnLeft = true,
        chatWidthFraction = 0.5f,
        landscapeChatVisible = false,
        showTopEmbeds = false,
        preferredPlatform = PlatformPreference.Twitch,
        qualityWifi = Quality.SOURCE,
        qualityMobile = "480",
        rememberQuality = false,
        backgroundPlayback = false,
        autoPip = false,
        lowLatency = false,
        nativePlayer = false,
        adblock = false,
        webChat = true,
        showTimestamps = true,
        hideFlairs = true,
        animateEmotes = false,
        chatFontScale = 1.25f,
        showRemoved = true,
        highlightMentions = false,
        greentext = false,
        formatEmbedLinks = false,
        hideNsfw = true,
        showJoinLeave = true,
        showCombos = false,
        highlightWords = setOf("rust", "debate"),
        ignoredUsers = setOf("troll"),
        taggedUsers = setOf("friend=#ff0000"),
        hideIgnoredMentions = true,
        highlightNicks = setOf("destiny"),
        whispersInChat = false,
        notifyWhispers = true,
        notifyHighlights = true,
    )

    @Test
    fun everySettingSurvivesARoundTripThroughDisk() = runBlocking {
        val writer = newScope()
        repository(writer).update { custom }
        // A fresh store reads the file back, as on the next launch
        writer.coroutineContext.job.cancelAndJoin()

        val read = withTimeout(10_000) { repository().settings.first { it != AppSettings() } }
        assertEquals(custom, read)
        assertTrue(failures.isEmpty())
    }

    // Uses the app's own corruption handler. (The app's store itself can't be tested here: Robolectric
    // starts TinyscreenApp for every test, which binds the process-wide store to an earlier test's files.)
    @Test
    fun aDamagedFileFallsBackToDefaultsInsteadOfCrashing() = runBlocking {
        file.writeBytes(ByteArray(16) { 0xFF.toByte() })

        val repo = repository()
        repo.update { it.copy(pureBlack = true) }
        val read = withTimeout(10_000) { repo.settings.first { it.pureBlack } }
        assertEquals(AppSettings().copy(pureBlack = true), read)
        assertTrue(failures.toString(), failures.isEmpty())
    }

    @Test
    fun theOldQualityCapSeedsBothQualitySettings() = runBlocking {
        val scope = newScope()
        val store = PreferenceDataStoreFactory.create(scope = scope) { file }
        store.edit { it[stringPreferencesKey("preferred_quality")] = "720" }

        val read = withTimeout(10_000) { SettingsRepository(store, scope).settings.first { it != AppSettings() } }
        assertEquals("720", read.qualityWifi)
        assertEquals("720", read.qualityMobile)
    }
}
