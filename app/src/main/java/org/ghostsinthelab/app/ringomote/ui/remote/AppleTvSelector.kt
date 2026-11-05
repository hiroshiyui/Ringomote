package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.network.KnownAppleTv
import org.ghostsinthelab.app.ringomote.ui.settings.AppleTvLinkState

/**
 * Which Apple TV the remote is aimed at, above the connection panel.
 *
 * With several known Apple TVs ([tvs]), a row of chips picks one ([selected], [onSelect]);
 * with one, the chips are not shown. Below, when the chosen TV is paired and the session is
 * not [connected], a Wake button ([onWake]) turns it on; its [linkState] gives the progress
 * and the outcome.
 *
 * When the selected TV is not yet paired, a line offers Pair ([onPairOverNetwork], which lands
 * on Settings, where the PIN flow lives); it vanishes once the pairing is stored.
 */
@Composable
fun AppleTvSelector(
    tvs: List<KnownAppleTv>,
    selected: KnownAppleTv?,
    linkState: AppleTvLinkState?,
    connected: Boolean,
    onSelect: (KnownAppleTv) -> Unit,
    onWake: (KnownAppleTv) -> Unit,
    modifier: Modifier = Modifier,
    onPairOverNetwork: () -> Unit = {},
) {
    if (tvs.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (selected != null && !selected.paired) {
            HintRow(
                text = stringResource(R.string.remote_pair_network_hint, selected.name),
                button = stringResource(R.string.remote_pair_network_button),
                onClick = onPairOverNetwork,
            )
        }
        if (tvs.size > 1) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tvs.forEach { tv ->
                    FilterChip(
                        selected = tv == selected,
                        onClick = { onSelect(tv) },
                        label = { Text(tv.name) },
                        modifier = Modifier.semantics { this.selected = tv == selected },
                    )
                }
            }
        }
        if (selected != null && selected.paired && !connected) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = when (linkState) {
                        is AppleTvLinkState.Busy -> stringResource(R.string.remote_wake_sending, selected.name)
                        is AppleTvLinkState.Paired -> when (linkState.note) {
                            AppleTvLinkState.Outcome.WakeSent -> stringResource(R.string.remote_wake_sent, selected.name)
                            AppleTvLinkState.Outcome.WakeFailed, AppleTvLinkState.Outcome.Unreachable ->
                                stringResource(R.string.remote_wake_failed, selected.name)
                            AppleTvLinkState.Outcome.Rejected -> stringResource(R.string.remote_wake_rejected, selected.name)
                            null -> stringResource(R.string.remote_wake_hint, selected.name)
                        }
                        else -> stringResource(R.string.remote_wake_hint, selected.name)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (linkState is AppleTvLinkState.Busy) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    FilledTonalButton(onClick = { onWake(selected) }) {
                        Text(stringResource(R.string.remote_wake_button))
                    }
                }
            }
        }
    }
}

/** One line of advice with a button at its end. */
@Composable
private fun HintRow(text: String, button: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        FilledTonalButton(onClick = onClick) { Text(button) }
    }
}
