# Ringomote

An Android app that turns your phone into a remote for Apple TV, compatible with the Siri Remote (1st generation).

一個將您的手機變成 Apple TV 遙控器的 Android 程式，與 Siri Remote（第 1 代）相容。

## Status 開發狀態

Ringomote is in early development. The project has been set up, but remote control is not implemented yet, so the app cannot control an Apple TV today.

Ringomote 仍在早期開發階段。專案已建立，但遙控功能尚未實作，目前還無法操控 Apple TV。

## How it works 運作方式

The phone talks to the Apple TV directly over Bluetooth, presenting itself as a Bluetooth input device, just as the physical remote does. No Wi-Fi network, Apple account or bridge device is involved.

The planned controls follow the Siri Remote (1st generation): the touch surface (swipe and click), Menu, TV/Home, Play/Pause, and Volume up/down. Siri voice input is not planned at this stage.

手機透過藍牙直接與 Apple TV 連線，像實體遙控器一樣以藍牙輸入裝置的身分出現，不需經過 Wi-Fi 網路、Apple 帳號或其他橋接裝置。

預計提供的操作對應 Siri Remote（第 1 代）：觸控表面（滑動與按壓）、Menu、TV／主畫面、播放／暫停，以及音量加／減。目前沒有支援 Siri 語音輸入的規劃。

## Requirements 使用需求

- An Android phone running Android 9 (API 28) or later, with Bluetooth.
- An Apple TV that works with the Siri Remote (1st generation) or Bluetooth input devices.

- 執行 Android 9（API 28）以上版本、具備藍牙功能的 Android 手機。
- 可搭配 Siri Remote（第 1 代）或藍牙輸入裝置使用的 Apple TV。

## Pairing 配對

Pairing instructions will be added once remote control is implemented. Pairing is expected to start from the Apple TV's Bluetooth settings (Settings > Remotes and Devices > Bluetooth), with Ringomote open on the phone.

遙控功能實作完成後，將補上配對說明。預計的方式是在手機上開啟 Ringomote，再從 Apple TV 的藍牙設定（設定 > 遙控器與裝置 > 藍牙）開始配對。

## Building 建置

Requirements: Android SDK (compile SDK 37). Gradle provisions the JDK it runs on automatically (see `gradle/gradle-daemon-jvm.properties`).

需求：Android SDK（compile SDK 37）。Gradle 會自動取得執行所需的 JDK（請見 `gradle/gradle-daemon-jvm.properties`）。

```bash
./gradlew :app:assembleDebug        # Debug APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug         # Install on a connected phone 安裝至已連接的手機
./gradlew :app:testDebugUnitTest    # JVM unit tests 單元測試
./gradlew :app:lintDebug            # Lint
```

The Android emulator cannot act as a Bluetooth input device, so testing remote control needs a physical phone and an Apple TV.

Android 模擬器無法作為藍牙輸入裝置，因此測試遙控功能需要實體手機與 Apple TV。

## Trademarks 商標

Apple, Apple TV, Siri and Siri Remote are trademarks of Apple Inc., registered in the U.S. and other countries. Ringomote is an independent project and is not affiliated with, sponsored or endorsed by Apple Inc. These names are used only to describe compatibility.

Apple、Apple TV、Siri 與 Siri Remote 為 Apple Inc. 在美國及其他國家註冊的商標。Ringomote 為獨立專案，與 Apple Inc. 並無關聯，亦未獲其贊助或認可；上述名稱僅用於說明相容性。
