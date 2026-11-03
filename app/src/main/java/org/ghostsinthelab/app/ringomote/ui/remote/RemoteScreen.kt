package org.ghostsinthelab.app.ringomote.ui.remote

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.remote.RemoteControl
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme

/**
 * The remote itself, laid out like the 1st-generation Siri Remote: the [TouchSurface] on top,
 * then Menu and TV (Home), Play/Pause, and Volume up/down.
 *
 * Stateless: [enabled] (true only while connected) and the input callbacks come from the
 * caller, normally [RemoteViewModel]. When disabled, every control is dimmed and announced as
 * disabled, and a hint above the remote says why.
 */
@Composable
fun RemoteScreen(
    enabled: Boolean,
    onPress: (RemoteControl) -> Unit,
    onRelease: (RemoteControl) -> Unit,
    onTap: (RemoteControl) -> Unit,
    onHold: (RemoteControl) -> Unit,
    modifier: Modifier = Modifier,
) {
    @Composable
    fun ControlButton(control: RemoteControl, @StringRes label: Int, @StringRes description: Int) =
        RemoteButton(
            control = control,
            label = stringResource(label),
            contentDescription = stringResource(description),
            enabled = enabled,
            onPress = onPress,
            onRelease = onRelease,
            onTap = onTap,
            onHold = onHold,
        )

    Column(
        modifier = modifier
            .widthIn(max = 480.dp)
            .fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!enabled) {
            Text(
                text = stringResource(R.string.remote_disabled_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TouchSurface(
            enabled = enabled,
            onStep = onTap,
            onSelect = { onTap(RemoteControl.Select) },
            onHoldSelect = { onHold(RemoteControl.Select) },
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ControlButton(RemoteControl.Menu, R.string.button_menu_label, R.string.button_menu_description)
            ControlButton(RemoteControl.Home, R.string.button_home_label, R.string.button_home_description)
        }
        ControlButton(
            RemoteControl.PlayPause,
            R.string.button_play_pause_label,
            R.string.button_play_pause_description,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            ControlButton(RemoteControl.VolumeUp, R.string.button_volume_up_label, R.string.button_volume_up_description)
            ControlButton(RemoteControl.VolumeDown, R.string.button_volume_down_label, R.string.button_volume_down_description)
        }
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RemoteScreenConnectedPreview() {
    RingomoteTheme {
        RemoteScreen(enabled = true, onPress = {}, onRelease = {}, onTap = {}, onHold = {})
    }
}

@Preview(showBackground = true, heightDp = 900)
@Composable
private fun RemoteScreenDisabledPreview() {
    RingomoteTheme {
        RemoteScreen(enabled = false, onPress = {}, onRelease = {}, onTap = {}, onHold = {})
    }
}
