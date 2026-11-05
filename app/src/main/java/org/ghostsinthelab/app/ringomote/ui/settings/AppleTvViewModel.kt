package org.ghostsinthelab.app.ringomote.ui.settings

import android.os.Build
import android.util.Log
import java.net.Socket
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.network.AppleTv
import org.ghostsinthelab.app.ringomote.network.companion.CompanionClient
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentials
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.PairSetup
import org.ghostsinthelab.app.ringomote.network.companion.PairingException

/** What the Settings list shows for one Apple TV, beyond the facts Bonjour gave. */
sealed interface AppleTvLinkState {
    /** No pairing stored; offer Pair. */
    data object Unpaired : AppleTvLinkState

    /** Pair-setup started, the Apple TV is showing a PIN; offer a PIN field. */
    data object AwaitingPin : AppleTvLinkState

    /** A connection is in progress ([what] names it); buttons disabled. */
    data class Busy(val what: Work) : AppleTvLinkState

    /** Pairing stored; offer Wake and Forget. [note] is the outcome of the last action, if any. */
    data class Paired(val note: Outcome? = null) : AppleTvLinkState

    enum class Work { Pairing, Waking }
    enum class Outcome { WakeSent, WakeFailed, Unreachable, Rejected }

    /** Pairing failed; [reason] says why, offer Pair again. */
    data class Failed(val reason: Outcome) : AppleTvLinkState
}

/**
 * Pairs with, wakes and forgets Apple TVs over Companion Link, on behalf of the Settings tab.
 *
 * Each action opens its own connection on [ioDispatcher] and closes it when done, except
 * pairing, which must keep the connection open between showing the PIN and confirming it:
 * [startPairing] holds it in [pending] until [submitPin] or [cancelPairing]. States are kept
 * per Apple TV id so two TVs in the list never share a button state.
 */
class AppleTvViewModel(
    private val store: CompanionCredentialsStore,
    private val bindSocket: (Socket) -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val _states = MutableStateFlow<Map<String, AppleTvLinkState>>(emptyMap())
    val states: StateFlow<Map<String, AppleTvLinkState>> = _states.asStateFlow()

    private class Pending(val client: CompanionClient, val setup: PairSetup)
    private var pending: Pending? = null

    fun stateOf(tv: AppleTv): AppleTvLinkState =
        _states.value[tv.id] ?: if (store.get(tv.id) != null) AppleTvLinkState.Paired() else AppleTvLinkState.Unpaired

    fun startPairing(tv: AppleTv) {
        set(tv, AppleTvLinkState.Busy(AppleTvLinkState.Work.Pairing))
        viewModelScope.launch {
            try {
                withContext(ioDispatcher) {
                    cancelPendingBlocking()
                    val client = CompanionClient(tv.host, tv.port, bindSocket)
                    try {
                        client.connect()
                        val setup = client.startPairing(DISPLAY_NAME)
                        pending = Pending(client, setup)
                    } catch (e: Exception) {
                        client.close()
                        throw e
                    }
                }
                set(tv, AppleTvLinkState.AwaitingPin)
            } catch (e: PairingException) {
                Log.w(TAG, "Pair-setup start rejected by ${tv.host}", e)
                set(tv, AppleTvLinkState.Failed(AppleTvLinkState.Outcome.Rejected))
            } catch (e: IOException) {
                Log.w(TAG, "Pair-setup start failed for ${tv.host}:${tv.port}", e)
                set(tv, AppleTvLinkState.Failed(AppleTvLinkState.Outcome.Unreachable))
            }
        }
    }

    fun submitPin(tv: AppleTv, pin: String) {
        val p = pending ?: return
        set(tv, AppleTvLinkState.Busy(AppleTvLinkState.Work.Pairing))
        viewModelScope.launch {
            try {
                val credentials = withContext(ioDispatcher) {
                    try {
                        p.client.finishPairing(p.setup, pin.trim())
                    } finally {
                        p.client.close()
                        pending = null
                    }
                }
                store.put(tv.id, credentials)
                set(tv, AppleTvLinkState.Paired())
            } catch (e: PairingException) {
                Log.w(TAG, "Pair-setup rejected by ${tv.host}", e)
                set(tv, AppleTvLinkState.Failed(AppleTvLinkState.Outcome.Rejected))
            } catch (e: IOException) {
                Log.w(TAG, "Pair-setup failed for ${tv.host}", e)
                set(tv, AppleTvLinkState.Failed(AppleTvLinkState.Outcome.Unreachable))
            }
        }
    }

    fun cancelPairing(tv: AppleTv) {
        viewModelScope.launch { withContext(ioDispatcher) { cancelPendingBlocking() } }
        set(tv, if (store.get(tv.id) != null) AppleTvLinkState.Paired() else AppleTvLinkState.Unpaired)
    }

    fun forget(tv: AppleTv) {
        store.remove(tv.id)
        set(tv, AppleTvLinkState.Unpaired)
    }

    fun wake(tv: AppleTv) {
        val credentials = store.get(tv.id) ?: return
        set(tv, AppleTvLinkState.Busy(AppleTvLinkState.Work.Waking))
        viewModelScope.launch {
            val outcome = try {
                withContext(ioDispatcher) { wakeBlocking(tv, credentials) }
                AppleTvLinkState.Outcome.WakeSent
            } catch (e: PairingException) {
                Log.w(TAG, "Wake rejected by ${tv.host}", e)
                AppleTvLinkState.Outcome.Rejected
            } catch (e: IOException) {
                Log.w(TAG, "Wake failed for ${tv.host}", e)
                AppleTvLinkState.Outcome.WakeFailed
            }
            set(tv, AppleTvLinkState.Paired(outcome))
        }
    }

    private fun wakeBlocking(tv: AppleTv, credentials: CompanionCredentials) {
        CompanionClient(tv.host, tv.port, bindSocket).use { client ->
            client.connect()
            client.verify(credentials)
            client.startSession(credentials, name = DISPLAY_NAME, model = Build.MODEL, deviceId = store.deviceId)
            client.hidPress(CompanionClient.Hid.WAKE)
        }
    }

    private fun cancelPendingBlocking() {
        pending?.client?.close()
        pending = null
    }

    private fun set(tv: AppleTv, state: AppleTvLinkState) = _states.update { it + (tv.id to state) }

    override fun onCleared() {
        pending?.client?.close()
        pending = null
    }

    companion object {
        private const val TAG = "Companion"

        /** How this phone appears under Settings > Remotes and Devices on the Apple TV. */
        const val DISPLAY_NAME = "Ringomote"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RingomoteApplication).container
                AppleTvViewModel(container.companionCredentials, container.localNetwork::bind)
            }
        }
    }
}
