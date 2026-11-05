package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.ghostsinthelab.app.ringomote.RingomoteApplication
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionCredentialsStore
import org.ghostsinthelab.app.ringomote.network.companion.CompanionSession
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.remote.RemoteControl

/**
 * State and actions for the remote screen.
 *
 * Every press goes to the [CompanionSession] on [serialDispatcher], shared app-wide (see
 * [org.ghostsinthelab.app.ringomote.AppContainer.serialDispatcher]), so presses reach the
 * Apple TV in the order the user produced them and a release queued by a closing screen runs
 * before anything the next screen queues.
 *
 * The session is wanted while the remote is on screen ([setRemoteShown]) in the foreground
 * ([setForeground]); its target is the paired Apple TV the Remote tab is aimed at
 * ([NetworkSettings.selectedAppleTv], or the only pairing). A new pairing makes the session
 * try again after a refusal; a refusal ([CompanionState.Rejected]) means the Apple TV forgot
 * the pairing, so the dead keys are dropped from the store, which turns the reminders back
 * into Pair. Whenever the link leaves [CompanionState.Connected], held
 * controls are released, so a finger that was down when the link dropped never leaves a key
 * stuck on the Apple TV.
 *
 * [onCleared] only releases held keys, in [applicationScope] because [viewModelScope] is
 * already cancelled by then, and lets the session go.
 */
class RemoteViewModel(
    private val companion: CompanionSession,
    private val networkSettings: NetworkSettings,
    private val companionCredentials: CompanionCredentialsStore,
    private val serialDispatcher: CoroutineDispatcher,
    private val applicationScope: CoroutineScope,
) : ViewModel() {

    /** The remote session state. */
    val companionState: StateFlow<CompanionState> = companion.state

    private val remoteShown = MutableStateFlow(false)
    private val foreground = MutableStateFlow(true)

    init {
        viewModelScope.launch(serialDispatcher) {
            companion.state.map { it is CompanionState.Connected }.distinctUntilChanged()
                .collect { connected -> if (!connected) companion.releaseAll() }
        }
        viewModelScope.launch {
            combine(networkSettings.selectedAppleTv, companionCredentials.credentials) { selected, paired ->
                selected?.takeIf { it in paired } ?: paired.keys.singleOrNull()
            }.distinctUntilChanged().collect { companion.target(it) }
        }
        viewModelScope.launch {
            companionCredentials.credentials.map { it.keys }.distinctUntilChanged().collect { companion.retry() }
        }
        viewModelScope.launch {
            companion.state.collect { if (it is CompanionState.Rejected) companionCredentials.remove(it.name) }
        }
        viewModelScope.launch {
            combine(remoteShown, foreground) { shown, fg -> shown && fg }
                .distinctUntilChanged().collect { companion.setWanted(it) }
        }
    }

    /** Whether the Remote tab is composed; the session is held only while it is. */
    fun setRemoteShown(shown: Boolean) { remoteShown.value = shown }

    /** Whether the app is in the foreground; the session closes a while after it leaves. */
    fun setForeground(inForeground: Boolean) { foreground.value = inForeground }

    /** Finger down on [control]: held until [release]. */
    fun press(control: RemoteControl) = serial { companion.press(control) }

    /** Finger up on [control]. */
    fun release(control: RemoteControl) = serial { companion.release(control) }

    /** A single click, e.g. a swipe step or an accessibility click. */
    fun tap(control: RemoteControl) = serial { companion.tap(control) }

    /** A long press without a finger to time it (accessibility long-click). */
    fun hold(control: RemoteControl) = serial {
        companion.press(control)
        delay(LONG_PRESS_MILLIS)
        companion.release(control)
    }

    /** Releases every held control; call when the screen stops receiving input. */
    fun releaseAll() = serial { companion.releaseAll() }

    private fun serial(block: suspend () -> Unit) {
        viewModelScope.launch(serialDispatcher) { block() }
    }

    override fun onCleared() {
        companion.setWanted(false)
        applicationScope.launch(serialDispatcher) { companion.releaseAll() }
    }

    companion object {
        /** Long enough for tvOS to treat Home as a hold (Control Center); verified at 1.5 s. */
        const val LONG_PRESS_MILLIS = 1_500L

        /** Builds the ViewModel from the app's [org.ghostsinthelab.app.ringomote.AppContainer]. */
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as RingomoteApplication).container
                RemoteViewModel(
                    container.companionSession,
                    container.networkSettings,
                    container.companionCredentials,
                    container.serialDispatcher,
                    container.applicationScope,
                )
            }
        }
    }
}
