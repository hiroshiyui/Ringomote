package org.ghostsinthelab.app.ringomote.ui.connection

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
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
 * The connection header: one compact status row, with the longer explanations behind a
 * Details toggle that is collapsed by default, so the remote below keeps most of the screen.
 *
 * The row always shows a status dot and a short status label (a polite live region, so TalkBack
 * announces every change), plus the one action the state needs right away:
 * - [TransportState.BluetoothOff]: turn Bluetooth on.
 * - [TransportState.Connecting] / [TransportState.Connected]: disconnect.
 * - [TransportState.RegistrationFailed]: retry via [onRetry].
 *
 * Details, for the states that have any ([hasDetails]), hold the full explanation and, when
 * [TransportState.Ready], the pairing steps: make the phone discoverable for pairing from the
 * Apple TV, or connect to one of [pairedDevices]. The dot's colour is never the only signal; the
 * label always says the same in words.
 *
 * Details start expanded when [expandDetailsByDefault] is true (first use, before any Apple TV
 * was connected), so the pairing steps are visible without a tap; otherwise they start
 * collapsed. Once the user toggles them, their choice wins over the default.
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
    expandDetailsByDefault: Boolean = false,
) {
    val launchSystemScreen = rememberSystemScreenLauncher()
    // null until the user toggles; then their choice overrides the default.
    var userExpanded by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val expanded = userExpanded ?: expandDetailsByDefault
    val showDetails = expanded && state.hasDetails()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(state.indicatorColor()),
            )
            Text(
                text = state.describeShort(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            when (state) {
                TransportState.BluetoothOff -> FilledTonalButton(
                    onClick = { launchSystemScreen(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                ) { Text(stringResource(R.string.action_turn_on_bluetooth)) }

                is TransportState.Connecting, is TransportState.Connected -> OutlinedButton(
                    onClick = onDisconnect,
                ) { Text(stringResource(R.string.action_disconnect)) }

                TransportState.RegistrationFailed -> FilledTonalButton(onClick = onRetry) {
                    Text(stringResource(R.string.action_retry))
                }

                else -> Unit
            }
            if (state.hasDetails()) {
                val expandedLabel = stringResource(R.string.state_expanded)
                val collapsedLabel = stringResource(R.string.state_collapsed)
                TextButton(
                    onClick = { userExpanded = !expanded },
                    modifier = Modifier.semantics {
                        stateDescription = if (showDetails) expandedLabel else collapsedLabel
                    },
                ) {
                    Text(
                        stringResource(if (showDetails) R.string.action_hide_details else R.string.action_show_details),
                    )
                }
            }
        }

        if (showDetails) {
            Text(text = state.describe(), style = MaterialTheme.typography.bodyMedium)
            if (state == TransportState.Ready) {
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
        }
    }
}

/** Whether [ConnectionPanel] has more to say about this state than the status row. */
private fun TransportState.hasDetails(): Boolean = when (this) {
    TransportState.Ready, TransportState.RegistrationFailed,
    TransportState.Unsupported, TransportState.PermissionRequired -> true
    else -> false
}

/** Status dot colour: primary when connected, error when blocked, neutral otherwise. */
@Composable
private fun TransportState.indicatorColor(): Color = when (this) {
    is TransportState.Connected -> MaterialTheme.colorScheme.primary
    TransportState.BluetoothOff, TransportState.RegistrationFailed,
    TransportState.Unsupported, TransportState.PermissionRequired -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.outline
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

/** The short status label shown in the collapsed row. */
@Composable
private fun TransportState.describeShort(): String = when (this) {
    TransportState.Stopped -> stringResource(R.string.state_short_stopped)
    TransportState.Unsupported -> stringResource(R.string.state_short_unsupported)
    TransportState.PermissionRequired -> stringResource(R.string.state_short_permission_required)
    TransportState.BluetoothOff -> stringResource(R.string.state_short_bluetooth_off)
    TransportState.Registering -> stringResource(R.string.state_short_registering)
    TransportState.RegistrationFailed -> stringResource(R.string.state_short_registration_failed)
    TransportState.Ready -> stringResource(R.string.state_short_ready)
    is TransportState.Connecting -> stringResource(R.string.state_connecting, device.displayName())
    is TransportState.Connected -> stringResource(R.string.state_connected, device.displayName())
}

/** The full explanation shown under Details. */
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
