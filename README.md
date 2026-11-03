<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" alt="Ringomote icon" width="128"></p>

# Ringomote

An Android app that turns your phone into a remote for Apple TV, compatible with the Siri Remote (1st generation).

一個將您的手機變成 Apple TV 遙控器的 Android 程式，與 Siri Remote（第 1 代）相容。

## Status 開發狀態

Ringomote is in early development. Pairing with a real Apple TV and every control (touch surface, Menu, TV/Home, Play/Pause, Volume) have been verified on hardware. Known limitation: holding the TV button does not open Control Center as it does on the Siri Remote, because tvOS treats a keyboard's Home key as a tap only.

Ringomote 仍在早期開發階段。與實際的 Apple TV 配對，以及所有操作（觸控表面、Menu、TV／主畫面、播放／暫停、音量）都已在實機上驗證可行。已知限制：長按 TV 鍵無法像 Siri Remote 一樣開啟控制中心，因為 tvOS 只把鍵盤的主畫面鍵當作點按處理。

## How it works 運作方式

The phone talks to the Apple TV directly over Bluetooth Low Energy, presenting itself as a Bluetooth input device (a HID over GATT keyboard), just as the physical remote does. No Wi-Fi network, Apple account or bridge device is involved.

The planned controls follow the Siri Remote (1st generation): the touch surface (swipe and click), Menu, TV/Home, Play/Pause, and Volume up/down. Siri voice input is not planned at this stage.

手機透過藍牙低功耗直接與 Apple TV 連線，像實體遙控器一樣以藍牙輸入裝置（HID over GATT 鍵盤）的身分出現，不需經過 Wi-Fi 網路、Apple 帳號或其他橋接裝置。

預計提供的操作對應 Siri Remote（第 1 代）：觸控表面（滑動與按壓）、Menu、TV／主畫面、播放／暫停，以及音量加／減。目前沒有支援 Siri 語音輸入的規劃。

## Requirements 使用需求

- An Android phone running Android 9 (API 28) or later, with Bluetooth.
- An Apple TV that works with the Siri Remote (1st generation) or Bluetooth input devices.

- 執行 Android 9（API 28）以上版本、具備藍牙功能的 Android 手機。
- 可搭配 Siri Remote（第 1 代）或藍牙輸入裝置使用的 Apple TV。

## Pairing 配對

1. Open Ringomote and allow the Bluetooth permissions it asks for. The phone starts advertising itself as a remote right away (the pairing details open by themselves until the first connection; otherwise tap **Details**).
2. On the Apple TV, open **Settings > Remotes and Devices > Bluetooth** and pick your phone.
3. On the phone, accept the pairing request. A second prompt follows: open the notification shade, tap the Bluetooth pairing notification, check that the 6-digit code matches the one on the TV and tap **Pair**. The Apple TV then shows the phone as connected.
4. Once paired, the Apple TV reconnects by itself whenever it is awake and Ringomote is open. If it does not, tap **Connect** next to the Apple TV under **Details**.

1. 開啟 Ringomote，並允許它要求的藍牙權限。手機會立刻以遙控器的身分開始廣播（首次連線前，配對說明會自動展開；否則請點選「詳細資訊」）。
2. 到 Apple TV 開啟「設定 > 遙控器與裝置 > 藍牙」，選擇您的手機。
3. 在手機上接受配對要求。接著還有第二個提示：請拉下通知列，點選藍牙配對通知，確認 6 位數的配對碼與電視上顯示的一致，然後點選「配對」。Apple TV 隨即會顯示手機已連線。
4. 配對完成後，只要 Apple TV 醒著且 Ringomote 開啟中，Apple TV 就會自動重新連線。若沒有，請在「詳細資訊」中點選 Apple TV 旁的「連線」。

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

Apple, Apple TV, Siri and Siri Remote are trademarks of Apple Inc., registered in the U.S. and other countries. Android is a trademark of Google LLC. The Bluetooth® word mark and logos are registered trademarks owned by Bluetooth SIG, Inc. Ringomote is an independent project and is not affiliated with, sponsored or endorsed by any of them. These names are used only to describe compatibility and the technologies the app uses.

Apple、Apple TV、Siri 與 Siri Remote 為 Apple Inc. 在美國及其他國家註冊的商標。Android 為 Google LLC 的商標。Bluetooth® 文字商標與標誌為 Bluetooth SIG, Inc. 所有的註冊商標。Ringomote 為獨立專案，與上述公司並無關聯，亦未獲其贊助或認可；這些名稱僅用於說明相容性及本程式使用的技術。

## Third-party notices 第三方聲明

Ringomote uses open-source libraries, mostly under the Apache License 2.0. See [NOTICES.md](NOTICES.md) for the full list.

Ringomote 使用多項開放原始碼函式庫（大多以 Apache License 2.0 授權）。完整清單請見 [NOTICES.md](NOTICES.md)。

## License 授權

Ringomote is free software, released under the [GNU General Public License, version 3 or later](LICENSE). Third-party components keep their own licenses, as listed in [NOTICES.md](NOTICES.md).

Ringomote 是自由軟體，以 [GNU 通用公共授權第 3 版或其後版本](LICENSE)釋出。第三方元件則各依其原本的授權條款，詳見 [NOTICES.md](NOTICES.md)。
