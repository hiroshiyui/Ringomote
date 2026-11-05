package org.ghostsinthelab.app.ringomote.network

import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice

/**
 * An Apple TV the phone knows from any source, merged by its user-given name.
 *
 * Apple exposes the same name over Bonjour and as the Bluetooth device name, and it is the
 * only identifier the two links share (Bluetooth addresses rotate), so the name is the key.
 * [pairedForWaking] means a Companion Link pairing is stored; [onNetwork] that Bonjour saw it
 * during the latest browse; [bonded] that the phone has it as a Bluetooth bond;
 * [connected] that it is the host attached to the remote right now.
 */
data class KnownAppleTv(
    val name: String,
    val model: String? = null,
    val pairedForWaking: Boolean = false,
    val onNetwork: Boolean = false,
    val bonded: Boolean = false,
    val connected: Boolean = false,
) {
    val modelName: String? get() = model?.let(AppleTvModels::nameOf)
}

/**
 * Merges Companion pairings ([pairedNames]), the latest Bonjour result ([discovered]), the
 * phone's bonded Bluetooth devices ([bonded]) and the connected host into one list, sorted by
 * name. Bonded devices are included only if they look like an Apple TV: either their name
 * matches a pairing or a discovered TV, or [bondedLooksLikeAppleTv] says so, since the bond
 * list also holds headphones and cars.
 */
fun mergeKnownAppleTvs(
    pairedNames: Set<String>,
    discovered: List<AppleTv>,
    bonded: List<HostDevice>,
    connectedHost: HostDevice?,
    bondedLooksLikeAppleTv: (HostDevice) -> Boolean = { false },
): List<KnownAppleTv> {
    val names = LinkedHashSet<String>()
    names += pairedNames
    names += discovered.map { it.name }
    names += bonded.filter { it.name != null && (it.name in names || bondedLooksLikeAppleTv(it)) }.mapNotNull { it.name }
    connectedHost?.name?.takeIf { it in names || bondedLooksLikeAppleTv(connectedHost) }?.let { names += it }
    return names.map { name ->
        KnownAppleTv(
            name = name,
            model = discovered.firstOrNull { it.name == name }?.model,
            pairedForWaking = name in pairedNames,
            onNetwork = discovered.any { it.name == name },
            bonded = bonded.any { it.name == name },
            connected = connectedHost?.name == name,
        )
    }.sortedBy { it.name }
}
