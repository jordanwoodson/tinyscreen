package io.github.jordanwoodson.tinyscreen.chat

import io.github.jordanwoodson.tinyscreen.settings.AppSettings

/** A line as the chat list shows it, worked out from the lines around it and the current filters. */
data class ChatRow(
    val line: ChatLine,
    /** Same author as the previous visible line: drawn as "> text" without the nick. */
    val continued: Boolean = false,
    /** From a muted or banned user, drawn as "<censored>" until tapped. */
    val censored: Boolean = false,
    val highlighted: Boolean = false,
) {
    val key: Long get() = line.key
}

/** Which lines are hidden and which are highlighted, from the chat settings. */
data class ChatFilter(
    val meKey: String? = null,
    val ignored: Set<String> = emptySet(),
    val hideIgnoredMentions: Boolean = false,
    val hideNsfw: Boolean = false,
    val showRemoved: Boolean = false,
    val highlights: HighlightRules = HighlightRules(),
) {
    private val ignoredMention: Regex? by lazy {
        ignored.takeIf { hideIgnoredMentions && it.isNotEmpty() }
            ?.let { Regex("\\b(?:${it.joinToString("|") { n -> Regex.escape(n) }})\\b", RegexOption.IGNORE_CASE) }
    }

    fun isOwn(line: ChatLine.User): Boolean = meKey != null && line.user.key == meKey

    /** Hidden whatever happens later: ignored authors, mentions of ignored users, NSFW links. */
    fun hidesText(line: ChatLine.User): Boolean = when {
        isOwn(line) -> false
        line.user.key in ignored -> true
        ignoredMention?.containsMatchIn(line.raw) == true -> true
        hideNsfw && (line.linkTag == LinkTag.Nsfw || line.linkTag == LinkTag.Nsfl) -> true
        else -> false
    }

    companion object {
        fun from(s: AppSettings, me: ChatUser?) = ChatFilter(
            meKey = me?.key,
            ignored = s.ignoredUsers.mapTo(HashSet()) { it.lowercase() },
            hideIgnoredMentions = s.hideIgnoredMentions,
            hideNsfw = s.hideNsfw,
            showRemoved = s.showRemoved,
            highlights = HighlightRules.from(s, me),
        )
    }
}

/**
 * Highlighting, shared by the chat list and notifications so both agree. Like chat-gui: your own
 * nick (outside links) when "Highlight mentions" is on, plus highlighted users and highlight words.
 */
data class HighlightRules(
    val meNick: String? = null,
    val mentions: Boolean = true,
    val words: Set<String> = emptySet(),
    val nicks: Set<String> = emptySet(),
) {
    private val self: Regex? by lazy { meNick?.let { Regex("\\b${Regex.escape(it)}\\b", RegexOption.IGNORE_CASE) } }
    private val wordRegex: Regex? by lazy {
        words.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.let { w -> Regex("\\b(?:${w.joinToString("|") { Regex.escape(it) }})\\b", RegexOption.IGNORE_CASE) }
    }
    private val nickKeys: Set<String> by lazy { nicks.mapTo(HashSet()) { it.lowercase() } }

    fun matches(line: ChatLine.User): Boolean {
        if (line.historical || line.whisper || line.pending) return false
        if (meNick != null && line.user.key == meNick.lowercase()) return false
        if (line.user.key in nickKeys) return true
        if (mentions && mentionsMe(line)) return true
        return wordRegex?.containsMatchIn("${line.user.nick} ${line.raw}") == true
    }

    /** Your nick in the text, links excluded, whether or not mentions are highlighted. */
    fun mentionsMe(line: ChatLine.User): Boolean {
        val regex = self ?: return false
        return regex.containsMatchIn(textWithoutLinks(line.segments))
    }

    companion object {
        fun from(s: AppSettings, me: ChatUser?) = HighlightRules(me?.nick, s.highlightMentions, s.highlightWords, s.highlightNicks)

        fun textWithoutLinks(segments: List<Segment>): String = buildString {
            for (s in segments) {
                when (s) {
                    is Segment.Text -> append(s.text)
                    is Segment.Mention -> append(s.text)
                    is Segment.EmoteSeg -> append(s.emote.prefix)
                    is Segment.HashLink -> append(s.text)
                    is Segment.Link -> append(' ')
                }
            }
        }
    }
}

/**
 * Mutes and bans hide what someone had already said, as on the site: each nick keeps the time of
 * its latest censor and only lines up to then are hidden. Revealing shows everything up to that
 * censor, while a later mute hides the newer lines again.
 */
data class Censors(
    val until: Map<String, Long> = emptyMap(),
    val revealedUntil: Map<String, Long> = emptyMap(),
) {
    fun censor(nickKey: String, at: Long): Censors =
        copy(until = until + (nickKey to maxOf(at, until[nickKey] ?: Long.MIN_VALUE)))

    fun reveal(nickKey: String): Censors =
        until[nickKey]?.let { copy(revealedUntil = revealedUntil + (nickKey to it)) } ?: this

    fun isCensored(line: ChatLine.User): Boolean {
        if (line.whisper || line.historical) return false
        val end = until[line.user.key] ?: return false
        return line.timestamp <= end && line.timestamp > (revealedUntil[line.user.key] ?: Long.MIN_VALUE)
    }
}

/**
 * Turns the raw buffer into rows for the list, newest first. Rows are reused while nothing about
 * them changes so the list only redraws what's new, and the per-line text checks (ignore regex,
 * highlight words) only run again when the filter changes.
 */
class RowBuilder {
    private class Facts(val line: ChatLine.User, val hidden: Boolean, val highlighted: Boolean)

    private var filter: ChatFilter? = null
    private var facts = HashMap<Long, Facts>()
    private var rows = HashMap<Long, ChatRow>()

    /** [watching]: nicks watching the current embed when "only people watching" is on, else null. */
    fun build(lines: List<ChatLine>, filter: ChatFilter, censors: Censors, watching: Set<String>?): List<ChatRow> {
        if (filter != this.filter) {
            this.filter = filter
            facts = HashMap()
        }
        val nextFacts = HashMap<Long, Facts>(lines.size)
        val nextRows = HashMap<Long, ChatRow>(lines.size)
        val out = ArrayList<ChatRow>(lines.size)
        var prev: ChatLine? = null
        for (line in lines) {
            var continued = false
            var censored = false
            var highlighted = false
            if (line is ChatLine.User) {
                val f = facts[line.key]?.takeIf { it.line === line }
                    ?: Facts(line, filter.hidesText(line), filter.highlights.matches(line))
                nextFacts[line.key] = f
                if (f.hidden) continue
                val own = filter.isOwn(line)
                censored = !own && censors.isCensored(line)
                if (censored && !filter.showRemoved) continue
                if (!own && watching != null && !line.historical && !line.whisper && line.user.key !in watching) continue
                highlighted = f.highlighted
                continued = !line.whisper && !line.historical && prev is ChatLine.User &&
                    prev.user.key == line.user.key && !prev.whisper && !prev.historical
            }
            val old = rows[line.key]
            val row = if (old != null && old.line === line && old.continued == continued && old.censored == censored && old.highlighted == highlighted) {
                old
            } else {
                ChatRow(line, continued, censored, highlighted)
            }
            nextRows[line.key] = row
            out += row
            prev = line
        }
        facts = nextFacts
        rows = nextRows
        out.reverse()
        return out
    }
}
