package org.ghostsinthelab.app.ringomote.ui.connection

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/**
 * The connection header: one compact status row for the remote session ([state]), so the
 * remote below keeps most of the screen.
 *
 * The row shows a Wi-Fi status icon and a short label (a polite live region, so TalkBack
 * announces every change): not connected (no paired Apple TV, with a Settings button via
 * [onOpenSettings]), connecting, controlling, unreachable, or rejected with the advice to pair
 * again (also with the Settings button). Neither the icon's shape nor its colour is the only
 * signal; the label always says the same in words, so the icon is decorative to TalkBack.
 *
 * There is no Connect or Disconnect: the session connects by itself while the remote is on
 * screen and a paired Apple TV is selected, and closes a while after the remote leaves.
 */
@Composable
fun ConnectionPanel(
    state: CompanionState,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Decorative: the label next to it says the same in words.
            Icon(
                painter = painterResource(R.drawable.ic_wifi),
                contentDescription = null,
                tint = state.indicatorColor(),
                modifier = Modifier.size(24.dp),
            )
            Text(
                text = state.describe(),
                style = MaterialTheme.typography.titleMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
            if (state is CompanionState.Off || state is CompanionState.Rejected) {
                FilledTonalButton(onClick = onOpenSettings) {
                    Text(stringResource(R.string.action_open_settings))
                }
            }
        }
    }
}

/** The status label. */
@Composable
private fun CompanionState.describe(): String = when (this) {
    CompanionState.Off -> stringResource(R.string.remote_wifi_off)
    is CompanionState.Connecting -> stringResource(R.string.remote_wifi_connecting, name)
    is CompanionState.Connected -> stringResource(R.string.remote_wifi_connected, name)
    is CompanionState.Unreachable -> stringResource(R.string.remote_wifi_unreachable, name)
    is CompanionState.Rejected -> stringResource(R.string.remote_wifi_rejected, name)
}

/** Icon colour: primary while controlling, error when the pairing was refused, neutral otherwise. */
@Composable
private fun CompanionState.indicatorColor(): Color = when (this) {
    is CompanionState.Connected -> MaterialTheme.colorScheme.primary
    is CompanionState.Rejected -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

@Preview(showBackground = true)
@Composable
private fun ConnectionPanelPreview() {
    RingomoteTheme {
        ConnectionPanel(state = CompanionState.Connected("客廳"), onOpenSettings = {})
    }
}
