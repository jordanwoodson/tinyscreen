package io.github.jordanwoodson.tinyscreen.player

import android.content.Context
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

data class LanguageTrack(
    val group: TrackGroup, val index: Int, val language: String?, val label: String, val selected: Boolean,
)

/** The exact track is for this stream. Its language preference applies to subsequent streams. */
class LanguageTracks(context: Context, private val player: Player) : Player.Listener {
    private val prefs = context.getSharedPreferences("player_languages", Context.MODE_PRIVATE)
    private val _audio = MutableStateFlow(emptyList<LanguageTrack>())
    private val _captions = MutableStateFlow(emptyList<LanguageTrack>())
    val audio = _audio.asStateFlow()
    val captions = _captions.asStateFlow()

    init {
        player.addListener(this)
        val parameters = player.trackSelectionParameters.buildUpon()
        prefs.getString("audio", null)?.let { parameters.setPreferredAudioLanguage(it) }
        prefs.getString("text", null)?.let { parameters.setPreferredTextLanguage(it) }
        if (prefs.contains("captions_off")) parameters.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, prefs.getBoolean("captions_off", false))
        player.trackSelectionParameters = parameters.build()
    }

    override fun onTracksChanged(tracks: Tracks) {
        _audio.value = options(tracks, C.TRACK_TYPE_AUDIO)
        _captions.value = options(tracks, C.TRACK_TYPE_TEXT)
    }

    private fun options(tracks: Tracks, type: Int): List<LanguageTrack> = buildList {
        for (group in tracks.groups.filter { it.type == type }) {
            for (index in 0 until group.length) {
                if (!group.isTrackSupported(index)) continue
                val format = group.getTrackFormat(index)
                val language = format.language?.takeUnless { it == "und" }
                val name = language?.let { Locale.forLanguageTag(it).displayName }.orEmpty()
                val label = listOfNotNull(name.takeIf(String::isNotEmpty), format.label).distinct().joinToString(" · ")
                add(LanguageTrack(group.mediaTrackGroup, index, language, label.ifBlank { "Track ${size + 1}" }, group.isTrackSelected(index)))
            }
        }
    }

    fun select(type: Int, track: LanguageTrack?) {
        val text = type == C.TRACK_TYPE_TEXT
        val parameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(type)
            .setTrackTypeDisabled(type, text && track == null)
        if (text) parameters.setPreferredTextLanguage(track?.language) else parameters.setPreferredAudioLanguage(track?.language)
        if (track != null) parameters.addOverride(TrackSelectionOverride(track.group, track.index))
        player.trackSelectionParameters = parameters.build()
        prefs.edit {
            putString(if (text) "text" else "audio", track?.language)
            if (text) putBoolean("captions_off", track == null)
        }
    }

    fun clearStreamOverrides() {
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO).clearOverridesOfType(C.TRACK_TYPE_TEXT).build()
    }
}
