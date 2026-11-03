# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Ringomote is an Android app that makes the phone act as an Apple TV remote, compatible with the 1st-generation Siri Remote, so the Apple TV can be controlled directly from the phone (no Wi-Fi/companion-protocol bridge; the phone itself is the remote).

## Architecture

Layers, with dependencies pointing downward only (`ui → remote → hid ← bluetooth`):

- `hid/` — pure Kotlin, no Android imports. The HID report descriptor (`HidReportDescriptor`, annotated byte by byte), report encoders (`HidReports`), usage constants, and the `HidReportSink` interface. Two input reports: ID 1 keyboard (8 bytes, 6-key array) and ID 2 consumer control (one 16-bit usage). Changing the descriptor means changing the encoders and the descriptor-parsing tests together.
- `remote/` — pure Kotlin. `RemoteControl` (the Siri Remote controls), `HidKeyMap` (control → HID usage; arrows/Enter/Escape on the keyboard page, Home/Play-Pause/Volume on the consumer page), and `RemoteSession`, which tracks held controls and turns press/release into reports. Invariant: every press is eventually released; `releaseAll()` runs when input stops (app stopped, disconnect, ViewModel cleared) so the Apple TV never sees a stuck key.
- `bluetooth/` — Android-specific. `HidTransport` interface (state as `StateFlow<TransportState>`, start/stop, connect/disconnect, also a `HidReportSink`) and `BluetoothHidDeviceTransport`, the only class that touches `android.bluetooth`. The transport is a thin adapter: the connection state machine and its recovery rules (retry, Bluetooth off/on, late events from a dead proxy) live in `HidLinkController`, which is pure Kotlin behind the `HidProfilePort` interface and is unit-tested with a fake port. Put new connection logic there, not in the transport. `BluetoothPermissions` centralises the API-31 permission split.
- `ui/` — Compose. `RemoteViewModel` runs everything that touches the transport (input, connect, start/stop) on `AppContainer.serialDispatcher`, shared app-wide, so report order is kept, binder calls stay off the main thread, and a closing screen's stop cannot overtake the next screen's start. It also releases held controls whenever the link leaves `Connected`. Screens depend only on the ViewModel and state types.
- `history/` — `ConnectionHistory`: one persisted boolean, "has ever connected to a host" (Android can't tell an Apple TV from other bonded devices, so this stands in for "Apple TV paired"). The connection panel auto-expands its pairing details until it is true.
- `AppContainer` (created by `RingomoteApplication`) is manual DI: process-lifetime transport, session, serial dispatcher and application scope. Swap implementations there.

Transport decision: classic Bluetooth HID via `BluetoothHidDevice` (official API since 28, accepted by tvOS like a Bluetooth keyboard), not BLE HID over GATT. It stays behind `HidTransport` so a BLE implementation could be added without touching `remote/` or `ui/`. Only one app per phone can be registered as a HID device; the Apple TV sees the phone's Bluetooth name. The touch surface is emulated with discrete arrow keys; Siri voice input is not reproduced.

Unverified on hardware (check on a real Apple TV before relying on it): AC Home (0x223) for the TV/Home button, Escape for Menu, and whether pairing works best from the Apple TV side (phone discoverable) or the phone side (`connect` to a bonded device). Bluetooth behaviour cannot be tested on the emulator.

- Android 12+ (API 31) needs the runtime permissions `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`; Android 9–11 use install-time `BLUETOOTH` / `BLUETOOTH_ADMIN` (`maxSdkVersion="30"`).

## Build & test commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build and install on a connected device
./gradlew lint                          # Android lint
./gradlew testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew connectedDebugAndroidTest     # instrumented tests on a device (app/src/androidTest)

# single test class / method
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.remote.RemoteSessionTest"
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.remote.RemoteSessionTest.<method>"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<fully.qualified.TestClass>
```

## Build setup notes

- Single module `:app`, package/namespace `org.ghostsinthelab.app.ringomote`. UI is Jetpack Compose with Material 3; theme lives in `ui/theme/`.
- Dependencies and plugin versions are managed in `gradle/libs.versions.toml`; add new dependencies there and reference them via `libs.*`.
- AGP 9 is used: Kotlin support is built into AGP, so there is no `org.jetbrains.kotlin.android` plugin. Only `kotlin.compose` is applied. Do not add the Kotlin Android plugin.
- R8 is configured with the AGP 9 `optimization { }` DSL in the `release` build type. Keep rules go in `app/src/main/keepRules/` (all files there are merged), not in `proguard-rules.pro`.
- compileSdk/targetSdk 37, Java/JVM target 11. Gradle configuration cache is enabled.
