package org.ghostsinthelab.app.ringomote.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.ghostsinthelab.app.ringomote.R
import org.ghostsinthelab.app.ringomote.network.AppleTvDiscovery
import org.ghostsinthelab.app.ringomote.network.NetworkSettings
import org.ghostsinthelab.app.ringomote.network.companion.CompanionState
import org.ghostsinthelab.app.ringomote.network.mergeKnownAppleTvs
import org.ghostsinthelab.app.ringomote.settings.AppearanceSettings
import org.ghostsinthelab.app.ringomote.ui.about.AboutScreen
import org.ghostsinthelab.app.ringomote.ui.connection.ConnectionPanel
import org.ghostsinthelab.app.ringomote.ui.remote.AppleTvSelector
import org.ghostsinthelab.app.ringomote.ui.remote.RemoteScreen
import org.ghostsinthelab.app.ringomote.ui.remote.RemoteViewModel
import org.ghostsinthelab.app.ringomote.ui.settings.AppleTvViewModel
import org.ghostsinthelab.app.ringomote.ui.settings.SettingsScreen

/**
 * Root of the UI: wires [RemoteViewModel] to the screens.
 *
 * Three tabs: the remote, [SettingsScreen] and [AboutScreen]. The remote tab shows the
 * [ConnectionPanel] above the [RemoteScreen]; the Settings tab finds Apple TVs on the network
 * and pairs with them.
 *
 * Lifecycle rules: on stop every held control is released (a finger that was down when the app
 * went to the background never sends its "up", and the Apple TV would see a stuck key) and the
 * session is told the app left the foreground, so it closes after its grace.
 */
@Composable
fun RingomoteApp(
    appearanceSettings: AppearanceSettings,
    networkSettings: NetworkSettings,
    appleTvDiscovery: AppleTvDiscovery,
    phoneName: String,
    modifier: Modifier = Modifier,
    vm: RemoteViewModel = viewModel(factory = RemoteViewModel.Factory),
) {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { vm.setForeground(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.releaseAll()
        vm.setForeground(false)
    }

    var tab by rememberSaveable { mutableStateOf(AppTab.Remote) }

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
        when (tab) {
            AppTab.Settings -> {
                val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()
                val appleTvs by appleTvDiscovery.appleTvs.collectAsStateWithLifecycle()
                val browsing by appleTvDiscovery.browsing.collectAsStateWithLifecycle()
                val appleTvVm: AppleTvViewModel = viewModel(factory = AppleTvViewModel.Factory)
                val linkStates by appleTvVm.states.collectAsStateWithLifecycle()
                val remoteName by networkSettings.remoteName.collectAsStateWithLifecycle()
                // Browse only while the Settings tab shows the list.
                DisposableEffect(Unit) {
                    appleTvDiscovery.start()
                    onDispose { appleTvDiscovery.stop() }
                }
                SettingsScreen(
                    appearance = appearance,
                    onModeChange = appearanceSettings::setMode,
                    onDynamicColorChange = appearanceSettings::setDynamicColor,
                    onLayoutChange = appearanceSettings::setLayout,
                    remoteName = remoteName,
                    phoneName = phoneName,
                    onRemoteNameChange = networkSettings::setRemoteName,
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
            AppTab.About -> AboutScreen(modifier = content)
            AppTab.Remote -> RemoteHome(
                vm = vm,
                appearanceSettings = appearanceSettings,
                networkSettings = networkSettings,
                appleTvDiscovery = appleTvDiscovery,
                onOpenSettings = { tab = AppTab.Settings },
                modifier = content,
            )
        }
    }
}

/**
 * The top-level tabs. Leaving the Remote tab removes the remote from the screen, which releases
 * any key still held (see [org.ghostsinthelab.app.ringomote.ui.remote.RemoteButton]) and lets
 * the session close after its grace, so switching back within it is instant.
 */
private enum class AppTab(@StringRes val label: Int) {
    Remote(R.string.tab_remote),
    Settings(R.string.tab_settings),
    About(R.string.tab_about),
}

/**
 * The connection panel and the remote.
 *
 * Above the panel, [AppleTvSelector] names the Apple TV the remote is aimed at, merged from
 * the stored pairings and the latest Bonjour result (see [mergeKnownAppleTvs]). A short
 * Bonjour browse runs on entry to refresh which TVs are reachable, then stops to save the
 * radio. The remote is enabled while the session is connected; the ViewModel holds the
 * session only while this screen is composed. [onOpenSettings] switches to the Settings tab
 * for the pairing reminder.
 */
@Composable
private fun RemoteHome(
    vm: RemoteViewModel,
    appearanceSettings: AppearanceSettings,
    networkSettings: NetworkSettings,
    appleTvDiscovery: AppleTvDiscovery,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val companionState by vm.companionState.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        vm.setRemoteShown(true)
        onDispose { vm.setRemoteShown(false) }
    }
    val appearance by appearanceSettings.appearance.collectAsStateWithLifecycle()

    val appleTvVm: AppleTvViewModel = viewModel(factory = AppleTvViewModel.Factory)
    val selectedName by networkSettings.selectedAppleTv.collectAsStateWithLifecycle()
    val pairedNames by appleTvVm.pairedNames.collectAsStateWithLifecycle()
    val discovered by appleTvDiscovery.appleTvs.collectAsStateWithLifecycle()
    val linkStates by appleTvVm.states.collectAsStateWithLifecycle()
    DisposableEffect(Unit) {
        appleTvDiscovery.start()
        onDispose { appleTvDiscovery.stop() }
    }
    LaunchedEffect(Unit) {
        delay(BROWSE_ON_ENTRY_MS)
        appleTvDiscovery.stop()
    }
    val knownTvs = remember(pairedNames, discovered) { mergeKnownAppleTvs(pairedNames, discovered) }
    // The stored choice if it is still known, else the only one.
    val selected = knownTvs.firstOrNull { it.name == selectedName } ?: knownTvs.singleOrNull()

    RemoteScreen(
        enabled = companionState is CompanionState.Connected,
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
                    connected = companionState is CompanionState.Connected,
                    onSelect = { networkSettings.setSelectedAppleTv(it.name) },
                    onWake = { appleTvVm.wake(it.name) },
                    onPairOverNetwork = onOpenSettings,
                )
                ConnectionPanel(state = companionState, onOpenSettings = onOpenSettings)
            }
        },
    )
}

/** How long the Remote tab browses Bonjour after it appears. */
private const val BROWSE_ON_ENTRY_MS = 8_000L
