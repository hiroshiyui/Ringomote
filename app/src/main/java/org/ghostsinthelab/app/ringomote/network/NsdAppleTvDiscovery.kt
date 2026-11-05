package org.ghostsinthelab.app.ringomote.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.util.Log
import java.util.ArrayDeque
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [AppleTvDiscovery] on Android's [NsdManager].
 *
 * Found services are resolved one at a time, because [NsdManager.resolveService] fails with
 * `FAILURE_ALREADY_ACTIVE` when two resolves overlap; a queue serialises them. A device is
 * listed only once its TXT record says it is an Apple TV (see [CompanionLink.isAppleTv]).
 *
 * A [WifiManager.MulticastLock] is held while browsing: without it many Wi-Fi chips drop the
 * multicast replies that mDNS consists of, and the list stays empty. Needs
 * `CHANGE_WIFI_MULTICAST_STATE`, a normal permission.
 */
class NsdAppleTvDiscovery(context: Context) : AppleTvDiscovery {
    private val appContext = context.applicationContext
    private val nsd = appContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _appleTvs = MutableStateFlow<List<AppleTv>>(emptyList())
    override val appleTvs: StateFlow<List<AppleTv>> = _appleTvs.asStateFlow()
    private val _browsing = MutableStateFlow(false)
    override val browsing: StateFlow<Boolean> = _browsing.asStateFlow()

    private val lock = Any()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private val pending = ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    override fun start() {
        synchronized(lock) {
            if (listener != null) return
            _appleTvs.value = emptyList()
            multicastLock = wifi.createMulticastLock("ringomote-bonjour").apply {
                setReferenceCounted(false)
                acquire()
            }
            val l = Listener()
            listener = l
            try {
                nsd.discoverServices(CompanionLink.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "discoverServices rejected", e)
                teardown()
            }
        }
    }

    override fun stop() {
        synchronized(lock) {
            val l = listener ?: return
            try {
                nsd.stopServiceDiscovery(l)
            } catch (e: IllegalArgumentException) {
                // Already stopped (e.g. after onStartDiscoveryFailed); nothing to undo.
            }
            teardown()
        }
    }

    private fun teardown() {
        listener = null
        pending.clear()
        resolving = false
        multicastLock?.release()
        multicastLock = null
        _browsing.value = false
    }

    private fun enqueue(service: NsdServiceInfo) {
        synchronized(lock) {
            if (listener == null) return
            pending.add(service)
            resolveNext()
        }
    }

    /** Must hold [lock]. */
    private fun resolveNext() {
        if (resolving) return
        val next = pending.poll() ?: return
        resolving = true
        @Suppress("DEPRECATION") // registerServiceInfoCallback needs API 34; minSdk is 28.
        nsd.resolveService(next, Resolver())
    }

    private fun onResolved(info: NsdServiceInfo) {
        val model = info.attributes[CompanionLink.TXT_MODEL]?.toString(Charsets.UTF_8)
        if (!CompanionLink.isAppleTv(model)) return
        val host = info.host?.hostAddress ?: return
        val tv = AppleTv(
            id = info.serviceName,
            name = info.serviceName,
            model = model!!,
            host = host,
            port = info.port,
        )
        _appleTvs.update { list -> (list.filter { it.id != tv.id } + tv).sortedBy { it.name } }
    }

    private inner class Listener : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            _browsing.value = true
        }

        override fun onDiscoveryStopped(serviceType: String) {
            _browsing.value = false
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery failed to start: $errorCode")
            synchronized(lock) { teardown() }
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "Discovery failed to stop: $errorCode")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            if (service.serviceType.trimEnd('.') != CompanionLink.SERVICE_TYPE.trimEnd('.')) return
            enqueue(service)
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            _appleTvs.update { list -> list.filter { it.id != service.serviceName } }
        }
    }

    private inner class Resolver : NsdManager.ResolveListener {
        override fun onServiceResolved(info: NsdServiceInfo) {
            onResolved(info)
            synchronized(lock) {
                resolving = false
                resolveNext()
            }
        }

        override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
            Log.w(TAG, "Resolve failed for ${info.serviceName}: $errorCode")
            synchronized(lock) {
                resolving = false
                resolveNext()
            }
        }
    }

    private companion object {
        const val TAG = "AppleTvDiscovery"
    }
}
