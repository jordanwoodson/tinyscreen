package io.github.jordanwoodson.tinyscreen

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.OrientationEventListener
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.net.toUri
import androidx.core.util.Consumer
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import io.github.jordanwoodson.tinyscreen.player.PlaybackService
import io.github.jordanwoodson.tinyscreen.ui.TinyscreenRoot
import io.github.jordanwoodson.tinyscreen.web.WebEmbedHolder
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** What the Compose UI can ask of the hosting activity. */
interface HostActions {
    fun enterPip()
    fun setFullscreen(fullscreen: Boolean)
    fun setVideoBounds(bounds: Rect?)
    fun openExternal(url: String)
}

class MainActivity : ComponentActivity(), HostActions {
    private val graph get() = (application as TinyscreenApp).graph
    private val webEmbed: WebEmbedHolder by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val inPip = mutableStateOf(false)
    val isLandscape = mutableStateOf(false)

    /** Links handed to the app (deep links, shares) waiting to be opened by the UI. */
    val incomingLinks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    private var videoBounds: Rect? = null
    private var lockedOrientation: Int? = null
    private var orientationListener: OrientationEventListener? = null

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_VIDEO_SIZE_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                updatePipParams()
                window.decorView.keepScreenOn = player.isPlaying
            }
        }
    }

    private var leftPipAt = 0L

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        if (inPip.value && !info.isInPictureInPictureMode) leftPipAt = System.currentTimeMillis()
        inPip.value = info.isInPictureInPictureMode
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        isLandscape.value = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        applySystemBars()
        webEmbed.attach(this)
        addOnPictureInPictureModeChangedListener(pipListener)
        graph.player.player.addListener(playerListener)
        lifecycleScope.launch {
            graph.settings.settings.collect { updatePipParams() }
        }

        setContent {
            TinyscreenRoot(activity = this, host = this)
        }
        handleIntent(intent)

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                val target = lockedOrientation ?: return
                if (degrees == ORIENTATION_UNKNOWN) return
                val physicallyLandscape = degrees in 60..120 || degrees in 240..300
                val physicallyPortrait = degrees < 30 || degrees > 330
                val matches = when (target) {
                    ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> physicallyLandscape
                    else -> physicallyPortrait
                }
                // Hand rotation back to the sensor once the phone is held the way we forced it,
                // but only if the user has auto-rotate on.
                if (matches && autoRotateEnabled()) {
                    lockedOrientation = null
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return
        incomingLinks.tryEmit(link)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller lets the session service own the media notification
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener?.disable()
        val settings = graph.settings.settings.value
        // Swiping the PiP window away stops the activity right after it leaves PiP: treat that as "close"
        val pipDismissed = System.currentTimeMillis() - leftPipAt < 1_500
        if (pipDismissed || (!settings.backgroundPlayback && !isInPictureInPictureMode && !isChangingConfigurations)) {
            graph.player.player.pause()
        }
    }

    override fun onDestroy() {
        controllerFuture?.let { MediaController.releaseFuture(it) }
        webEmbed.detach(this)
        graph.player.player.removeListener(playerListener)
        removeOnPictureInPictureModeChangedListener(pipListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        isLandscape.value = newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE
        applySystemBars()
    }

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape.value) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    private fun autoRotateEnabled(): Boolean =
        Settings.System.getInt(contentResolver, Settings.System.ACCELEROMETER_ROTATION, 0) == 1

    // --- HostActions ---

    override fun enterPip() {
        val player = graph.player.player
        if (player.mediaItemCount == 0) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    override fun setFullscreen(fullscreen: Boolean) {
        val target = if (fullscreen) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        lockedOrientation = target
        requestedOrientation = target
    }

    override fun setVideoBounds(bounds: Rect?) {
        if (bounds == videoBounds) return
        videoBounds = bounds
        updatePipParams()
    }

    override fun openExternal(url: String) {
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(this, url.toUri())
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        }
    }

    // --- Picture in picture ---

    private fun buildPipParams(): PictureInPictureParams {
        val player = graph.player.player
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0) {
            val r = size.width.toFloat() * size.pixelWidthHeightRatio / size.height
            when {
                r > 2.39f -> Rational(239, 100)
                r < 1 / 2.39f -> Rational(100, 239)
                else -> Rational((size.width * size.pixelWidthHeightRatio).toInt(), size.height)
            }
        } else {
            Rational(16, 9)
        }
        val settings = graph.settings.settings.value
        return PictureInPictureParams.Builder()
            .setAspectRatio(ratio)
            .setAutoEnterEnabled(settings.autoPip && player.isPlaying)
            .setSeamlessResizeEnabled(false)
            .apply { videoBounds?.let { setSourceRectHint(it) } }
            .build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }
}
