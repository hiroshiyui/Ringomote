# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project goal

Ringomote is an Android app that makes the phone act as an Apple TV remote, compatible with the 1st-generation Siri Remote, so the Apple TV can be controlled directly from the phone (no Wi-Fi/companion-protocol bridge; the phone itself is the remote).

The codebase is currently the Android Studio "Empty Compose Activity" template. No remote/Bluetooth code exists yet.

Things to keep in mind when implementing the remote:
- The 1st-gen Siri Remote talks to the Apple TV over Bluetooth Low Energy (HID over GATT). The phone needs to act as a BLE peripheral exposing an HID service (`BluetoothGattServer` + `BluetoothLeAdvertiser`), or alternatively as a classic Bluetooth HID device via `BluetoothHidDevice` (API 28+), which tvOS accepts like a Bluetooth keyboard. Choose deliberately and document the choice here once made.
- `minSdk` is 28, so `BluetoothHidDevice` is always available. Android 12+ (API 31) needs the runtime permissions `BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE`, while older versions use the legacy `BLUETOOTH` / `BLUETOOTH_ADMIN` permissions; gate these with `Build.VERSION.SDK_INT` checks.
- Bluetooth HID peripheral behavior cannot be verified on the emulator; it needs a physical device paired to a real Apple TV.

## Build & test commands

```bash
./gradlew assembleDebug                 # build debug APK
./gradlew installDebug                  # build and install on a connected device
./gradlew lint                          # Android lint
./gradlew testDebugUnitTest             # JVM unit tests (app/src/test)
./gradlew connectedDebugAndroidTest     # instrumented tests on a device (app/src/androidTest)

# single test class / method
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.ExampleUnitTest"
./gradlew testDebugUnitTest --tests "org.ghostsinthelab.app.ringomote.ExampleUnitTest.addition_isCorrect"
./gradlew connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=org.ghostsinthelab.app.ringomote.ExampleInstrumentedTest
```

## Build setup notes

- Single module `:app`, package/namespace `org.ghostsinthelab.app.ringomote`. UI is Jetpack Compose with Material 3; theme lives in `ui/theme/`.
- Dependencies and plugin versions are managed in `gradle/libs.versions.toml`; add new dependencies there and reference them via `libs.*`.
- AGP 9 is used: Kotlin support is built into AGP, so there is no `org.jetbrains.kotlin.android` plugin. Only `kotlin.compose` is applied. Do not add the Kotlin Android plugin.
- R8 is configured with the AGP 9 `optimization { }` DSL in the `release` build type. Keep rules go in `app/src/main/keepRules/` (all files there are merged), not in `proguard-rules.pro`.
- compileSdk/targetSdk 37, Java/JVM target 11. Gradle configuration cache is enabled.
