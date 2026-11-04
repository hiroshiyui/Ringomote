package org.ghostsinthelab.app.ringomote.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.settings.Appearance
import org.ghostsinthelab.app.ringomote.settings.RemoteLayout
import org.ghostsinthelab.app.ringomote.settings.ThemeMode
import org.ghostsinthelab.app.ringomote.ui.theme.RingomoteTheme
import org.ghostsinthelab.app.ringomote.ui.theme.supportsDynamicColor

/**
 * The Settings tab: appearance, the remote's layout, a shortcut to the phone's Bluetooth
 * settings, and an Advanced section with Bluetooth diagnostics.
 *
 * Theme mode is a single-choice segmented row (System / Light / Dark); dynamic colour is a
 * switch whose whole row toggles, with [Role.Switch] semantics so TalkBack reads it as one
 * control. The switch is shown only where the platform can provide dynamic colour (Android
 * 12+), so the setting never promises what the phone cannot do. Changes apply immediately
 * through [RingomoteTheme]; nothing needs a restart.
 *
 * The Bluetooth section only opens the system screen via [onOpenBluetoothSettings]: a paired
 * Apple TV is removed from the phone there (Forget), which the app cannot do itself. Android
 * offers no Disconnect for a GATT-only link, so the text does not promise one.
 *
 * The Advanced section opens the Bluetooth diagnostics report ([onOpenDiagnostics]), meant
 * for attaching to a bug report; its text says what the report contains.
 */
@Composable
fun SettingsScreen(
    appearance: Appearance,
    onModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onLayoutChange: (RemoteLayout) -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    modifier: Modifier = Modifier,
    showDynamicColor: Boolean = supportsDynamicColor,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_appearance_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.settings_theme_label),
                style = MaterialTheme.typography.bodyMedium,
            )
            // IntrinsicSize.Min: a label that wraps at large font scales (「跟隨系統」 at 2.0×)
            // makes every segment as tall as the tallest, instead of a staircase.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = appearance.mode == mode,
                        onClick = { onModeChange(mode) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                        label = { Text(stringResource(mode.label)) },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
            if (showDynamicColor) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = appearance.dynamicColor,
                            role = Role.Switch,
                            onValueChange = onDynamicColorChange,
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_label),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.settings_dynamic_color_description),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    // The row carries the semantics; the switch itself is just the visual.
                    Switch(checked = appearance.dynamicColor, onCheckedChange = null)
                }
            }
            Text(
                text = stringResource(R.string.settings_layout_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.settings_layout_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                RemoteLayout.entries.forEachIndexed { index, layout ->
                    SegmentedButton(
                        selected = appearance.layout == layout,
                        onClick = { onLayoutChange(layout) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = RemoteLayout.entries.size),
                        label = { Text(stringResource(layout.label)) },
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
            }
            Text(
                text = stringResource(R.string.settings_bluetooth_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.settings_bluetooth_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenBluetoothSettings) {
                Text(stringResource(R.string.settings_bluetooth_open))
            }
            Text(
                text = stringResource(R.string.settings_advanced_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.settings_diagnostics_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            OutlinedButton(onClick = onOpenDiagnostics) {
                Text(stringResource(R.string.settings_diagnostics_open))
            }
        }
    }
}

@get:StringRes
private val RemoteLayout.label: Int
    get() = when (this) {
        RemoteLayout.Compact -> R.string.settings_layout_compact
        RemoteLayout.Spread -> R.string.settings_layout_spread
    }

@get:StringRes
private val ThemeMode.label: Int
    get() = when (this) {
        ThemeMode.System -> R.string.settings_theme_system
        ThemeMode.Light -> R.string.settings_theme_light
        ThemeMode.Dark -> R.string.settings_theme_dark
    }

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    RingomoteTheme {
        SettingsScreen(
            appearance = Appearance(),
            onModeChange = {},
            onDynamicColorChange = {},
            onLayoutChange = {},
            onOpenBluetoothSettings = {},
            onOpenDiagnostics = {},
            showDynamicColor = true,
        )
    }
}
