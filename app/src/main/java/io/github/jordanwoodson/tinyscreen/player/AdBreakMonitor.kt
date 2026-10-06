package io.github.jordanwoodson.tinyscreen.player

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Set while the playlist filter is stripping an ad break, so the player can say so. */
object AdBreakMonitor {
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    fun set(active: Boolean) {
        if (_active.value != active) _active.value = active
    }
}
