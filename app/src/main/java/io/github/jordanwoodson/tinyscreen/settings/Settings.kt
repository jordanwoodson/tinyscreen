package io.github.jordanwoodson.tinyscreen.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class PlatformPreference(val label: String) {
    Auto("Automatic"),
    Kick("Kick"),
    YouTube("YouTube"),
    Twitch("Twitch"),
    Rumble("Rumble"),
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
    val preferredQuality: String = "auto",
    val backgroundPlayback: Boolean = true,
    val autoPip: Boolean = true,
    val lowLatency: Boolean = true,
    val nativePlayer: Boolean = true,
    val adblock: Boolean = true,
    // Chat
    val webChat: Boolean = false,
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
)

class SettingsRepository(private val context: Context, scope: CoroutineScope) {
    private object Keys {
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val pureBlack = booleanPreferencesKey("pure_black")
        val chatOnLeft = booleanPreferencesKey("chat_on_left")
        val chatWidth = floatPreferencesKey("chat_width")
        val landscapeChatVisible = booleanPreferencesKey("landscape_chat_visible")
        val showTopEmbeds = booleanPreferencesKey("show_top_embeds")
        val preferredPlatform = stringPreferencesKey("preferred_platform")
        val preferredQuality = stringPreferencesKey("preferred_quality")
        val backgroundPlayback = booleanPreferencesKey("background_playback")
        val autoPip = booleanPreferencesKey("auto_pip")
        val lowLatency = booleanPreferencesKey("low_latency")
        val nativePlayer = booleanPreferencesKey("native_player")
        val adblock = booleanPreferencesKey("adblock")
        val webChat = booleanPreferencesKey("web_chat")
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
    }

    val settings: StateFlow<AppSettings> = context.dataStore.data
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
            preferredQuality = this[Keys.preferredQuality] ?: d.preferredQuality,
            backgroundPlayback = this[Keys.backgroundPlayback] ?: d.backgroundPlayback,
            autoPip = this[Keys.autoPip] ?: d.autoPip,
            lowLatency = this[Keys.lowLatency] ?: d.lowLatency,
            nativePlayer = this[Keys.nativePlayer] ?: d.nativePlayer,
            adblock = this[Keys.adblock] ?: d.adblock,
            webChat = this[Keys.webChat] ?: d.webChat,
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
        )
    }

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val next = block(prefs.toSettings())
            prefs.write(next)
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
        this[Keys.preferredQuality] = s.preferredQuality
        this[Keys.backgroundPlayback] = s.backgroundPlayback
        this[Keys.autoPip] = s.autoPip
        this[Keys.lowLatency] = s.lowLatency
        this[Keys.nativePlayer] = s.nativePlayer
        this[Keys.adblock] = s.adblock
        this[Keys.webChat] = s.webChat
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
    }

    fun launchUpdate(scope: CoroutineScope, block: (AppSettings) -> AppSettings) {
        scope.launch { update(block) }
    }
}
