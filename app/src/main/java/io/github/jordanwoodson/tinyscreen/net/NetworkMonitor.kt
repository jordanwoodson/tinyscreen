package io.github.jordanwoodson.tinyscreen.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the default network is metered (mobile data, hotspots), which picks between the Wi-Fi and
 * mobile quality settings. VPNs such as Tailscale report the metering of the network underneath.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _metered = MutableStateFlow(connectivity.isActiveNetworkMetered)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            }
        })
    }
}
