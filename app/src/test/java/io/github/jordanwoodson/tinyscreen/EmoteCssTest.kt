package io.github.jordanwoodson.tinyscreen

import io.github.jordanwoodson.tinyscreen.chat.ChatAssets
import io.github.jordanwoodson.tinyscreen.chat.EmoteCss
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Snippets copied from r2cdn.destiny.gg/emotes/emotes.css. */
@RunWith(RobolectricTestRunner::class)
class EmoteCssTest {
    private val dggL = """
        .emote.dggL {
            background-image: url("65193333306cb.png");
            height: 32px;
            width: 768px;
        }
        .msg-chat .emote.dggL {
            margin-top: -32px;
            top: 8px;
        }
        .emote.dggL {
            height: 32px;
            width: 32px;
            background-position: -672px;
            background-repeat: no-repeat;
            filter: brightness(1.1);
            transition: ease-out .4s;
        }
        .emote.dggL:hover {
            transform: scale(1.3);
        }
        .msg-chat.msg-user[data-username="prsek"]>.text .emote.dggL.dggL {
            background-position: -608px;
            animation: none;
        }
        /*
        [data-username="bot"] .emote.dggL,
        [data-username="cake"] .emote.dggL {
            height: 28px;
            width: 28px;
            background-position: -166px;
        }*/
        .msg-chat.msg-user>time[data-unixtimestamp${'$'}="0"]~.text .emote.dggL {
            background-position: 0px;
        }
        .msg-chat.msg-user>time[data-unixtimestamp${'$'}="01"]~.text .emote.dggL {
            background-position: -320px;
            //animation: RARE-BRONZE-PULSATE 2.5s 5 linear;
        }
    """.trimIndent()

    private val chatting = """
        .emote.Chatting {
            background-image: url("65191515dc04e.png");
            height: 32px;
            width: 320px;
        }
        .emote.Chatting{
          width: 32px;
          height: 32px;
          transform: scale(-1, 1) translate(-2px, 3px);
        }
        .emote:not(.Chatting) + .emote.Chatting + .emote:not(.Chatting) + .emote.Chatting,
        .emote.Chatting:only-of-type,
        .emote + .emote.Chatting{
          transform: scale(1) translate(-2px, 3px);
          animation: Chatting-anim .30s steps(4) 60;
        }
        @keyframes Chatting-anim {
          100% { background-position: -128px; }
        }
    """.trimIndent()

    private val animated = """
        .emote.AMAZIN {
            width: 53px;
            animation: AMAZIN-anim 2 steps(45) 2200ms;
        }
        .emote.AMAZIN:hover { animation: AMAZIN-anim-hover infinite steps(45) 2200ms; }
        @keyframes AMAZIN-anim {
            100% { background-position: -2385px; }
        }
        .emote.AngelThump {
            width: 105px;
            height: 30px;
            background-repeat: no-repeat;
            background-position: 0px;
            animation: AngelThump-anim steps(119) 1.25s 1 forwards;
            /*transform: scale(1.1);*/
        }
        @keyframes AngelThump-anim { to { background-position: -12495px; } }
        .emote.BINGQILIN {
            width: 32px;
            background-position: 32px;
            animation: BINGQILIN steps(90) 2.5s 4;
        }
        @keyframes BINGQILIN { 0% { background-position: 0px; } 100% { background-position: -2880px; } }
        .emote.FiveHead { width: 28px; animation: FiveHead-anim 1.7s steps(34) 1; }
        @keyframes FiveHead-anim { 100% { background-position: calc(-28*34px); } }
        .emote.GameOfThrows {
          width: 79px;
          animation: GameOfThrows-anim 1 steps(32) ;
          animation-duration: 5s;
        }
        .emote.catJAM { width: 32px; height: 32px; animation: catJAM-anim 2 steps(190) 6.5s; }
        .emote.Klappa { width: 38px; animation: Klappa-anim 10 steps(2) 0.4s -0.15s; }
    """.trimIndent()

    @Test
    fun dggLShowsOneStaticHeartAndIgnoresPerUserAndCommentedRules() {
        val style = EmoteCss.parse(dggL).getValue("dggL")
        assertEquals(32, style.width)
        assertEquals(32, style.height)
        assertEquals(-672, style.restOffset)
        assertNull("no animation on the bare rule", style.sprite)
    }

    @Test
    fun chattingIsAStillFrameBecauseItsAnimationOnlyAppliesNextToOtherEmotes() {
        val style = EmoteCss.parse(chatting).getValue("Chatting")
        assertEquals(32, style.width)
        assertNull(style.sprite)
        assertEquals(0, style.restOffset)
    }

    @Test
    fun framesComeFromSteps() {
        val styles = EmoteCss.parse(animated)
        styles.getValue("AMAZIN").sprite!!.let {
            assertEquals(45, it.frames)
            assertEquals(2200, it.durationMs)
            assertEquals(2, it.iterations)
            assertEquals(0, it.start)
            assertEquals(-2385, it.end)
            assertEquals(-53, it.offsetAt(1))
            assertFalse(it.holdEnd)
        }
        styles.getValue("AngelThump").sprite!!.let {
            assertEquals(119, it.frames)
            assertEquals(1250, it.durationMs)
            assertTrue("forwards keeps the last frame", it.holdEnd)
            assertEquals(-12495, it.end)
            assertEquals(-105, it.offsetAt(1))
        }
        styles.getValue("BINGQILIN").let {
            assertEquals(32, it.restOffset)
            assertEquals(90, it.sprite!!.frames)
            assertEquals(-32, it.sprite!!.offsetAt(1))
        }
        assertEquals(-952, styles.getValue("FiveHead").sprite!!.end)
        assertEquals(5000, styles.getValue("GameOfThrows").sprite!!.durationMs)
        // No keyframes in the snippet: step one frame width at a time
        assertEquals(-32, styles.getValue("catJAM").sprite!!.offsetAt(1))
        styles.getValue("Klappa").sprite!!.let {
            assertEquals("the negative time is a delay, not the duration", 400, it.durationMs)
            assertEquals(10, it.iterations)
            assertEquals(2, it.frames)
        }
    }

    @Test
    fun emotesCropToTheirBox() {
        val json = """
            [
              {"prefix":"dggL","image":[{"url":"https://r2cdn.destiny.gg/emotes/65193333306cb.png","mime":"image/png","height":32,"width":768}]},
              {"prefix":"AngelThump","image":[{"url":"https://r2cdn.destiny.gg/emotes/6519114b8b7a8.png","mime":"image/png","height":32,"width":12600}]},
              {"prefix":"monkaS","image":[{"url":"https://r2cdn.destiny.gg/emotes/61584b37bc3a3.png","mime":"image/png","height":32,"width":59}]}
            ]
        """.trimIndent()
        val emotes = ChatAssets.parseEmotes(json, dggL + "\n" + animated).associateBy { it.prefix }
        emotes.getValue("dggL").let {
            assertTrue(it.cropped)
            assertEquals(32, it.width)
            assertEquals(-672, it.restOffset)
            assertNull(it.sprite)
        }
        assertNotNull(emotes.getValue("AngelThump").sprite)
        emotes.getValue("monkaS").let {
            assertFalse(it.cropped)
            assertEquals(59, it.width)
        }
    }

    @Test
    fun aMalformedEntryOnlyLosesThatEmote() {
        val json = """
            [
              {"prefix":"good","image":[{"url":"https://x/a.png","height":32,"width":32}]},
              {"prefix":12345,"image":"not a list"},
              {"image":[]},
              {"prefix":"alsoGood","image":[{"url":"https://x/b.png","height":32,"width":40}]}
            ]
        """.trimIndent()
        assertEquals(listOf("good", "alsoGood"), ChatAssets.parseEmotes(json, "").map { it.prefix })
        val flairs = ChatAssets.parseFlairs("""[{"name":"flair1","label":"Tier 2"},{"label":"no name"},{"name":"flair3","label":"Tier 3","color":"#fff"}]""")
        assertEquals(setOf("flair1", "flair3"), flairs.keys)
    }
}
