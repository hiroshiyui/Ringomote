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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
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
import org.ghostsinthelab.app.ringomote.network.AppleTv
import org.ghostsinthelab.app.ringomote.network.companion.DeviceName

/**
 * The Settings tab: appearance, the remote's layout, and the Apple TVs on the network.
 *
 * Theme mode is a single-choice segmented row (System / Light / Dark); dynamic colour is a
 * switch whose whole row toggles, with [Role.Switch] semantics so TalkBack reads it as one
 * control. The switch is shown only where the platform can provide dynamic colour (Android
 * 12+), so the setting never promises what the phone cannot do. Changes apply immediately
 * through [RingomoteTheme]; nothing needs a restart.
 *
 * The Apple TV section starts with the name this phone pairs under ([remoteName], with
 * [phoneName] as the placeholder default; [onRemoteNameChange] stores it, and a new name
 * takes effect at the next pairing). Below it lists the Apple TVs found through Bonjour ([appleTvs], with a progress
 * bar while [browsing]). The caller starts and stops the browse; this screen only shows the
 * result. Each Apple TV row carries its Companion Link state ([linkStateOf]): Pair, then a
 * PIN field while the Apple TV shows one, then Wake and Forget.
 */
@Composable
fun SettingsScreen(
    appearance: Appearance,
    onModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onLayoutChange: (RemoteLayout) -> Unit,
    remoteName: String?,
    phoneName: String,
    onRemoteNameChange: (String?) -> Unit,
    appleTvs: List<AppleTv>,
    browsing: Boolean,
    linkStateOf: (AppleTv) -> AppleTvLinkState,
    onPair: (AppleTv) -> Unit,
    onSubmitPin: (AppleTv, String) -> Unit,
    onCancelPairing: (AppleTv) -> Unit,
    onWake: (AppleTv) -> Unit,
    onForget: (AppleTv) -> Unit,
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
                text = stringResource(R.string.settings_apple_tvs_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() }.padding(top = 16.dp),
            )
            Text(
                text = stringResource(R.string.settings_apple_tvs_description),
                style = MaterialTheme.typography.bodyMedium,
            )
            // The draft is local so the field keeps the caret; every change is stored at once.
            var nameDraft by rememberSaveable(remoteName) { mutableStateOf(remoteName.orEmpty()) }
            OutlinedTextField(
                value = nameDraft,
                onValueChange = { nameDraft = it; onRemoteNameChange(it) },
                label = { Text(stringResource(R.string.settings_remote_name_label)) },
                placeholder = { Text(phoneName) },
                supportingText = { Text(stringResource(R.string.settings_remote_name_hint, "${DeviceName.APP_NAME} (${nameDraft.ifBlank { phoneName }})")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            if (browsing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (appleTvs.isEmpty()) {
                Text(
                    text = stringResource(
                        if (browsing) R.string.settings_apple_tvs_searching else R.string.settings_apple_tvs_none,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            appleTvs.forEach { tv ->
                AppleTvRow(
                    tv = tv,
                    state = linkStateOf(tv),
                    onPair = { onPair(tv) },
                    onSubmitPin = { onSubmitPin(tv, it) },
                    onCancelPairing = { onCancelPairing(tv) },
                    onWake = { onWake(tv) },
                    onForget = { onForget(tv) },
                )
            }
        }
    }
}

/**
 * One discovered Apple TV with its Companion Link actions. The PIN field is kept here, not
 * in the ViewModel, since it is transient input; it survives rotation through
 * [rememberSaveable].
 */
@Composable
private fun AppleTvRow(
    tv: AppleTv,
    state: AppleTvLinkState,
    onPair: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onCancelPairing: () -> Unit,
    onWake: () -> Unit,
    onForget: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = { Text(tv.name) },
            supportingContent = {
                Column {
                    Text("${tv.modelName} · ${tv.host}")
                    statusText(state)?.let {
                        Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            },
            trailingContent = {
                when (state) {
                    is AppleTvLinkState.Busy -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    is AppleTvLinkState.Paired -> Button(onClick = onWake) { Text(stringResource(R.string.settings_apple_tv_wake)) }
                    is AppleTvLinkState.Unpaired, is AppleTvLinkState.Failed ->
                        OutlinedButton(onClick = onPair) { Text(stringResource(R.string.settings_apple_tv_pair)) }
                    AppleTvLinkState.AwaitingPin -> Unit
                }
            },
            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        )
        when (state) {
            AppleTvLinkState.AwaitingPin -> {
                var pin by rememberSaveable(tv.id) { mutableStateOf("") }
                Text(
                    text = stringResource(R.string.settings_apple_tv_pin_prompt),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = pin,
                        onValueChange = { pin = it.filter(Char::isDigit).take(4) },
                        label = { Text(stringResource(R.string.settings_apple_tv_pin_label)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        modifier = Modifier.weight(1f),
                    )
                    Button(onClick = { onSubmitPin(pin) }, enabled = pin.length == 4) {
                        Text(stringResource(R.string.settings_apple_tv_pin_confirm))
                    }
                    TextButton(onClick = onCancelPairing) { Text(stringResource(R.string.settings_apple_tv_pin_cancel)) }
                }
            }
            is AppleTvLinkState.Paired -> TextButton(onClick = onForget, modifier = Modifier.padding(horizontal = 8.dp)) {
                Text(stringResource(R.string.settings_apple_tv_forget))
            }
            else -> Unit
        }
    }
}

@Composable
private fun statusText(state: AppleTvLinkState): String? = when (state) {
    AppleTvLinkState.Unpaired -> stringResource(R.string.settings_apple_tv_status_unpaired)
    AppleTvLinkState.AwaitingPin -> null
    is AppleTvLinkState.Busy -> stringResource(
        when (state.what) {
            AppleTvLinkState.Work.Pairing -> R.string.settings_apple_tv_status_pairing
            AppleTvLinkState.Work.Waking -> R.string.settings_apple_tv_status_waking
        },
    )
    is AppleTvLinkState.Paired -> state.note?.let { stringResource(outcomeText(it)) }
        ?: stringResource(R.string.settings_apple_tv_status_paired)
    is AppleTvLinkState.Failed -> stringResource(outcomeText(state.reason))
}

@StringRes
private fun outcomeText(outcome: AppleTvLinkState.Outcome): Int = when (outcome) {
    AppleTvLinkState.Outcome.WakeSent -> R.string.settings_apple_tv_outcome_wake_sent
    AppleTvLinkState.Outcome.WakeFailed -> R.string.settings_apple_tv_outcome_wake_failed
    AppleTvLinkState.Outcome.Unreachable -> R.string.settings_apple_tv_outcome_unreachable
    AppleTvLinkState.Outcome.Rejected -> R.string.settings_apple_tv_outcome_rejected
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
            remoteName = null,
            phoneName = "Nokia G50",
            onRemoteNameChange = {},
            appleTvs = listOf(AppleTv("keting", "keting", "AppleTV14,1", "192.168.50.48", 49152)),
            browsing = true,
            linkStateOf = { AppleTvLinkState.AwaitingPin },
            onPair = {},
            onSubmitPin = { _, _ -> },
            onCancelPairing = {},
            onWake = {},
            onForget = {},
            showDynamicColor = true,
        )
    }
}
