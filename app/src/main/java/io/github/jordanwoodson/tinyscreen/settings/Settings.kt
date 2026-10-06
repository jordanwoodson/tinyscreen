package io.github.jordanwoodson.tinyscreen.settings

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException

/** A damaged settings file starts over from the defaults instead of crashing every launch. */
internal val settingsCorruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() }

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = settingsCorruptionHandler,
)

enum class PlatformPreference(val label: String) {
    Auto("Automatic"),
    Kick("Kick"),
    YouTube("YouTube"),
    Twitch("Twitch"),
    Rumble("Rumble"),
}

/**
 * Stream quality preferences: "auto" (adapts to the connection), "source" (always the best), a height
 * such as "720" (the best rendition up to 720p) or "audio" (audio only).
 */
object Quality {
    const val AUTO = "auto"
    const val SOURCE = "source"
    const val AUDIO = "audio"

    val choices = listOf(AUTO, SOURCE, "1080", "720", "480", "360", AUDIO)

    fun label(pref: String): String = when (pref) {
        AUTO -> "Auto"
        SOURCE -> "Source"
        AUDIO -> "Audio only"
        else -> pref.toIntOrNull()?.let { "${it}p" } ?: "Auto"
    }
}

data class AppSettings(
    // Appearance
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    // Layout
    val chatOnLeft: Boolean = false,
    val chatWidthFraction: Float = 0.34f,
    val landscapeChatVisible: Boolean = true,
    val showTopEmbeds: Boolean = true,
    // Playback
    val preferredPlatform: PlatformPreference = PlatformPreference.Auto,
    val qualityWifi: String = Quality.AUTO,
    val qualityMobile: String = Quality.AUTO,
    val rememberQuality: Boolean = true,
    val backgroundPlayback: Boolean = true,
    val autoPip: Boolean = true,
    val lowLatency: Boolean = true,
    val nativePlayer: Boolean = true,
    val adblock: Boolean = true,
    // Chat
    val webChat: Boolean = false,
    val platformChatTabs: Boolean = true,
    /** Platforms (kick, youtube, …) whose chat tabs are switched off. */
    val hiddenChatPlatforms: Set<String> = emptySet(),
    val otherStreamChatTab: Boolean = true,
    val showTimestamps: Boolean = false,
    val hideFlairs: Boolean = false,
    val animateEmotes: Boolean = true,
    val chatFontScale: Float = 1f,
    val showRemoved: Boolean = false,
    val highlightMentions: Boolean = true,
    val greentext: Boolean = true,
    val formatEmbedLinks: Boolean = true,
    val hideNsfw: Boolean = false,
    val showJoinLeave: Boolean = false,
    val showCombos: Boolean = true,
    val highlightWords: Set<String> = emptySet(),
    val ignoredUsers: Set<String> = emptySet(),
    val taggedUsers: Set<String> = emptySet(), // "nick=color"
    val hideIgnoredMentions: Boolean = false,
    val highlightNicks: Set<String> = emptySet(),
    val whispersInChat: Boolean = true,
    val notifyWhispers: Boolean = false,
    val notifyHighlights: Boolean = false,
    /** Lines chat keeps while you're at the bottom; scrolled up it keeps up to [CHAT_LINES_UNPINNED]. */
    val chatLines: Int = 300,
) {
    companion object {
        val chatLineChoices = listOf(150, 300, 500, 1000)
        const val CHAT_LINES_UNPINNED = 2_000
    }
}

class SettingsRepository(private val store: DataStore<Preferences>, scope: CoroutineScope) {
    constructor(context: Context, scope: CoroutineScope) : this(context.dataStore, scope)

    private object Keys {
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pureBlack = booleanPreferencesKey("pure_black")
        val chatOnLeft = booleanPreferencesKey("chat_on_left")
        val chatWidth = floatPreferencesKey("chat_width")
        val landscapeChatVisible = booleanPreferencesKey("landscape_chat_visible")
        val showTopEmbeds = booleanPreferencesKey("show_top_embeds")
        val preferredPlatform = stringPreferencesKey("preferred_platform")
        /** The old single "quality cap", read once to seed the Wi-Fi and mobile settings. */
        val legacyQuality = stringPreferencesKey("preferred_quality")
        val qualityWifi = stringPreferencesKey("quality_wifi")
        val qualityMobile = stringPreferencesKey("quality_mobile")
        val rememberQuality = booleanPreferencesKey("remember_quality")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val autoPip = booleanPreferencesKey("auto_pip")
        val lowLatency = booleanPreferencesKey("low_latency")
        val nativePlayer = booleanPreferencesKey("native_player")
        val adblock = booleanPreferencesKey("adblock")
        val webChat = booleanPreferencesKey("web_chat")
        val platformChatTabs = booleanPreferencesKey("platform_chat_tabs")
        val hiddenChatPlatforms = stringSetPreferencesKey("hidden_chat_platforms")
        val otherStreamChatTab = booleanPreferencesKey("other_stream_chat_tab")
        val showTimestamps = booleanPreferencesKey("show_timestamps")
        val hideFlairs = booleanPreferencesKey("hide_flairs")
        val animateEmotes = booleanPreferencesKey("animate_emotes")
        val chatFontScale = floatPreferencesKey("chat_font_scale")
        val showRemoved = booleanPreferencesKey("show_removed")
        val highlightMentions = booleanPreferencesKey("highlight_mentions")
        val greentext = booleanPreferencesKey("greentext")
        val formatEmbedLinks = booleanPreferencesKey("format_embed_links")
        val hideNsfw = booleanPreferencesKey("hide_nsfw")
        val showJoinLeave = booleanPreferencesKey("show_join_leave")
        val showCombos = booleanPreferencesKey("show_combos")
        val highlightWords = stringSetPreferencesKey("highlight_words")
        val ignoredUsers = stringSetPreferencesKey("ignored_users")
        val taggedUsers = stringSetPreferencesKey("tagged_users")
        val hideIgnoredMentions = booleanPreferencesKey("hide_ignored_mentions")
        val highlightNicks = stringSetPreferencesKey("highlight_nicks")
        val whispersInChat = booleanPreferencesKey("whispers_in_chat")
        val notifyWhispers = booleanPreferencesKey("notify_whispers")
        val notifyHighlights = booleanPreferencesKey("notify_highlights")
        val chatLines = intPreferencesKey("chat_lines")
    }

    val settings: StateFlow<AppSettings> = store.data
        // A file that can't be read at all (rather than a damaged one) shows the defaults
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            dynamicColor = this[Keys.dynamicColor] ?: d.dynamicColor,
            pureBlack = this[Keys.pureBlack] ?: d.pureBlack,
            chatOnLeft = this[Keys.chatOnLeft] ?: d.chatOnLeft,
            chatWidthFraction = this[Keys.chatWidth] ?: d.chatWidthFraction,
            landscapeChatVisible = this[Keys.landscapeChatVisible] ?: d.landscapeChatVisible,
            showTopEmbeds = this[Keys.showTopEmbeds] ?: d.showTopEmbeds,
            preferredPlatform = this[Keys.preferredPlatform]
                ?.let { runCatching { PlatformPreference.valueOf(it) }.getOrNull() }
                ?: d.preferredPlatform,
            qualityWifi = this[Keys.qualityWifi] ?: this[Keys.legacyQuality] ?: d.qualityWifi,
            qualityMobile = this[Keys.qualityMobile] ?: this[Keys.legacyQuality] ?: d.qualityMobile,
            rememberQuality = this[Keys.rememberQuality] ?: d.rememberQuality,
            backgroundPlayback = this[Keys.backgroundPlayback] ?: d.backgroundPlayback,
            autoPip = this[Keys.autoPip] ?: d.autoPip,
            lowLatency = this[Keys.lowLatency] ?: d.lowLatency,
            nativePlayer = this[Keys.nativePlayer] ?: d.nativePlayer,
            adblock = this[Keys.adblock] ?: d.adblock,
            webChat = this[Keys.webChat] ?: d.webChat,
            platformChatTabs = this[Keys.platformChatTabs] ?: d.platformChatTabs,
            hiddenChatPlatforms = this[Keys.hiddenChatPlatforms] ?: d.hiddenChatPlatforms,
            otherStreamChatTab = this[Keys.otherStreamChatTab] ?: d.otherStreamChatTab,
            showTimestamps = this[Keys.showTimestamps] ?: d.showTimestamps,
            hideFlairs = this[Keys.hideFlairs] ?: d.hideFlairs,
            animateEmotes = this[Keys.animateEmotes] ?: d.animateEmotes,
            chatFontScale = this[Keys.chatFontScale] ?: d.chatFontScale,
            showRemoved = this[Keys.showRemoved] ?: d.showRemoved,
            highlightMentions = this[Keys.highlightMentions] ?: d.highlightMentions,
            greentext = this[Keys.greentext] ?: d.greentext,
            formatEmbedLinks = this[Keys.formatEmbedLinks] ?: d.formatEmbedLinks,
            hideNsfw = this[Keys.hideNsfw] ?: d.hideNsfw,
            showJoinLeave = this[Keys.showJoinLeave] ?: d.showJoinLeave,
            showCombos = this[Keys.showCombos] ?: d.showCombos,
            highlightWords = this[Keys.highlightWords] ?: d.highlightWords,
            ignoredUsers = this[Keys.ignoredUsers] ?: d.ignoredUsers,
            taggedUsers = this[Keys.taggedUsers] ?: d.taggedUsers,
            hideIgnoredMentions = this[Keys.hideIgnoredMentions] ?: d.hideIgnoredMentions,
            highlightNicks = this[Keys.highlightNicks] ?: d.highlightNicks,
            whispersInChat = this[Keys.whispersInChat] ?: d.whispersInChat,
            notifyWhispers = this[Keys.notifyWhispers] ?: d.notifyWhispers,
            notifyHighlights = this[Keys.notifyHighlights] ?: d.notifyHighlights,
            chatLines = this[Keys.chatLines] ?: d.chatLines,
        )
    }

    /** The stored settings, read from disk: for background work that can't wait for [settings] to load. */
    suspend fun current(): AppSettings = store.data.first().toSettings()

    suspend fun update(block: (AppSettings) -> AppSettings) {
        try {
            store.edit { prefs ->
                val next = block(prefs.toSettings())
                prefs.write(next)
            }
        } catch (e: IOException) {
            // Losing one change beats crashing the app's scope
            Log.w("Settings", "couldn't save settings", e)
        }
    }

    private fun MutablePreferences.write(s: AppSettings) {
        this[Keys.dynamicColor] = s.dynamicColor
        this[Keys.pureBlack] = s.pureBlack
        this[Keys.chatOnLeft] = s.chatOnLeft
        this[Keys.chatWidth] = s.chatWidthFraction
        this[Keys.landscapeChatVisible] = s.landscapeChatVisible
        this[Keys.showTopEmbeds] = s.showTopEmbeds
        this[Keys.preferredPlatform] = s.preferredPlatform.name
        remove(Keys.legacyQuality)
        this[Keys.qualityWifi] = s.qualityWifi
        this[Keys.qualityMobile] = s.qualityMobile
        this[Keys.rememberQuality] = s.rememberQuality
        this[Keys.backgroundPlayback] = s.backgroundPlayback
        this[Keys.autoPip] = s.autoPip
        this[Keys.lowLatency] = s.lowLatency
        this[Keys.nativePlayer] = s.nativePlayer
        this[Keys.adblock] = s.adblock
        this[Keys.webChat] = s.webChat
        this[Keys.platformChatTabs] = s.platformChatTabs
        this[Keys.hiddenChatPlatforms] = s.hiddenChatPlatforms
        this[Keys.otherStreamChatTab] = s.otherStreamChatTab
        this[Keys.showTimestamps] = s.showTimestamps
        this[Keys.hideFlairs] = s.hideFlairs
        this[Keys.animateEmotes] = s.animateEmotes
        this[Keys.chatFontScale] = s.chatFontScale
        this[Keys.showRemoved] = s.showRemoved
        this[Keys.highlightMentions] = s.highlightMentions
        this[Keys.greentext] = s.greentext
        this[Keys.formatEmbedLinks] = s.formatEmbedLinks
        this[Keys.hideNsfw] = s.hideNsfw
        this[Keys.showJoinLeave] = s.showJoinLeave
        this[Keys.showCombos] = s.showCombos
        this[Keys.highlightWords] = s.highlightWords
        this[Keys.ignoredUsers] = s.ignoredUsers
        this[Keys.taggedUsers] = s.taggedUsers
        this[Keys.hideIgnoredMentions] = s.hideIgnoredMentions
        this[Keys.highlightNicks] = s.highlightNicks
        this[Keys.whispersInChat] = s.whispersInChat
        this[Keys.notifyWhispers] = s.notifyWhispers
        this[Keys.notifyHighlights] = s.notifyHighlights
        this[Keys.chatLines] = s.chatLines
    }

    fun launchUpdate(scope: CoroutineScope, block: (AppSettings) -> AppSettings) {
        scope.launch { update(block) }
    }
}
