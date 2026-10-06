package io.github.jordanwoodson.tinyscreen.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The default network: whether there is one, and whether it's metered (mobile data, hotspots),
 * which picks between the Wi-Fi and mobile quality settings. VPNs such as Tailscale report the
 * metering of the network underneath.
 */
class NetworkMonitor(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)

    private val _metered = MutableStateFlow(connectivity.isActiveNetworkMetered)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    /**
     * True while the default network reaches the internet (Android has validated it). Collect the
     * false → true edges to retry after a dead zone: a network that has only just connected often
     * can't resolve names yet, so retrying on connect alone would fail and back off.
     */
    private val _online = MutableStateFlow(
        connectivity.activeNetwork?.let(connectivity::getNetworkCapabilities)?.let(::reachesInternet) == true,
    )
    val online: StateFlow<Boolean> = _online.asStateFlow()

    init {
        connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            // For the default network this means there's none left: a replacement arrives through
            // onCapabilitiesChanged instead, and activeNetwork can still name the lost one here
            override fun onLost(network: Network) {
                _online.value = false
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _online.value = reachesInternet(caps)
                _metered.value = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TEMPORARILY_NOT_METERED)
            }
        })
    }

    private fun reachesInternet(caps: NetworkCapabilities): Boolean =
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
