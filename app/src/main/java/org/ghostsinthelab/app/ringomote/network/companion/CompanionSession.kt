package org.ghostsinthelab.app.ringomote.network.companion

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.ghostsinthelab.app.ringomote.network.AppleTvDiscovery
import org.ghostsinthelab.app.ringomote.remote.RemoteControl

/** Where the remote link stands, shown in the connection panel. */
sealed interface CompanionState {
    /** No Apple TV to hold a session with, or the remote is not on screen. */
    data object Off : CompanionState

    /** Connecting to and verifying with [name]. */
    data class Connecting(val name: String) : CompanionState

    /** A remote session with [name] is open; button presses can be sent. */
    data class Connected(val name: String) : CompanionState

    /** [name] could not be reached; another attempt follows after a back-off. */
    data class Unreachable(val name: String) : CompanionState

    /** [name] refused the stored pairing; the user has to pair again. No retry until then. */
    data class Rejected(val name: String) : CompanionState
}

/**
 * One verified, session-started connection to an Apple TV, as [CompanionSession] needs it.
 * [send] blocks; both methods throw [IOException] when the link is gone.
 */
interface CompanionLink : Closeable {
    val name: String
    fun send(command: CompanionCommand, down: Boolean)
}

/**
 * Opens links for [CompanionSession]. [open] blocks on the calling thread and throws
 * [PairingException] when there is no usable pairing for [name] (none stored, or the Apple TV
 * refused it) and [IOException] when the TV cannot be reached.
 */
fun interface CompanionLinkPort {
    fun open(name: String): CompanionLink
}

/**
 * Keeps a Companion Link remote session open with one Apple TV while the remote needs it; it
 * is the one path button presses take, the same link the iOS Remote uses.
 *
 * The session has a [target] (the paired Apple TV the Remote tab is aimed at) and is
 * [wanted][setWanted] while the remote is on screen in the foreground. It connects when
 * both hold, keeps the socket open, and closes it [GRACE_MILLIS] after it stops being wanted,
 * so a quick tab switch does not cost a reconnect. Failures to reach the TV back off
 * ([BACKOFF_MILLIS]) and retry while wanted; a refused pairing stops retries ([CompanionState.Rejected])
 * until [retry] (the Settings tab pairs again) or the target changes.
 *
 * Input: [press] and [release] send `_hidC` down/up for the control's [CompanionKeyMap]
 * command; a send that fails drops the link, which reconnects by itself, and reports false.
 * Held commands are tracked so [releaseAll] can lift them when input stops, so the Apple TV
 * never sees a stuck key. Sends run on [ioDispatcher] under a mutex, in call order.
 *
 * Pure Kotlin behind [CompanionLinkPort]: [ClientPort] is the real one, tests use a fake.
 */
class CompanionSession(
    private val port: CompanionLinkPort,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
) {
    private data class Desire(val target: String? = null, val wanted: Boolean = false, val epoch: Int = 0)

    private val desire = MutableStateFlow(Desire())
    private val _state = MutableStateFlow<CompanionState>(CompanionState.Off)

    /** The link state, for the connection panel and the remote's enabled flag. */
    val state: StateFlow<CompanionState> = _state.asStateFlow()

    private val lock = Mutex()
    private var link: CompanionLink? = null
    private val held = LinkedHashSet<CompanionCommand>()

    init {
        scope.launch(ioDispatcher) { desire.collectLatest { run(it) } }
    }

    /** The Apple TV to hold a session with, by Bonjour name; null for none. */
    fun target(name: String?) {
        desire.update { if (it.target == name) it else it.copy(target = name, epoch = it.epoch + 1) }
        showConnectingEarly()
    }

    /** Whether the remote needs the Wi-Fi link right now. */
    fun setWanted(wanted: Boolean) {
        desire.update { if (it.wanted == wanted) it else it.copy(wanted = wanted) }
        showConnectingEarly()
    }

    /**
     * Reports Connecting as soon as a target is wanted, before the runner gets to it, so the
     * panel never shows "not connected, pair" for a frame while a pairing exists.
     */
    private fun showConnectingEarly() {
        val d = desire.value
        if (d.target != null && d.wanted) _state.compareAndSet(CompanionState.Off, CompanionState.Connecting(d.target))
    }

    /** Tries again after [CompanionState.Rejected], e.g. once a new pairing is stored. */
    fun retry() = desire.update { it.copy(epoch = it.epoch + 1) }

    /** Sends the control's button-down; false when not connected or the send failed. */
    suspend fun press(control: RemoteControl): Boolean {
        val command = CompanionKeyMap.commandFor(control)
        if (lock.withLock { command in held }) return true
        return send(command, down = true).also { if (it) lock.withLock { held += command } }
    }

    /** Sends the control's button-up; true when nothing was held. */
    suspend fun release(control: RemoteControl): Boolean {
        val command = CompanionKeyMap.commandFor(control)
        if (!lock.withLock { held.remove(command) }) return true
        return send(command, down = false)
    }

    /** A single click: down then up. */
    suspend fun tap(control: RemoteControl): Boolean {
        val pressed = press(control)
        val released = release(control)
        return pressed && released
    }

    /** Lifts every command still held, for when input stops unexpectedly. */
    suspend fun releaseAll() {
        val pending = lock.withLock { held.toList().also { held.clear() } }
        pending.forEach { send(it, down = false) }
    }

    private suspend fun send(command: CompanionCommand, down: Boolean): Boolean = withContext(ioDispatcher) {
        lock.withLock {
            val link = link ?: return@withLock false
            try {
                link.send(command, down)
                true
            } catch (e: IOException) {
                dropLocked()
                false
            }
        }
    }

    /** Runs one desire: connect while target and wanted hold, else close after the grace. */
    private suspend fun run(d: Desire) {
        val target = d.target
        if (target == null || !d.wanted) {
            val current = lock.withLock { link?.name }
            if (current != null) {
                if (current == target) delay(GRACE_MILLIS)
                lock.withLock { closeLocked() }
            }
            _state.value = CompanionState.Off
            return
        }
        lock.withLock { if (link != null && link?.name != target) closeLocked() }
        var attempt = 0
        while (lock.withLock { link } == null) {
            _state.value = CompanionState.Connecting(target)
            val opened = try {
                port.open(target)
            } catch (e: PairingException) {
                _state.value = CompanionState.Rejected(target)
                return
            } catch (e: IOException) {
                _state.value = CompanionState.Unreachable(target)
                delay(BACKOFF_MILLIS[minOf(attempt++, BACKOFF_MILLIS.lastIndex)])
                continue
            }
            lock.withLock { link = opened }
        }
        _state.value = CompanionState.Connected(target)
    }

    /** Closes a dead link and asks the runner to make a new one. Call with [lock] held. */
    private fun dropLocked() {
        closeLocked()
        desire.update { it.copy(epoch = it.epoch + 1) }
    }

    private fun closeLocked() {
        link?.let { l -> try { l.close() } catch (_: IOException) {} }
        link = null
        held.clear()
    }

    /**
     * The real port: looks the Apple TV up (live Bonjour result, else the last stored
     * endpoint), then connects, verifies with the stored pairing and starts the remote session
     * with [CompanionClient]. [model] is shown on the Apple TV next to [displayName].
     */
    class ClientPort(
        private val store: CompanionCredentialsStore,
        private val discovery: AppleTvDiscovery,
        private val bindSocket: (java.net.Socket) -> Unit,
        private val displayName: () -> String,
        private val model: String,
    ) : CompanionLinkPort {
        override fun open(name: String): CompanionLink {
            val credentials = store.get(name) ?: throw PairingException("No pairing stored for $name")
            val live = discovery.appleTvs.value.firstOrNull { it.id == name }
            val endpoint = live?.let { CompanionCredentialsStore.Endpoint(it.host, it.port) }
                ?: store.endpoint(name)
                ?: throw IOException("No address known for $name")
            val client = CompanionClient(endpoint.host, endpoint.port, bindSocket)
            try {
                client.connect()
                client.verify(credentials)
                client.startSession(credentials, name = displayName(), model = model, deviceId = store.deviceId)
            } catch (e: Exception) {
                client.close()
                throw e
            }
            return object : CompanionLink {
                override val name = name
                override fun send(command: CompanionCommand, down: Boolean) = client.hid(command, down)
                override fun close() {
                    try { client.stopSession() } catch (_: IOException) {}
                    client.close()
                }
            }
        }
    }

    companion object {
        /** How long an unwanted link stays open, so a quick tab switch does not reconnect. */
        const val GRACE_MILLIS = 10_000L

        /** Delays between attempts while the Apple TV cannot be reached; the last one repeats. */
        val BACKOFF_MILLIS = longArrayOf(1_000, 2_000, 5_000, 10_000)
    }
}
