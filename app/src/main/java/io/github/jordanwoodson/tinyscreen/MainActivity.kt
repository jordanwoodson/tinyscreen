package io.github.jordanwoodson.tinyscreen

import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.view.OrientationEventListener
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.MultiWindowModeChangedInfo
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
import io.github.jordanwoodson.tinyscreen.chat.ChatNotifier
import io.github.jordanwoodson.tinyscreen.player.PlaybackService
import io.github.jordanwoodson.tinyscreen.ui.TinyscreenRoot
import io.github.jordanwoodson.tinyscreen.web.WebEmbedHolder
import io.github.jordanwoodson.tinyscreen.web.isBigscreenLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** What the Compose UI can ask of the hosting activity. */
interface HostActions {
    fun enterPip()
    fun setFullscreen(fullscreen: Boolean)
    /** Whether [setFullscreen] can turn the phone: not in split-screen, PiP or on large screens. Compose-observable. */
    val canRotate: Boolean
    fun setVideoBounds(bounds: Rect?)
    fun openExternal(url: String)
}

class MainActivity : ComponentActivity(), HostActions {
    private val graph get() = (application as TinyscreenApp).graph
    private val webEmbed: WebEmbedHolder by viewModels()
    private var controllerFuture: ListenableFuture<MediaController>? = null

    val inPip = mutableStateOf(false)
    /** The window is wider than it's tall, so the UI uses the landscape layout. */
    val isLandscape = mutableStateOf(false)
    /**
     * The landscape layout fills the screen and turning the phone back to portrait is possible. Not in
     * split-screen, freeform windows or on large screens, where Android ignores orientation requests.
     */
    val isFullscreen = mutableStateOf(false)
    private val canRotateState = mutableStateOf(true)
    override val canRotate: Boolean get() = canRotateState.value

    /** Links handed to the app (deep links, shares) waiting to be opened by the UI. */
    val incomingLinks = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)

    /** A whisper notification was tapped: the UI shows the Whispers screen. */
    val openWhispers = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 1)

    private var videoBounds: Rect? = null
    private var lockedOrientation: Int? = null
    private var orientationListener: OrientationEventListener? = null

    /** An HTML5 player's fullscreen view, covering the whole window. */
    private var webFullscreenView: View? = null
    private var webFullscreenBack: OnBackPressedCallback? = null
    private var rotatedForWebFullscreen = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.containsAny(
                    Player.EVENT_IS_PLAYING_CHANGED,
                    Player.EVENT_PLAY_WHEN_READY_CHANGED,
                    Player.EVENT_VIDEO_SIZE_CHANGED,
                    Player.EVENT_PLAYBACK_STATE_CHANGED,
                )
            ) {
                onPlayingChanged()
            }
        }
    }

    private var leftPipAt = 0L

    private val pipListener = Consumer<PictureInPictureModeChangedInfo> { info ->
        if (inPip.value && !info.isInPictureInPictureMode) leftPipAt = System.currentTimeMillis()
        inPip.value = info.isInPictureInPictureMode
        updateWindowState(resources.configuration)
    }

    private val multiWindowListener = Consumer<MultiWindowModeChangedInfo> {
        updateWindowState(resources.configuration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        updateWindowState(resources.configuration)
        webEmbed.attach(this, openUrl = ::openExternal, showFullscreen = ::showWebFullscreen)
        addOnPictureInPictureModeChangedListener(pipListener)
        addOnMultiWindowModeChangedListener(multiWindowListener)
        graph.player.player.addListener(playerListener)
        lifecycleScope.launch {
            graph.settings.settings.collect { updatePipParams() }
        }
        lifecycleScope.launch {
            webEmbed.playing.collect { onPlayingChanged() }
        }

        setContent {
            TinyscreenRoot(activity = this, host = this)
        }
        // Only a fresh launch brings a new link: recreation (font size, language) and returning after
        // the process was killed redeliver the intent that first opened the app
        if (savedInstanceState == null) handleIntent(intent)

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
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        // Relaunched from Recents with the intent it was first opened with
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        intent.getStringExtra(ChatNotifier.EXTRA_WHISPER_FROM)?.let { nick ->
            graph.chat.openWhisper(nick)
            openWhispers.tryEmit(Unit)
            return
        }
        val link = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            // Shared styled text arrives as a CharSequence, which getStringExtra drops
            Intent.ACTION_SEND -> intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            else -> null
        } ?: return
        incomingLinks.tryEmit(link)
    }

    override fun onStart() {
        super.onStart()
        // Binding a controller lets the session service own the media notification. It's released in
        // onStop: once playing, the service keeps itself running in the foreground without us.
        val token = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = MediaController.Builder(this, token).buildAsync()
        orientationListener?.takeIf { it.canDetectOrientation() }?.enable()
        webEmbed.onForeground()
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
        // A web player has no media notification to stop it from, so it never plays in the background
        if (!isInPictureInPictureMode && !isChangingConfigurations) webEmbed.onBackground()
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
    }

    override fun onDestroy() {
        webEmbed.detach(this)
        graph.player.player.removeListener(playerListener)
        removeOnPictureInPictureModeChangedListener(pipListener)
        removeOnMultiWindowModeChangedListener(multiWindowListener)
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateWindowState(newConfig)
    }

    private fun updateWindowState(config: Configuration) {
        isLandscape.value = config.orientation == Configuration.ORIENTATION_LANDSCAPE
        canRotateState.value = canRotate(config)
        isFullscreen.value = isLandscape.value && canRotateState.value
        applySystemBars()
    }

    /** Whether orientation requests take effect: Android ignores them in multi-window and on large screens. */
    private fun canRotate(config: Configuration = resources.configuration): Boolean =
        !isInMultiWindowMode && !isInPictureInPictureMode && config.smallestScreenWidthDp < 600

    private fun applySystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape.value || webFullscreenView != null) {
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
        if (player.mediaItemCount == 0 && !webEmbed.active.value) return
        runCatching { enterPictureInPictureMode(buildPipParams()) }
    }

    override fun setFullscreen(fullscreen: Boolean) {
        if (!canRotate()) return
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
        val uri = url.toUri()
        // Tinyscreen claims destiny.gg/bigscreen links itself, so Android could hand those straight
        // back to us: send them to a browser by name, or nowhere
        val browser = if (isBigscreenLink(url)) browserPackage() ?: return else null
        runCatching {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .apply { browser?.let { intent.setPackage(it) } }
                .launchUrl(this, uri)
        }.onFailure {
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri).setPackage(browser)) }
        }
    }

    /** The browser that provides Custom Tabs, or else any app that opens web pages; never Tinyscreen. */
    private fun browserPackage(): String? {
        CustomTabsClient.getPackageName(this, null)?.takeIf { it != packageName }?.let { return it }
        val web = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
        return packageManager.queryIntentActivities(web, PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.packageName }
            .firstOrNull { it != packageName }
    }

    /** Shows an HTML5 player's fullscreen [view] over the whole window, turning the phone sideways; null ends it. */
    private fun showWebFullscreen(view: View?) {
        val decor = window.decorView as ViewGroup
        webFullscreenView?.let { decor.removeView(it) }
        webFullscreenBack?.remove()
        webFullscreenBack = null
        webFullscreenView = view
        if (view != null) {
            decor.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // Added last, so Back leaves the player's fullscreen before anything else
            webFullscreenBack = onBackPressedDispatcher.addCallback(this) { webEmbed.exitFullscreen() }
            if (!isLandscape.value) {
                rotatedForWebFullscreen = true
                setFullscreen(true)
            }
        } else if (rotatedForWebFullscreen) {
            rotatedForWebFullscreen = false
            setFullscreen(false)
        }
        applySystemBars()
    }

    private fun onPlayingChanged() {
        updatePipParams()
        window.decorView.keepScreenOn = graph.player.player.isPlaying || webEmbed.playing.value
    }

    // --- Picture in picture ---

    private fun buildPipParams(): PictureInPictureParams {
        val player = graph.player.player
        val size = player.videoSize
        val ratio = if (size.width > 0 && size.height > 0 && !webEmbed.active.value) {
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
            .setAutoEnterEnabled(settings.autoPip && (player.wantsToPlay() || webEmbed.playing.value))
            .setSeamlessResizeEnabled(false)
            .apply { videoBounds?.let { setSourceRectHint(it) } }
            .build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(buildPipParams()) }
    }

    /** Playing or about to: pressing Home mid-rebuffer should still go to picture-in-picture. */
    private fun Player.wantsToPlay(): Boolean =
        playWhenReady && (playbackState == Player.STATE_READY || playbackState == Player.STATE_BUFFERING)
}
