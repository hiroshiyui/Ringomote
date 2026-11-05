package org.ghostsinthelab.app.ringomote.ui.connection

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.annotation.DrawableRes
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.bluetooth.HostDevice
import org.ghostsinthelab.app.ringomote.bluetooth.TransportState
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/**
 * The connection header: one compact status row, with the longer explanations behind a
 * Details toggle that is collapsed by default, so the remote below keeps most of the screen.
 *
 * The row always shows a Bluetooth status icon and a short status label (a polite live region, so TalkBack
 * announces every change), plus the one action the state needs right away:
 * - [TransportState.BluetoothOff]: turn Bluetooth on.
 * - [TransportState.RegistrationFailed]: retry via [onRetry].
 *
 * There is no Disconnect: a BLE peripheral cannot end the link, only detach from it, and the
 * Apple TV then keeps showing the phone as connected while input is dead (seen on hardware).
 * The phone's own Bluetooth settings offer no disconnect for a GATT-only link either (only
 * Forget), so the Connected details say to disconnect on the Apple TV.
 *
 * Details, for the states that have any ([hasDetails]), hold the full explanation and, when
 * [TransportState.Ready], the pairing steps: the phone is already advertising, so pick it on
 * the Apple TV and confirm the two pairing prompts. There is no Connect button: the Apple TV
 * owns the link and reconnects by itself, and a phone-side connect opens a classic link the
 * TV closes at once (see the link rules in CLAUDE.md). Neither the icon's shape nor its colour is the
 * only signal; the label always says the same in words, so the icon is decorative to TalkBack.
 *
 * Below the Bluetooth row, while the Wi-Fi remote session ([companionState]) is doing
 * anything, a second row says so: connecting, controlling over Wi-Fi (with the note that Mute
 * is unavailable there, shown only while Bluetooth cannot carry it either), unreachable, or
 * rejected with the advice to pair again. Wi-Fi is the first link, so the row stays while the
 * Apple TV is also subscribed over Bluetooth.
 *
 * Details start expanded when [expandDetailsByDefault] is true (first use, before any Apple TV
 * was connected), so the pairing steps are visible without a tap; otherwise they start
 * collapsed. Once the user toggles them, their choice wins over the default.
 *
 * The "turn on Bluetooth" system dialog is the only place the UI touches `android.bluetooth`;
 * its result is not needed because the transport observes the adapter state itself. Launch failures
 * (no such screen, or a missing permission) are reported with a toast instead of crashing.
 */
@Composable
fun ConnectionPanel(
    state: TransportState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    expandDetailsByDefault: Boolean = false,
    companionState: CompanionState = CompanionState.Off,
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
            // Decorative: the label next to it says the same in words.
            Icon(
                painter = painterResource(state.indicatorIcon()),
                contentDescription = null,
                tint = state.indicatorColor(),
                modifier = Modifier.size(24.dp),
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

        if (companionState != CompanionState.Off) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_wifi),
                    contentDescription = null,
                    tint = companionState.indicatorColor(),
                    modifier = Modifier.size(24.dp),
                )
                Text(
                    text = companionState.describe(),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier
                        .weight(1f)
                        .semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (companionState is CompanionState.Connected && state !is TransportState.Connected) {
                Text(
                    text = stringResource(R.string.remote_wifi_mute_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (showDetails) {
            Text(text = state.describe(), style = MaterialTheme.typography.bodyMedium)
            if (state == TransportState.Ready) {
                Text(
                    text = stringResource(R.string.pairing_hint),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

/** Whether [ConnectionPanel] has more to say about this state than the status row. */
private fun TransportState.hasDetails(): Boolean = when (this) {
    TransportState.Ready, TransportState.RegistrationFailed,
    TransportState.Unsupported, TransportState.PermissionRequired, is TransportState.Connected,
    is TransportState.Resyncing -> true
    else -> false
}

/**
 * Status icon: Bluetooth-connected when connected, Bluetooth-searching while a link is being set
 * up, Bluetooth-disabled when blocked, and the plain Bluetooth symbol when ready to pair.
 */
@DrawableRes
private fun TransportState.indicatorIcon(): Int = when (this) {
    is TransportState.Connected -> R.drawable.ic_bluetooth_connected
    is TransportState.Resyncing -> R.drawable.ic_bluetooth_connected
    is TransportState.Connecting, TransportState.Registering, TransportState.Stopped ->
        R.drawable.ic_bluetooth_searching
    TransportState.Ready -> R.drawable.ic_bluetooth
    TransportState.BluetoothOff, TransportState.RegistrationFailed,
    TransportState.Unsupported, TransportState.PermissionRequired -> R.drawable.ic_bluetooth_disabled
}

/** Status icon colour: primary when connected, error when blocked, neutral otherwise. */
@Composable
private fun TransportState.indicatorColor(): Color = when (this) {
    is TransportState.Connected -> MaterialTheme.colorScheme.primary
    is TransportState.Resyncing -> MaterialTheme.colorScheme.error
    TransportState.BluetoothOff, TransportState.RegistrationFailed,
    TransportState.Unsupported, TransportState.PermissionRequired -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * Returns a function that opens a system screen and swallows the failures that some devices
 * produce: [ActivityNotFoundException] (no such screen) and [SecurityException] (Android 12+
 * requires a Bluetooth permission the user revoked meanwhile).
 */
@Composable
internal fun rememberSystemScreenLauncher(): (Intent) -> Unit {
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
    is TransportState.Resyncing -> stringResource(R.string.state_short_resyncing, device.displayName())
}

/** The Wi-Fi row's text; [CompanionState.Off] has no row. */
@Composable
private fun CompanionState.describe(): String = when (this) {
    CompanionState.Off -> ""
    is CompanionState.Connecting -> stringResource(R.string.remote_wifi_connecting, name)
    is CompanionState.Connected -> stringResource(R.string.remote_wifi_connected, name)
    is CompanionState.Unreachable -> stringResource(R.string.remote_wifi_unreachable, name)
    is CompanionState.Rejected -> stringResource(R.string.remote_wifi_rejected, name)
}

/** Wi-Fi icon colour: primary while controlling, error when the pairing was refused. */
@Composable
private fun CompanionState.indicatorColor(): Color = when (this) {
    is CompanionState.Connected -> MaterialTheme.colorScheme.primary
    is CompanionState.Rejected -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
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
    is TransportState.Connected -> stringResource(R.string.state_connected_details, device.displayName())
    is TransportState.Resyncing -> stringResource(R.string.state_resyncing, device.displayName())
}

@Preview(showBackground = true)
@Composable
private fun ConnectionPanelReadyPreview() {
    RingomoteTheme {
        ConnectionPanel(
            state = TransportState.Ready,
            onRetry = {},
        )
    }
}
