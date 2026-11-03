package org.ghostsinthelab.app.ringomote.ui.connection

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/** How long the phone stays discoverable after "Make discoverable", in seconds. */
private const val DISCOVERABLE_SECONDS = 120

/**
 * Shows the Bluetooth link [state] as text and the actions that move it forward.
 *
 * The status text is a polite live region, so TalkBack announces every change (connecting,
 * connected, Bluetooth turned off) without the user having to look for it. Actions per state:
 * - [TransportState.BluetoothOff]: ask the system to turn Bluetooth on.
 * - [TransportState.Ready]: make the phone discoverable for pairing from the Apple TV, and
 *   connect to one of [pairedDevices].
 * - [TransportState.Connecting] / [TransportState.Connected]: disconnect.
 * - [TransportState.RegistrationFailed]: retry via [onRetry].
 *
 * The two system dialogs are the only place the UI touches `android.bluetooth`; their results
 * are not needed because the transport observes the adapter state itself. Launch failures
 * (no such screen, or a missing permission) are reported with a toast instead of crashing.
 */
@Composable
fun ConnectionPanel(
    state: TransportState,
    pairedDevices: List<HostDevice>,
    onConnect: (HostDevice) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val launchSystemScreen = rememberSystemScreenLauncher()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            text = state.describe(),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        when (state) {
            TransportState.BluetoothOff -> Button(
                onClick = { launchSystemScreen(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
            ) { Text(stringResource(R.string.action_turn_on_bluetooth)) }

            TransportState.Ready -> {
                Button(
                    onClick = {
                        launchSystemScreen(
                            Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).putExtra(
                                BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION,
                                DISCOVERABLE_SECONDS,
                            ),
                        )
                    },
                ) { Text(stringResource(R.string.action_make_discoverable)) }
                Text(
                    text = stringResource(R.string.pairing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
                PairedDeviceList(devices = pairedDevices, onConnect = onConnect)
            }

            is TransportState.Connecting, is TransportState.Connected -> OutlinedButton(
                onClick = onDisconnect,
            ) { Text(stringResource(R.string.action_disconnect)) }

            TransportState.RegistrationFailed -> Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }

            TransportState.Stopped, TransportState.Unsupported,
            TransportState.PermissionRequired, TransportState.Registering -> Unit
        }
    }
}

/** Paired hosts, each with a Connect button whose spoken name includes the device name. */
@Composable
private fun PairedDeviceList(devices: List<HostDevice>, onConnect: (HostDevice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.paired_devices_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        if (devices.isEmpty()) {
            Text(stringResource(R.string.paired_devices_empty), style = MaterialTheme.typography.bodyMedium)
        }
        devices.forEach { device ->
            val name = device.displayName()
            val connectTo = stringResource(R.string.action_connect_to, name)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = name,
                    modifier = Modifier.weight(1f),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )
                // The description goes on the label, not the button, so TalkBack reads the
                // merged button once ("Connect to <name>") instead of description plus label.
                OutlinedButton(onClick = { onConnect(device) }) {
                    Text(
                        text = stringResource(R.string.action_connect),
                        modifier = Modifier.semantics { contentDescription = connectTo },
                    )
                }
            }
        }
    }
}

/**
 * Returns a function that opens a system screen and swallows the failures that some devices
 * produce: [ActivityNotFoundException] (no such screen) and [SecurityException] (Android 12+
 * requires a Bluetooth permission the user revoked meanwhile).
 */
@Composable
private fun rememberSystemScreenLauncher(): (Intent) -> Unit {
    val context = LocalContext.current
    val message = stringResource(R.string.error_no_system_screen)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return { intent ->
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        } catch (_: SecurityException) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }
}

/** The device name, or its address when Android does not know the name. */
private fun HostDevice.displayName(): String = name?.takeIf { it.isNotBlank() } ?: address

@Composable
private fun TransportState.describe(): String = when (this) {
    TransportState.Stopped -> stringResource(R.string.state_stopped)
    TransportState.Unsupported -> stringResource(R.string.state_unsupported)
    TransportState.PermissionRequired -> stringResource(R.string.state_permission_required)
    TransportState.BluetoothOff -> stringResource(R.string.state_bluetooth_off)
    TransportState.Registering -> stringResource(R.string.state_registering)
    TransportState.RegistrationFailed -> stringResource(R.string.state_registration_failed)
    TransportState.Ready -> stringResource(R.string.state_ready)
    is TransportState.Connecting -> stringResource(R.string.state_connecting, device.displayName())
    is TransportState.Connected -> stringResource(R.string.state_connected, device.displayName())
}

@Preview(showBackground = true)
@Composable
private fun ConnectionPanelReadyPreview() {
    RingomoteTheme {
        ConnectionPanel(
            state = TransportState.Ready,
            pairedDevices = listOf(
                HostDevice("00:11:22:33:44:55", "Living Room"),
                HostDevice("66:77:88:99:AA:BB", null),
            ),
            onConnect = {},
            onDisconnect = {},
            onRetry = {},
        )
    }
}
