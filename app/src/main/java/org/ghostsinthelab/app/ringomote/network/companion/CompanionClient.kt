package org.ghostsinthelab.app.ringomote.network.companion

import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.UUID
import kotlin.random.Random

/** Thrown when the Apple TV answers a request with an error or an unexpected reply. */
class CompanionException(message: String) : IOException(message)

/**
 * A Companion Link connection to one Apple TV: TCP, 4-byte frame headers, OPACK payloads,
 * HAP pairing, and ChaCha20-Poly1305 once verified.
 *
 * Everything here blocks, so call it from [kotlinx.coroutines.Dispatchers.IO]. One request
 * is in flight at a time; events the Apple TV pushes in between (`_t` = 1) are skipped
 * while waiting for a response, which is enough for pairing and the few commands used.
 *
 * Frame layout: `type (1) | length (3, big endian) | payload`. After [enableEncryption]
 * every non-empty payload is sealed with the session keys, the 4-byte header as additional
 * data, and a per-direction little-endian counter as nonce; the header's length counts the
 * 16-byte tag.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    /** Binds the socket to a network before connecting; see `LocalNetwork`. */
    private val bindSocket: (Socket) -> Unit = {},
) : Closeable {
    private val socket = Socket()
    private lateinit var input: DataInputStream
    private var keys: SessionKeys? = null
    private var outCounter = 0L
    private var inCounter = 0L
    private var nextXid = Random.nextInt(0, 0x10000).toLong()

    /** Session id from `_sessionStart`, 0 until then and after [stopSession]. */
    var sessionId: Long = 0
        private set

    /** Whether the socket is still open on this side; a peer close shows only on the next read. */
    val isOpen: Boolean get() = socket.isConnected && !socket.isClosed

    object FrameType {
        const val PS_START = 0x03
        const val PS_NEXT = 0x04
        const val PV_START = 0x05
        const val PV_NEXT = 0x06
        const val E_OPACK = 0x08
    }

    fun connect(timeoutMs: Int = 5000) {
        bindSocket(socket)
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        socket.soTimeout = timeoutMs
        socket.tcpNoDelay = true
        input = DataInputStream(socket.getInputStream())
    }

    override fun close() {
        try { socket.close() } catch (_: IOException) {}
    }

    // ---- pairing ----

    /**
     * Starts pair-setup; when this returns the Apple TV is showing a PIN. Keep the returned
     * [PairSetup] and the connection open, then call [finishPairing] with the PIN.
     */
    fun startPairing(displayName: String): PairSetup {
        val setup = PairSetup(displayName)
        val m2 = exchangeAuth(FrameType.PS_START, setup.m1(), extra = mapOf("_pwTy" to 1L))
        pendingM2 = m2
        return setup
    }

    private var pendingM2: ByteArray? = null

    fun finishPairing(setup: PairSetup, pin: String): CompanionCredentials {
        val m2 = pendingM2 ?: throw IllegalStateException("startPairing() not called")
        val m4 = exchangeAuth(FrameType.PS_NEXT, setup.m3(m2, pin), extra = mapOf("_pwTy" to 1L))
        val m6 = exchangeAuth(FrameType.PS_NEXT, setup.m5(m4), extra = mapOf("_pwTy" to 1L))
        return setup.finish(m6)
    }

    /** Pair-verify with stored [credentials] and switch the connection to encrypted frames. */
    fun verify(credentials: CompanionCredentials) {
        val verify = PairVerify(credentials)
        val m2 = exchangeAuth(FrameType.PV_START, verify.m1(), extra = mapOf("_auTy" to 4L))
        val m4 = exchangeAuth(FrameType.PV_NEXT, verify.m3(m2))
        verify.finish(m4)
        enableEncryption(verify.sessionKeys)
    }

    private fun enableEncryption(keys: SessionKeys) {
        this.keys = keys
        outCounter = 0
        inCounter = 0
    }

    // ---- session ----

    /**
     * What the reference client sends after verifying: identify ourselves, open a remote
     * session and the TV Remote Client session. [name] is shown on the Apple TV; [deviceId]
     * is a stable per-install identifier (any MAC-like string will do).
     */
    fun startSession(credentials: CompanionCredentials, name: String, model: String, deviceId: String) {
        request(
            "_systemInfo",
            mapOf(
                "_bf" to 0L,
                "_cf" to 512L,
                "_clFl" to 128L,
                "_i" to deviceId.replace(":", "").lowercase(),
                "_idsID" to credentials.controllerId,
                "_pubID" to deviceId,
                "_sf" to 256L,
                "_sv" to "170.18",
                "model" to model,
                "name" to name,
            ),
        )
        val localSid = Random.nextLong(0, 1L shl 32)
        val reply = request("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to localSid))
        val remoteSid = (reply["_c"] as? Map<*, *>)?.get("_sid") as? Long
            ?: throw CompanionException("_sessionStart reply without _sid")
        sessionId = (remoteSid shl 32) or localSid
        try {
            request("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2"))
        } catch (_: CompanionException) {
            // Older tvOS has no handler for it; harmless.
        }
    }

    /** Presses and releases a HID button; [CompanionCommand.Wake] turns a sleeping Apple TV on. */
    fun hidPress(command: CompanionCommand) {
        hid(command, down = true)
        hid(command, down = false)
    }

    /** Sends one `_hidC` message with the given button state; `Wake` needs only the release. */
    fun hid(command: CompanionCommand, down: Boolean) {
        request("_hidC", mapOf("_hBtS" to (if (down) 1L else 2L), "_hidC" to command.code.toLong()))
    }

    /**
     * Ends the remote session opened by [startSession], as the reference client does before
     * closing. Best effort: the Apple TV also cleans up when the socket closes.
     */
    fun stopSession() {
        if (sessionId == 0L) return
        request("_sessionStop", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to sessionId))
        sessionId = 0
    }

    /** Sends a request (`_t` = 2) and returns the matching response dictionary. */
    fun request(id: String, content: Map<String, Any?>): Map<*, *> {
        val xid = nextXid++
        send(FrameType.E_OPACK, Opack.encode(mapOf("_i" to id, "_t" to 2L, "_x" to xid, "_c" to content)))
        while (true) {
            val (type, payload) = receive()
            if (type != FrameType.E_OPACK) continue
            val message = Opack.decode(payload) as? Map<*, *> ?: continue
            if (message["_t"] != 3L || message["_x"] != xid) continue
            (message["_em"] as? String)?.let { throw CompanionException("$id failed: $it") }
            return message
        }
    }

    // ---- framing ----

    private fun exchangeAuth(frameType: Int, pairingData: ByteArray, extra: Map<String, Any?> = emptyMap()): ByteArray {
        send(frameType, Opack.encode(mapOf("_pd" to pairingData) + extra))
        val expected = when (frameType) {
            FrameType.PS_START -> FrameType.PS_NEXT
            FrameType.PV_START -> FrameType.PV_NEXT
            else -> frameType
        }
        while (true) {
            val (type, payload) = receive()
            if (type != expected) continue
            val message = Opack.decode(payload) as? Map<*, *> ?: throw CompanionException("Auth reply is not a dictionary")
            return message["_pd"] as? ByteArray ?: throw CompanionException("Auth reply without pairing data")
        }
    }

    private fun send(frameType: Int, payload: ByteArray) {
        val keys = keys
        val body = if (keys != null && payload.isNotEmpty()) {
            val header = header(frameType, payload.size + 16)
            header + HapCrypto.encrypt(keys.output, HapCrypto.counterNonce(outCounter++), payload, header)
        } else {
            header(frameType, payload.size) + payload
        }
        socket.getOutputStream().apply { write(body); flush() }
    }

    private fun receive(): Pair<Int, ByteArray> {
        val header = ByteArray(4)
        try { input.readFully(header) } catch (e: EOFException) { throw CompanionException("The Apple TV closed the connection") }
        val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = ByteArray(length).also { if (length > 0) input.readFully(it) }
        val keys = keys
        val plain = if (keys != null && payload.isNotEmpty()) {
            HapCrypto.decrypt(keys.input, HapCrypto.counterNonce(inCounter++), payload, header)
        } else payload
        return (header[0].toInt() and 0xFF) to plain
    }

    private fun header(frameType: Int, length: Int) = byteArrayOf(
        frameType.toByte(), (length ushr 16).toByte(), (length ushr 8).toByte(), length.toByte(),
    )

    companion object {
        /** A stable, MAC-shaped device id derived from a random UUID, kept in settings. */
        fun newDeviceId(): String {
            val b = UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            return b.chunked(2).joinToString(":").uppercase()
        }
    }
}
