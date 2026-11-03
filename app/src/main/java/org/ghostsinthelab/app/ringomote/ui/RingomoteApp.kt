package org.ghostsinthelab.app.ringomote.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import org.ghostsinthelab.app.ringomote.bluetooth.BluetoothPermissions
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.ui.connection.ConnectionPanel
import org.ghostsinthelab.app.ringomote.ui.remote.RemoteScreen
import org.ghostsinthelab.app.ringomote.ui.remote.RemoteViewModel

/**
 * Root of the UI: wires [RemoteViewModel] to the screens.
 *
 * Until every permission in [BluetoothPermissions.runtime] is granted it shows
 * [PermissionScreen]; afterwards it starts the transport and shows the [ConnectionPanel] above
 * the [RemoteScreen]. Permissions are re-checked on every resume, since the user may grant or
 * revoke them in the system settings while the app is in the background.
 *
 * Lifecycle rules: on stop every held control is released (a finger that was down when the app
 * went to the background never sends its "up", and the Apple TV would see a stuck key); on
 * resume the paired-device list is refreshed, since pairing happens in the system UI.
 */
@Composable
fun RingomoteApp(
    modifier: Modifier = Modifier,
    vm: RemoteViewModel = viewModel(factory = RemoteViewModel.Factory),
) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(BluetoothPermissions.allGranted(context)) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = BluetoothPermissions.allGranted(context)
        if (granted) vm.refreshPairedDevices()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { vm.releaseAll() }

    // start() is idempotent, so it is safe on entry, after a grant and after recreation.
    LaunchedEffect(granted) { if (granted) vm.start() }

    Scaffold(modifier = modifier.fillMaxSize()) { innerPadding ->
        if (granted) {
            RemoteHome(vm = vm, modifier = Modifier.padding(innerPadding))
        } else {
            PermissionScreen(
                onGranted = { granted = true },
                modifier = Modifier.padding(innerPadding),
            )
        }
    }
}

/** The connection panel and the remote, shown once permissions are granted. */
@Composable
private fun RemoteHome(vm: RemoteViewModel, modifier: Modifier = Modifier) {
    val state by vm.transportState.collectAsStateWithLifecycle()
    val pairedDevices by vm.pairedDevices.collectAsStateWithLifecycle()

    // The bonded list is empty while Bluetooth is off; reload it once the link is usable.
    val ready = state is TransportState.Ready
    LaunchedEffect(ready) { if (ready) vm.refreshPairedDevices() }

    RemoteScreen(
        enabled = state is TransportState.Connected,
        onPress = vm::press,
        onRelease = vm::release,
        onTap = vm::tap,
        onHold = vm::hold,
        modifier = modifier.padding(16.dp),
        header = {
            ConnectionPanel(
                state = state,
                pairedDevices = pairedDevices,
                onConnect = vm::connect,
                onDisconnect = vm::disconnect,
                onRetry = vm::start,
            )
        },
    )
}
