package io.github.jordanwoodson.tinyscreen.chat

import kotlin.math.roundToInt

/**
 * Reads what emotes.css says about each emote's box: its size, which part of the image it shows
 * (background-position) and the frame-strip animation, if any. Only bare `.emote.X` rules count;
 * per-user, combo and hover variants, and anything in comments, are ignored.
 */
object EmoteCss {
    data class Style(
        val width: Int? = null,
        val height: Int? = null,
        /** background-position x in px while not animating. */
        val restOffset: Int = 0,
        val sprite: SpriteAnimation? = null,
    )

    private class Rule {
        var width: Int? = null
        var height: Int? = null
        var position: Int? = null
        var animation: String? = null
        var duration: Int? = null
        var iterations: Int? = null
    }

    private class Keyframes(val from: Int?, val to: Int?)

    private val COMMENT = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)
    private val KEYFRAMES = Regex("""@keyframes\s+([\w-]+)\s*\{((?:[^{}]*\{[^{}]*\})*[^{}]*)\}""")
    private val RULE = Regex("""([^{}]+)\{([^{}]*)\}""")
    private val STEPS_CALL = Regex("""steps\([^)]*\)""")
    private val SPACE = Regex("\\s+")
    private val BARE = Regex("""\.emote\.([A-Za-z0-9_-]+)""")
    private val TIME = Regex("""(-?[\d.]+)(ms|s)""")
    private val STEPS = Regex("""steps\(\s*(\d+)""")
    private val CALC = Regex("""calc\(\s*(-?[\d.]+)\s*\*\s*(-?[\d.]+)px\s*\)""")
    private val PX = Regex("""^(-?[\d.]+)(px)?$""")

    fun parse(css: String): Map<String, Style> {
        val text = css.replace(COMMENT, "")
        val keyframes = HashMap<String, Keyframes>()
        for (m in KEYFRAMES.findAll(text)) keyframes[m.groupValues[1]] = parseKeyframes(m.groupValues[2])
        val rules = HashMap<String, Rule>()
        for (m in RULE.findAll(text.replace(KEYFRAMES, ""))) {
            val names = m.groupValues[1].split(',').mapNotNull { BARE.matchEntire(it.trim())?.groupValues?.get(1) }
            if (names.isEmpty()) continue
            val declarations = declarations(m.groupValues[2])
            // Later rules win, as in the cascade
            names.forEach { name -> apply(rules.getOrPut(name) { Rule() }, declarations) }
        }
        return rules.mapValues { (_, rule) -> style(rule, keyframes) }
    }

    private fun declarations(body: String): List<Pair<String, String>> = body.split(';').mapNotNull { d ->
        val colon = d.indexOf(':')
        if (colon < 0) return@mapNotNull null
        val name = d.substring(0, colon).trim().lowercase()
        // "//animation: …" isn't valid CSS; browsers drop it
        if (name.isEmpty() || !name.first().isLetter() && name.first() != '-') return@mapNotNull null
        name to d.substring(colon + 1).trim()
    }

    private fun apply(rule: Rule, declarations: List<Pair<String, String>>) {
        for ((name, value) in declarations) {
            when (name) {
                "width" -> px(value)?.let { rule.width = it }
                "height" -> px(value)?.let { rule.height = it }
                "background-position" -> rule.position = firstPx(value) ?: rule.position
                "animation" -> rule.animation = value
                "animation-duration" -> time(value)?.let { rule.duration = it }
                "animation-iteration-count" -> iterations(value)?.let { rule.iterations = it }
            }
        }
    }

    private fun style(rule: Rule, keyframes: Map<String, Keyframes>): Style {
        val rest = rule.position ?: 0
        val base = Style(rule.width, rule.height, rest)
        val animation = rule.animation?.split(',')?.map { it.trim() }?.firstOrNull { STEPS.containsMatchIn(it) } ?: return base
        val frames = STEPS.find(animation)!!.groupValues[1].toInt()
        val tokens = animation.replace(STEPS_CALL, " ").split(SPACE).filter { it.isNotEmpty() }
        var duration: Int? = null
        var iterations: Int? = null
        var hold = false
        var reverse = false
        var name: String? = null
        for (t in tokens) {
            val ms = time(t)
            when {
                ms != null -> if (duration == null) duration = ms
                t == "infinite" -> iterations = 0
                t.toFloatOrNull() != null -> iterations = t.toFloat().roundToInt()
                t == "forwards" || t == "both" -> hold = true
                t == "reverse" -> reverse = true
                t in KEYWORDS -> Unit
                else -> if (name == null) name = t
            }
        }
        duration = rule.duration ?: duration
        iterations = rule.iterations ?: iterations ?: 1
        val kf = name?.let { keyframes[it] }
        // A missing 0% or 100% keyframe animates from or to the element's own position
        var start = kf?.from ?: rest
        var end = kf?.to ?: if (kf == null) rest - frames * (rule.width ?: 0) else rest
        if (reverse) start = end.also { end = start }
        if (frames < 1 || start == end || duration == null || duration <= 0) {
            return base.copy(restOffset = if (hold) end else rest)
        }
        return base.copy(sprite = SpriteAnimation(start, end, frames, duration, iterations, hold))
    }

    private fun parseKeyframes(body: String): Keyframes {
        var from: Int? = null
        var to: Int? = null
        for (m in RULE.findAll(body)) {
            val position = declarations(m.groupValues[2]).lastOrNull { it.first == "background-position" }?.let { firstPx(it.second) } ?: continue
            val stops = m.groupValues[1].split(',').map { it.trim().lowercase() }
            if ("from" in stops || "0%" in stops) from = position
            if ("to" in stops || "100%" in stops) to = position
        }
        return Keyframes(from, to)
    }

    private fun px(value: String): Int? = PX.matchEntire(value.trim())?.groupValues?.get(1)?.toFloatOrNull()?.roundToInt()

    /** The x part of a background-position: "-672px", "0", "-5940px 0", "calc(-28*34px)". */
    private fun firstPx(value: String): Int? {
        val v = value.trim()
        CALC.find(v)?.let { return (it.groupValues[1].toFloat() * it.groupValues[2].toFloat()).roundToInt() }
        return px(v.split(SPACE).first())
    }

    private fun time(token: String): Int? {
        val m = TIME.matchEntire(token.trim()) ?: return null
        val n = m.groupValues[1].toFloatOrNull() ?: return null
        return (if (m.groupValues[2] == "s") n * 1000 else n).roundToInt()
    }

    private fun iterations(value: String): Int? = if (value.trim() == "infinite") 0 else value.trim().toFloatOrNull()?.roundToInt()

    private val KEYWORDS = setOf(
        "linear", "ease", "ease-in", "ease-out", "ease-in-out", "step-start", "step-end", "normal", "alternate",
        "alternate-reverse", "none", "backwards", "running", "paused",
    )
}
