package io.github.jordanwoodson.tinyscreen

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.jordanwoodson.tinyscreen.chat.ChatAssets
import io.github.jordanwoodson.tinyscreen.chat.ChatNotifier
import io.github.jordanwoodson.tinyscreen.chat.ChatRepository
import io.github.jordanwoodson.tinyscreen.player.PlayerHolder
import io.github.jordanwoodson.tinyscreen.settings.SettingsRepository
import io.github.jordanwoodson.tinyscreen.stream.LiveRepository
import io.github.jordanwoodson.tinyscreen.stream.resolvers.KickResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.KickVodResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.RumbleResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.StreamResolvers
import io.github.jordanwoodson.tinyscreen.stream.resolvers.TwitchResolver
import io.github.jordanwoodson.tinyscreen.stream.resolvers.YouTubeResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Hand-rolled dependency graph; everything here lives as long as the process. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsRepository(app, scope)
    val player by lazy { PlayerHolder(app, scope) }
    val live by lazy { LiveRepository(app, scope) }
    val chatAssets by lazy { ChatAssets(app) }
    val chat by lazy { ChatRepository(app, scope, settings, chatAssets) }
    private val notifier by lazy { ChatNotifier(app) }
    val resolvers by lazy {
        StreamResolvers(
            listOf(
                KickResolver(),
                KickVodResolver(),
                TwitchResolver(),
                YouTubeResolver(),
                RumbleResolver(),
            ),
        )
    }

    @Volatile var inForeground = false
        private set

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            private var idleJob: Job? = null
            private var pausedSockets = false

            override fun onStart(owner: LifecycleOwner) {
                inForeground = true
                idleJob?.cancel()
                if (pausedSockets) {
                    pausedSockets = false
                    live.start()
                    if (!settings.settings.value.webChat) chat.start()
                }
            }

            override fun onStop(owner: LifecycleOwner) {
                inForeground = false
                // Keep sockets while something is playing (PiP / background audio); otherwise
                // drop them after a couple of minutes to save battery.
                idleJob = scope.launch {
                    delay(120_000)
                    if (!player.player.isPlaying) {
                        pausedSockets = true
                        chat.stop()
                        live.stop()
                    }
                }
            }
        })
        scope.launch {
            chat.alerts.collect { (title, text) -> if (!inForeground) notifier.show(title, text) }
        }
    }
}
