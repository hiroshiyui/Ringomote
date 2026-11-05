package org.ghostsinthelab.app.ringomote.network

import kotlinx.coroutines.flow.StateFlow

/**
 * An Apple TV found on the local network through Bonjour (mDNS / DNS-SD).
 *
 * Every Apple TV advertises `_companion-link._tcp`, the service the Apple Remote on an iPhone
 * talks to. Its TXT record names the hardware model (`rpMd`, e.g. `AppleTV14,1`), which is
 * how an Apple TV is told apart from the iPhones, Macs and HomePods that advertise the same
 * service. [id] is the service instance name, stable for one device on one network.
 */
data class AppleTv(
    val id: String,
    val name: String,
    val model: String,
    val host: String,
    val port: Int,
) {
    /** The marketing name for [model] when known ("Apple TV 4K (3rd generation)"), else the model id. */
    val modelName: String get() = AppleTvModels.nameOf(model)
}

/**
 * Browses the local network for Apple TVs while started, publishing what it has found so far.
 *
 * Browsing costs radio and battery, so callers start it only while a list is on screen and
 * stop it when the list goes away; [appleTvs] keeps the last result in between.
 */
interface AppleTvDiscovery {
    val appleTvs: StateFlow<List<AppleTv>>

    /** Whether a browse is running; the UI shows a progress indicator while true. */
    val browsing: StateFlow<Boolean>

    /** Starts browsing; idempotent. Clears the previous result so stale devices do not linger. */
    fun start()

    /** Stops browsing; idempotent. */
    fun stop()
}

/** Bonjour facts shared by the discovery implementations and their tests. */
object CompanionLink {
    /** The DNS-SD service type Apple TVs (and other Apple devices) register. */
    const val SERVICE_TYPE = "_companion-link._tcp."

    /** TXT key holding the hardware model, e.g. `AppleTV14,1`. */
    const val TXT_MODEL = "rpMd"

    /** True if a `rpMd` value names an Apple TV rather than an iPhone, Mac or HomePod. */
    fun isAppleTv(model: String?): Boolean = model != null && model.startsWith("AppleTV")
}

/** Marketing names for Apple TV hardware identifiers, for the list in Settings. */
object AppleTvModels {
    private val names = mapOf(
        "AppleTV2,1" to "Apple TV (2nd generation)",
        "AppleTV3,1" to "Apple TV (3rd generation)",
        "AppleTV3,2" to "Apple TV (3rd generation)",
        "AppleTV5,3" to "Apple TV HD",
        "AppleTV6,2" to "Apple TV 4K",
        "AppleTV11,1" to "Apple TV 4K (2nd generation)",
        "AppleTV14,1" to "Apple TV 4K (3rd generation)",
    )

    fun nameOf(model: String): String = names[model] ?: model
}
