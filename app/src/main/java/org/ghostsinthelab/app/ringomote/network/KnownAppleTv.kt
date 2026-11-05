package org.ghostsinthelab.app.ringomote.network

/**
 * An Apple TV the phone knows, merged by its user-given name, which is the key Bonjour and
 * the stored pairings share. [pairedForWaking] means a Companion Link pairing is stored;
 * [onNetwork] that Bonjour saw it during the latest browse.
 */
data class KnownAppleTv(
    val name: String,
    val model: String? = null,
    val pairedForWaking: Boolean = false,
    val onNetwork: Boolean = false,
) {
    val modelName: String? get() = model?.let(AppleTvModels::nameOf)
}

/**
 * Merges Companion pairings ([pairedNames]) and the latest Bonjour result ([discovered]) into
 * one list, sorted by name.
 */
fun mergeKnownAppleTvs(pairedNames: Set<String>, discovered: List<AppleTv>): List<KnownAppleTv> {
    val names = LinkedHashSet<String>()
    names += pairedNames
    names += discovered.map { it.name }
    return names.map { name ->
        KnownAppleTv(
            name = name,
            model = discovered.firstOrNull { it.name == name }?.model,
            pairedForWaking = name in pairedNames,
            onNetwork = discovered.any { it.name == name },
        )
    }.sortedBy { it.name }
}
