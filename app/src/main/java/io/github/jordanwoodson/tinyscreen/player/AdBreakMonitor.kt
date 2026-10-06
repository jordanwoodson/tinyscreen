package io.github.jordanwoodson.tinyscreen.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Set while the playing stream's playlist filter is waiting out an ad break, so the player can say so. */
object AdBreakMonitor {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private var owner: Any? = null

    /** A new stream started: only its filter may report ad breaks from now on. */
    @Synchronized
    fun claim(owner: Any?) {
        this.owner = owner
        _active.value = false
    }

    /** From a filter on a loader thread; a filter still finishing for an old stream is ignored. */
    @Synchronized
    fun set(from: Any, active: Boolean) {
        if (from === owner) _active.value = active
    }
}
