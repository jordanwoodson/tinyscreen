package io.github.jordanwoodson.tinyscreen.chat.ui

import android.content.Context
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.gif.repeatCount
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.toBitmap
import io.github.jordanwoodson.tinyscreen.chat.Emote
import io.github.jordanwoodson.tinyscreen.chat.SpriteAnimation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Draws an emote at its CSS size; sprite sheets are shown through a one-frame window and stepped. */
@Composable
fun EmoteImage(emote: Emote, scale: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val w: Dp = (emote.width * scale).dp
    val h: Dp = (emote.height * scale).dp
    if (!emote.cropped) {
        val context = LocalContext.current
        val request = remember(emote.url, animate) {
            ImageRequest.Builder(context)
                .data(emote.url)
                .apply { if (!animate) repeatCount(0) }
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = emote.prefix,
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.Medium,
            modifier = modifier.size(w, h),
        )
        return
    }

    val context = LocalContext.current
    var bitmap by remember(emote.url) { mutableStateOf(SpriteSheets.peek(emote.url)) }
    if (bitmap == null) {
        LaunchedEffect(emote.url) { bitmap = SpriteSheets.load(context, emote.url) }
    }
    val sprite = emote.sprite
    // Read only while drawing, so stepping a frame redraws the canvas without recomposing
    var offset by remember(emote) { mutableIntStateOf(sprite?.let { resting(emote, it) } ?: emote.restOffset) }
    var plays by remember { mutableIntStateOf(0) }
    if (sprite != null) {
        LaunchedEffect(bitmap != null, plays, animate, emote) {
            if (bitmap == null) return@LaunchedEffect
            if (!animate && plays == 0) {
                offset = resting(emote, sprite)
                return@LaunchedEffect
            }
            // steps(n): n equal intervals per play, so the canvas only changes at the sprite's own frame rate
            val frameMs = (sprite.durationMs.toLong() / sprite.frames).coerceAtLeast(16)
            val iterations = if (plays > 0) 1 else if (sprite.iterations == 0) Int.MAX_VALUE else sprite.iterations
            repeat(iterations) {
                for (frame in 0 until sprite.frames) {
                    offset = sprite.offsetAt(frame)
                    delay(frameMs)
                }
            }
            offset = resting(emote, sprite)
        }
    }
    Canvas(
        modifier
            .size(w, h)
            .semantics { contentDescription = emote.prefix }
            .then(
                if (sprite != null) {
                    // Tapping replays it, like hovering on the site
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { plays++ }
                } else {
                    Modifier
                },
            ),
    ) {
        val bmp = bitmap ?: return@Canvas
        // Image px per CSS px, in case the decoded bitmap isn't the size emotes.json says
        val k = bmp.width.toFloat() / emote.sheetWidth.coerceAtLeast(1)
        val srcX = (Math.floorMod(-offset, emote.sheetWidth.coerceAtLeast(1)) * k).roundToInt().coerceIn(0, bmp.width - 1)
        val srcW = (emote.width * k).roundToInt().coerceIn(1, bmp.width - srcX)
        val srcH = (emote.height * k).roundToInt().coerceIn(1, bmp.height)
        drawImage(
            image = bmp,
            srcOffset = IntOffset(srcX, 0),
            srcSize = IntSize(srcW, srcH),
            dstSize = IntSize((size.width * srcW / (emote.width * k)).roundToInt(), (size.height * srcH / (emote.height * k)).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}

private fun resting(emote: Emote, sprite: SpriteAnimation) = if (sprite.holdEnd) sprite.end else emote.restOffset

/**
 * Decoded sprite sheets, shared by every copy of an emote on screen: a busy combo shows the same
 * strip dozens of times, and each used to decode and hold its own bitmap.
 */
private object SpriteSheets {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cache = object : LruCache<String, ImageBitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val loading = HashMap<String, Deferred<ImageBitmap?>>()

    fun peek(url: String): ImageBitmap? = cache.get(url)

    /** Call on the main thread. */
    suspend fun load(context: Context, url: String): ImageBitmap? {
        cache.get(url)?.let { return it }
        val pending = loading.getOrPut(url) {
            scope.async {
                val result = context.applicationContext.imageLoader.execute(
                    ImageRequest.Builder(context.applicationContext).data(url).allowHardware(false)
                        // Long strips (up to ~12k px) must not be downsampled or frames drift
                        .maxBitmapSize(coil3.size.Size.ORIGINAL)
                        .size(coil3.size.Size.ORIGINAL)
                        .build(),
                )
                (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()?.also { cache.put(url, it) }
            }
        }
        return try {
            pending.await()
        } finally {
            if (pending.isCompleted) loading.remove(url)
        }
    }
}
