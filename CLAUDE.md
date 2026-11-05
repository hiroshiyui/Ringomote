# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Ringomote is an Android app that makes the phone act as an Apple TV remote, compatible with the 1st-generation Siri Remote. It talks to the Apple TV over the local Wi-Fi network with Apple's Companion Link protocol, the same link the iPhone and iPad Remote use, so it can also wake a sleeping Apple TV.

History: versions up to 0.5.0 made the phone a BLE HID keyboard instead. That path was dropped on 2026-11-06 because the Apple TV owns a Bluetooth link (it cannot be woken, and tvOS caches "no keyboard" for good whenever the app's process dies under an open link, and a link that comes back subscribed may still swallow every key), while the Companion session is owned by the phone and simply reconnects. The whole Bluetooth implementation, its tests, the HCI snoop tooling and the hardware findings live in the `bluetooth-archive` branch; do not bring Bluetooth back into `main` without a decision from the user.

## Architecture

Layers, with dependencies pointing downward only (`ui → network/companion → remote`):

- `remote/` — pure Kotlin. `RemoteControl`, the Siri Remote controls the app reproduces (arrows, Select, Menu, Home, Play/Pause, Volume up/down; no Mute, since Companion Link has no mute command, and no Siri).
- `network/` — `AppleTvDiscovery` (interface; pure Kotlin helpers `CompanionLink`/`AppleTvModels`, unit-tested) finds Apple TVs through Bonjour; `NsdAppleTvDiscovery` implements it on `NsdManager`, browsing `_companion-link._tcp` and keeping services whose `rpMd` starts with `AppleTV`, with a multicast lock held while browsing; `RingomoteApp` starts it while the Settings tab shows the list and for a short browse when the Remote tab appears. `LocalNetwork` binds sockets to the non-VPN Wi-Fi/Ethernet network. `NetworkSettings` keeps the selected Apple TV's name. `KnownAppleTv`/`mergeKnownAppleTvs` merge pairings and Bonjour results by name for the Remote tab's selector.
- `network/companion/` — the Companion Link client: pure Kotlin `Tlv8`, `Opack`, `HapSrpClient` (SRP-6a, tested against srptools vectors), `PairSetup`/`PairVerify` (HAP, transport-free state machines over TLV bytes), `CompanionCredentials`; `HapCrypto` wraps Bouncy Castle's lightweight API (never register its JCA provider); `CompanionClient` is the blocking framed/encrypted socket client (run on `Dispatchers.IO`); `CompanionCredentialsStore` keeps pairings per Bonjour service name, excluded from backups. `CompanionCommand`/`CompanionKeyMap` are the `_hidC` button table (pyatv's `HidCommand`); `CompanionSession` (pure Kotlin behind `CompanionLinkPort`, fake-tested) keeps one verified remote session open with the selected paired TV while `RemoteViewModel` wants it (Remote tab shown, app in front), with a 10 s grace on close, 1/2/5/10 s back-off while unreachable and no retry after a refused pairing until credentials change; it tracks held commands so `releaseAll()` never leaves a key stuck on the Apple TV; its `ClientPort` is the real one. `ui/settings/AppleTvViewModel` drives Pair → PIN → Wake/Forget per row. `DeviceName` builds the name the Apple TV lists the phone under, "Ringomote (<phone name>)", from `NetworkSettings.remoteName` (the field in Settings), else the system device name, else the model, so several phones in one home stay apart; it is sent at pair-setup, so an existing pairing keeps its old name until paired afresh. Protocol notes and hardware findings: `docs/companion-link.md`. Manifest: `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`; no runtime permissions.
- `ui/` — Compose. `RemoteViewModel` sends every press to the session on `AppContainer.serialDispatcher`, shared app-wide, so presses keep their order and a closing screen's release cannot overtake the next screen's input; it releases held controls whenever the session leaves `Connected`, sets the session's target from the selected (or only) pairing, and wants the session only while the Remote tab is composed in the foreground. Screens depend only on the ViewModel and state types. `RingomoteApp` has three tabs: the remote (`ui/connection/ConnectionPanel` with the session state above `ui/remote/RemoteScreen`, and `ui/remote/AppleTvSelector` naming the target with Wake and a pair reminder), `ui/settings/SettingsScreen` and `ui/about/AboutScreen`; the About texts (privacy, license, open-source notices, trademarks) summarise `LICENSE`, `NOTICES.md` and the README, so update them together, in all three locales (`values`, `values-zh-rTW`, `values-ja`).
- `settings/` — `AppearanceSettings`: theme mode (System/Light/Dark), dynamic colour and the remote layout, in SharedPreferences, published as a `StateFlow`. `MainActivity` feeds it to `RingomoteTheme`, which picks dynamic colours on Android 12+ when asked, else the coral `RingomoteLightColors`/`RingomoteDarkColors` in `ui/theme/Color.kt`. Never hard-code colours in screens; use `MaterialTheme.colorScheme` roles so all three schemes work.
- `AppContainer` (created by `RingomoteApplication`) is manual DI: process-lifetime session, serial dispatcher and application scope. Swap implementations there.

Verified on hardware (Nokia G50, Android 13, Apple TV 4K): Bonjour discovery, pairing with the PIN, wake, and every control over the session, including the 1.5 s hold of TV for Control Center. There is no foreground service and no boot receiver: the session needs the process only while the remote is on screen. Network behaviour can be tested on the emulator only with a bridged network; a phone is simpler.

## Build & test commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build and install on a connected device
./gradlew lint                          # Android lint
./gradlew testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew connectedDebugAndroidTest     # instrumented tests on a device (app/src/androidTest)

# single test class / method
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.network.companion.CompanionSessionTest"
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.network.companion.CompanionSessionTest.<method>"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>
```

## Build setup notes

- Single module `:app`, package/namespace `org.ghostsinthelab.app.ringomote`. UI is Jetpack Compose with Material 3; theme lives in `ui/theme/`.
- Dependencies and plugin versions are managed in `gradle/libs.versions.toml`; add new dependencies there and reference them via `libs.*`.
- AGP 9 is used: Kotlin support is built into AGP, so there is no `org.jetbrains.kotlin.android` plugin. Only `kotlin.compose` is applied. Do not add the Kotlin Android plugin.
- R8 is configured with the AGP 9 `optimization { }` DSL in the `release` build type. Keep rules go in `app/src/main/keepRules/` (all files there are merged), not in `proguard-rules.pro`.
- compileSdk/targetSdk 37, Java/JVM target 11. Gradle configuration cache is enabled.
