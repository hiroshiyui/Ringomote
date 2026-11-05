package org.ghostsinthelab.app.ringomote.ui

import android.content.Intent
import android.provider.Settings
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.ui.about.AboutScreen
import org.ghostsinthelab.app.ringomote.ui.connection.rememberSystemScreenLauncher
import org.ghostsinthelab.app.ringomote.diagnostics.DiagnosticsReport
import org.ghostsinthelab.app.ringomote.ui.settings.DiagnosticsScreen
import org.ghostsinthelab.app.ringomote.ui.settings.SettingsScreen
import org.ghostsinthelab.app.ringomote.settings.AppearanceSettings
import org.ghostsinthelab.app.ringomote.network.AppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import androidx.compose.runtime.DisposableEffect
import org.ghostsinthelab.app.ringomote.ui.settings.AppleTvViewModel
import org.ghostsinthelab.app.ringomote.network.mergeKnownAppleTvs
import org.ghostsinthelab.app.ringomote.ui.remote.AppleTvSelector
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import kotlinx.coroutines.delay
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
 * Three tabs: the remote, [SettingsScreen] (which can open [DiagnosticsScreen] in its place)
 * and [AboutScreen]. Settings and About are always
 * reachable; on the remote tab, until
 * every permission in [BluetoothPermissions.runtime] is granted it shows
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
    appearanceSettings: AppearanceSettings,
    diagnosticsReport: DiagnosticsReport,
    networkSettings: NetworkSettings,
    appleTvDiscovery: AppleTvDiscovery,
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

    var tab by rememberSaveable { mutableStateOf(AppTab.Remote) }
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            PrimaryTabRow(
                selectedTabIndex = tab.ordinal,
                modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
            ) {
                AppTab.entries.forEach { entry ->
                    Tab(
                        selected = tab == entry,
                        onClick = { tab = entry },
                        // One line: at 2.0x a wrapped label breaks mid-word and crowds the row.
                        text = { Text(stringResource(entry.label), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    )
                }
            }
        },
    ) { innerPadding ->
        val content = Modifier.padding(innerPadding)
        when {
            tab == AppTab.Settings && showDiagnostics -> DiagnosticsScreen(
                buildReport = diagnosticsReport::build,
                onBack = { showDiagnostics = false },
                modifier = content,
            )

            tab == AppTab.Settings -> {
                val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
                val networkEnabled by networkSettings.enabled.collectAsStateWithLifecycle()
                val appleTvs by appleTvDiscovery.appleTvs.collectAsStateWithLifecycle()
                val browsing by appleTvDiscovery.browsing.collectAsStateWithLifecycle()
                val appleTvVm: AppleTvViewModel = viewModel(factory = AppleTvViewModel.Factory)
                val linkStates by appleTvVm.states.collectAsStateWithLifecycle()
                val launchSystemScreen = rememberSystemScreenLauncher()
                // Browse only while the Settings tab shows the list and the user has opted in.
                DisposableEffect(networkEnabled) {
                    if (networkEnabled) appleTvDiscovery.start()
                    onDispose { appleTvDiscovery.stop() }
                }
                SettingsScreen(
                    appearance = appearance,
                    onModeChange = appearanceSettings::setMode,
                    onDynamicColorChange = appearanceSettings::setDynamicColor,
                    onLayoutChange = appearanceSettings::setLayout,
                    onOpenBluetoothSettings = { launchSystemScreen(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) },
                    onOpenDiagnostics = { showDiagnostics = true },
                    networkEnabled = networkEnabled,
                    onNetworkEnabledChange = networkSettings::setEnabled,
                    appleTvs = appleTvs,
                    browsing = browsing,
                    linkStateOf = { tv -> linkStates[tv.id] ?: appleTvVm.stateOf(tv) },
                    onPair = appleTvVm::startPairing,
                    onSubmitPin = appleTvVm::submitPin,
                    onCancelPairing = appleTvVm::cancelPairing,
                    onWake = appleTvVm::wake,
                    onForget = appleTvVm::forget,
                    modifier = content,
                )
            }
            tab == AppTab.About -> AboutScreen(modifier = content)
            granted -> RemoteHome(
                vm = vm,
                appearanceSettings = appearanceSettings,
                networkSettings = networkSettings,
                appleTvDiscovery = appleTvDiscovery,
                modifier = content,
            )
            else -> PermissionScreen(onGranted = { granted = true }, modifier = content)
        }
    }
}

/**
 * The top-level tabs. Leaving the Remote tab removes the remote from the screen, which releases
 * any key still held (see [org.ghostsinthelab.app.ringomote.ui.remote.RemoteButton]); the
 * Bluetooth link itself stays up, so switching back is instant.
 */
private enum class AppTab(@StringRes val label: Int) {
    Remote(R.string.tab_remote),
    Settings(R.string.tab_settings),
    About(R.string.tab_about),
}

/**
 * The connection panel and the remote, shown once permissions are granted.
 *
 * Above the panel, [AppleTvSelector] names the Apple TV the remote is aimed at, merged from
 * Companion pairings, the latest Bonjour result and the phone's bonds (see
 * [mergeKnownAppleTvs]). When the network is enabled, a short Bonjour browse runs on entry
 * to refresh which TVs are reachable, then stops to save the radio.
 */
@Composable
private fun RemoteHome(
    vm: RemoteViewModel,
    appearanceSettings: AppearanceSettings,
    networkSettings: NetworkSettings,
    appleTvDiscovery: AppleTvDiscovery,
    modifier: Modifier = Modifier,
) {
    val state by vm.transportState.collectAsStateWithLifecycle()
    val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
    val pairedDevices by vm.pairedDevices.collectAsStateWithLifecycle()
    val hasConnectedBefore by vm.hasConnectedBefore.collectAsStateWithLifecycle()

    // The bonded list is empty while Bluetooth is off; reload it once the link is usable.
    val ready = state is TransportState.Ready
    LaunchedEffect(ready) { if (ready) vm.refreshPairedDevices() }

    val appleTvVm: AppleTvViewModel = viewModel(factory = AppleTvViewModel.Factory)
    val networkEnabled by networkSettings.enabled.collectAsStateWithLifecycle()
    val selectedName by networkSettings.selectedAppleTv.collectAsStateWithLifecycle()
    val pairedNames by appleTvVm.pairedNames.collectAsStateWithLifecycle()
    val discovered by appleTvDiscovery.appleTvs.collectAsStateWithLifecycle()
    val linkStates by appleTvVm.states.collectAsStateWithLifecycle()
    DisposableEffect(networkEnabled) {
        if (networkEnabled) appleTvDiscovery.start()
        onDispose { appleTvDiscovery.stop() }
    }
    LaunchedEffect(networkEnabled) {
        if (networkEnabled) {
            delay(BROWSE_ON_ENTRY_MS)
            appleTvDiscovery.stop()
        }
    }
    val connectedHost = (state as? TransportState.Connected)?.device
    val knownTvs = remember(pairedNames, discovered, pairedDevices, connectedHost) {
        mergeKnownAppleTvs(pairedNames, discovered, pairedDevices, connectedHost)
    }
    // The stored choice if it is still known, else the connected one, else the only one.
    val selected = knownTvs.firstOrNull { it.name == selectedName }
        ?: knownTvs.firstOrNull { it.connected }
        ?: knownTvs.singleOrNull()

    RemoteScreen(
        enabled = state is TransportState.Connected,
        onPress = vm::press,
        onRelease = vm::release,
        onTap = vm::tap,
        onHold = vm::hold,
        modifier = modifier.padding(16.dp),
        layout = appearance.layout,
        header = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AppleTvSelector(
                    tvs = knownTvs,
                    selected = selected,
                    linkState = selected?.let { linkStates[it.name] ?: appleTvVm.stateOf(it.name) },
                    bluetoothConnected = connectedHost != null,
                    onSelect = { networkSettings.setSelectedAppleTv(it.name) },
                    onWake = { appleTvVm.wake(it.name) },
                )
                ConnectionPanel(
                    state = state,
                    pairedDevices = pairedDevices,
                    expandDetailsByDefault = !hasConnectedBefore,
                    onConnect = vm::connect,
                    onRetry = vm::start,
                )
            }
        },
    )
}

/** How long the Remote tab browses Bonjour after it appears. */
private const val BROWSE_ON_ENTRY_MS = 8_000L
