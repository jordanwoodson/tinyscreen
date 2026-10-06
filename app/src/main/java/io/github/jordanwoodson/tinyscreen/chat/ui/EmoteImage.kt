package io.github.jordanwoodson.tinyscreen.chat.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.gif.repeatCount
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.maxBitmapSize
import coil3.toBitmap
import coil3.imageLoader
import io.github.jordanwoodson.tinyscreen.chat.Emote
import kotlin.math.roundToInt

/** Draws an emote at its CSS size; sprite strips are animated frame by frame. */
@Composable
fun EmoteImage(emote: Emote, scale: Float, animate: Boolean, modifier: Modifier = Modifier) {
    val w: Dp = (emote.width * scale).dp
    val h: Dp = (emote.height * scale).dp
    val sprite = emote.sprite
    if (sprite == null) {
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
    var bitmap by remember(emote.url) { androidx.compose.runtime.mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(emote.url) {
        val result = context.imageLoader.execute(
            ImageRequest.Builder(context).data(emote.url).allowHardware(false)
                // Long strips (up to ~12k px) must not be downsampled or frames drift
                .maxBitmapSize(coil3.size.Size.ORIGINAL)
                .size(coil3.size.Size.ORIGINAL)
                .build(),
        )
        bitmap = (result as? SuccessResult)?.image?.toBitmap()?.asImageBitmap()
    }
    var plays by remember { mutableIntStateOf(0) }
    val frame = remember(emote.url) { Animatable(0f) }
    LaunchedEffect(bitmap, plays, animate) {
        if (bitmap == null) return@LaunchedEffect
        if (!animate && plays == 0) {
            frame.snapTo(if (sprite.holdLastFrame) (sprite.frames - 1).toFloat() else 0f)
            return@LaunchedEffect
        }
        val iterations = if (plays > 0) 1 else if (sprite.iterations == 0) Int.MAX_VALUE else sprite.iterations
        repeat(iterations) {
            frame.snapTo(0f)
            frame.animateTo(sprite.frames.toFloat(), tween(sprite.durationMs, easing = LinearEasing))
        }
        frame.snapTo(if (sprite.holdLastFrame) (sprite.frames - 1).toFloat() else 0f)
    }
    val bmp = bitmap
    Canvas(
        modifier
            .size(w, h)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { plays++ },
    ) {
        bmp ?: return@Canvas
        val frames = sprite.frames.coerceAtLeast(1)
        val frameW = bmp.width.toFloat() / frames
        val index = frame.value.toInt().coerceIn(0, frames - 1)
        val left = (index * frameW).roundToInt().coerceAtMost(bmp.width - 1)
        drawImage(
            image = bmp,
            srcOffset = IntOffset(left, 0),
            srcSize = IntSize(frameW.roundToInt().coerceIn(1, bmp.width - left), bmp.height),
            dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
    }
}
