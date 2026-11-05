package org.ghostsinthelab.app.ringomote.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.Socket

/**
 * Binds sockets to the phone's Wi-Fi or Ethernet network rather than whatever the routing
 * policy would pick.
 *
 * With a VPN up, Android sends every app's traffic into the tunnel, local addresses
 * included, so a plain socket to the Apple TV never reaches it (seen on a Nokia G50 on
 * 2026-11-05). Binding the socket to the non-VPN network keeps the Companion link on the
 * LAN. Needs `ACCESS_NETWORK_STATE`.
 */
class LocalNetwork(context: Context) {
    private val connectivity =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    /** The first Wi-Fi or Ethernet network that is not itself a VPN; the active one if it fits. */
    fun network(): Network? {
        val candidates = listOfNotNull(connectivity.activeNetwork) + connectivity.allNetworks
        return candidates.firstOrNull { network ->
            val caps = connectivity.getNetworkCapabilities(network) ?: return@firstOrNull false
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }

    /** Binds [socket] to [network] when there is one; a no-op otherwise. Call before connect. */
    fun bind(socket: Socket) {
        network()?.bindSocket(socket)
    }
}
