<p align="center"><img src="fastlane/metadata/android/en-US/images/icon.png" alt="Ringomote icon" width="128"></p>

# Ringomote

An Android app that turns your phone into a remote for Apple TV, compatible with the Siri Remote (1st generation).

一個將您的手機變成 Apple TV 遙控器的 Android 程式，與 Siri Remote（第 1 代）相容。

## Status 開發狀態

Ringomote is ready for everyday use. Pairing with a real Apple TV over the network, reconnection after the app restarts or the Apple TV wakes, waking a sleeping Apple TV, and every control (touch surface, Menu, TV/Home, Play/Pause, Volume) have been verified on hardware, including holding the TV button to open Control Center. Siri voice input is not reproduced, and there is no Mute button, because the protocol has none.

Earlier versions (up to 0.5.0) worked as a Bluetooth keyboard instead; that code lives on in the `bluetooth-archive` branch.

Ringomote 已可供日常使用。透過網路與實際的 Apple TV 配對、App 重新啟動或 Apple TV 喚醒後的重新連線、喚醒睡眠中的 Apple TV，以及所有操作（觸控表面、Menu、TV／主畫面、播放／暫停、音量）都已在實機上驗證可行，包括長按 TV 鍵開啟控制中心。Siri 語音輸入無法重現；也沒有靜音鍵，因為該協定沒有這個指令。

較早的版本（至 0.5.0 為止）是以藍牙鍵盤的方式運作；那些程式碼保留在 `bluetooth-archive` 分支。

## Screenshots 螢幕截圖

<p align="center">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1-remote-compact.png" alt="The remote, compact layout, connected to an Apple TV" width="180">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2-remote-spread.png" alt="The remote, spread-out layout" width="180">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3-remote-dark.png" alt="The remote in the dark theme" width="180">
<img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4-settings.png" alt="The Settings tab: theme, dynamic colour, layout, the phone's name and the paired Apple TV" width="180">
</p>

Left to right: the remote in the compact and spread-out layouts and in the dark theme, controlling an Apple TV over Wi-Fi, and the Settings tab with the phone's name and the paired Apple TV. Taken on a Nokia G50 with dynamic colour on, so the palette follows the wallpaper.

由左至右：緊湊與延伸至邊緣版面的遙控器、深色主題（皆正透過 Wi-Fi 控制 Apple TV），以及含手機名稱與已配對 Apple TV 的「設定」分頁。於 Nokia G50 上擷取，開啟了動態色彩，因此配色跟隨桌布。

## How it works 運作方式

The phone talks to the Apple TV over your Wi-Fi network with Apple's Companion Link protocol, the same link the iPhone and iPad Remote use. Ringomote finds Apple TVs on the local network with Bonjour, pairs with one by typing the 4-digit code the Apple TV shows (a HomeKit-style pairing that leaves a key pair on each side), and from then on opens an encrypted session with it whenever the remote is on screen. Every control is sent as a button-down and button-up on that session, so a hold works as it does on the Siri Remote: holding TV opens Control Center. The same session wakes a sleeping Apple TV, which neither Bluetooth nor Wake-on-LAN can do. No Apple account, bridge device or server is involved, and the app never contacts anything beyond the local network.

The session is held only while the remote is on screen and the app is in front; it lingers ten seconds after that so a tab switch is free, backs off while the Apple TV cannot be reached, and stops retrying after a refused pairing until you pair again.

Siri voice input is not reproduced, and there is no Mute button: Companion Link has no mute command, only an absolute volume level.

手機透過您的 Wi-Fi 網路，以 Apple 的 Companion Link 協定與 Apple TV 通訊，也就是 iPhone 與 iPad 的「遙控器」所用的同一條連線。Ringomote 會以 Bonjour 在區域網路上尋找 Apple TV，輸入 Apple TV 顯示的 4 位數代碼完成配對（類似 HomeKit 的配對，雙方各留下一組金鑰），之後只要遙控器在畫面上，就會與它建立加密連線。每個操作都以按下與放開的形式經由這條連線送出，因此長按的行為與 Siri Remote 相同：長按 TV 鍵會開啟控制中心。同一條連線也能喚醒睡眠中的 Apple TV，這是藍牙與 Wake-on-LAN 都做不到的。過程不需要 Apple 帳號、橋接裝置或伺服器，本程式也絕不會連線到區域網路以外的任何地方。

連線只在遙控器顯示於畫面且程式在前景時保持；離開後會再維持十秒，讓切換分頁不必重新連線；連不上 Apple TV 時會逐步拉長重試間隔；配對被拒絕後則停止重試，直到您重新配對。

Siri 語音輸入無法重現，也沒有靜音鍵：Companion Link 沒有靜音指令，只有絕對音量。

## Requirements 使用需求

- An Android phone running Android 9 (API 28) or later, on the same Wi-Fi network as the Apple TV.
- An Apple TV that works with the iPhone or iPad Remote (tvOS with Companion Link, which every Apple TV HD and 4K has).

- 執行 Android 9（API 28）以上版本、與 Apple TV 在同一個 Wi-Fi 網路上的 Android 手機。
- 可搭配 iPhone 或 iPad「遙控器」使用的 Apple TV（具備 Companion Link 的 tvOS，Apple TV HD 與 4K 皆有）。

## Pairing 配對

1. Open Ringomote and go to **Settings**. Optionally give this phone a name (the Apple TV lists it as *Ringomote (name)*; with several phones of the same model, each needs its own). The Apple TVs on your network appear under *Apple TVs on this network* within a few seconds.
2. Tap **Pair** next to your Apple TV. The Apple TV shows a 4-digit code; type it into the field that appears and tap **Confirm**.
3. Go back to the **Remote** tab. The panel says *Controlling <name> over Wi-Fi* and the controls appear. If the Apple TV is asleep, tap **Wake** first.
4. The pairing lasts until you remove it with **Forget pairing** in Settings or remove Ringomote on the Apple TV under **Settings > Remotes and Devices**; if the Apple TV then rejects the pairing, the panel says so and offers Settings to pair again.

1. 開啟 Ringomote，進入「設定」。您可以為這支手機取個名稱（Apple TV 會將它列為「Ringomote (名稱)」；同型號的手機有好幾支時，每支都需要不同的名稱）。幾秒之內，您網路上的 Apple TV 會列在「這個網路上的 Apple TV」之下。
2. 點選您的 Apple TV 旁的「配對」。Apple TV 會顯示 4 位數代碼；請在出現的欄位輸入並點選「確認」。
3. 回到「遙控器」分頁。面板會顯示「正透過 Wi-Fi 控制 <名稱>」，操作介面隨即出現。若 Apple TV 在睡眠中，請先點選「喚醒」。
4. 配對會一直保留，直到您在「設定」點選「清除配對」，或在 Apple TV 的「設定 > 遙控器與裝置」移除 Ringomote；若 Apple TV 之後拒絕配對，面板會顯示並提供「設定」讓您重新配對。

## Permissions and privacy 權限與隱私

Ringomote collects nothing and contacts no server. The permissions it declares, and why:

- **Network** (`INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_WIFI_MULTICAST_STATE`): to find Apple TVs with Bonjour and to talk to the paired one over Companion Link, encrypted with the pairing keys. Nothing goes beyond the local network.

Stored on the phone: the appearance settings, the chosen Apple TV, this phone's name, and, once you pair, the Companion Link pairing keys and the Apple TV's last address. The pairing keys include a private key, so that file is excluded from Android backups; everything else may be backed up.

Ringomote 不會收集任何資料，也不會連線到任何伺服器。它宣告的權限及其用途如下：

- **網路**（`INTERNET`、`ACCESS_NETWORK_STATE`、`CHANGE_WIFI_MULTICAST_STATE`）：用來以 Bonjour 尋找 Apple TV，並以配對金鑰加密、透過 Companion Link 與已配對的 Apple TV 通訊。不會有任何資料送出區域網路。

儲存在手機上的資料：外觀設定、所選的 Apple TV、這支手機的名稱，以及配對後的 Companion Link 配對金鑰與 Apple TV 最近的位址。配對金鑰含有私鑰，因此該檔案不納入 Android 備份；其他資料可以備份。

## Building 建置

Requirements: Android SDK (compile SDK 37). Gradle provisions the JDK it runs on automatically (see `gradle/gradle-daemon-jvm.properties`).

需求：Android SDK（compile SDK 37）。Gradle 會自動取得執行所需的 JDK（請見 `gradle/gradle-daemon-jvm.properties`）。

```bash
./gradlew :app:assembleDebug        # Debug APK → app/build/outputs/apk/debug/
./gradlew :app:installDebug         # Install on a connected phone 安裝至已連接的手機
./gradlew :app:testDebugUnitTest    # JVM unit tests 單元測試
./gradlew :app:lintDebug            # Lint
```

Testing remote control needs an Apple TV on the same network; the emulator can reach it if its network is bridged, but a physical phone is simpler.

測試遙控功能需要同一網路上的 Apple TV；模擬器在橋接網路下也能連到，但用實體手機較簡單。

## Trademarks 商標

Apple, Apple TV, Siri and Siri Remote are trademarks of Apple Inc., registered in the U.S. and other countries. Android is a trademark of Google LLC. Ringomote is an independent project and is not affiliated with, sponsored or endorsed by any of them. These names are used only to describe compatibility and the technologies the app uses.

Apple、Apple TV、Siri 與 Siri Remote 為 Apple Inc. 在美國及其他國家註冊的商標。Android 為 Google LLC 的商標。Ringomote 為獨立專案，與上述公司並無關聯，亦未獲其贊助或認可；這些名稱僅用於說明相容性及本程式使用的技術。

## Third-party notices 第三方聲明

Ringomote uses open-source libraries, mostly under the Apache License 2.0. See [NOTICES.md](NOTICES.md) for the full list.

Ringomote 使用多項開放原始碼函式庫（大多以 Apache License 2.0 授權）。完整清單請見 [NOTICES.md](NOTICES.md)。

## License 授權

Ringomote is free software, released under the [GNU General Public License, version 3 or later](LICENSE). Third-party components keep their own licenses, as listed in [NOTICES.md](NOTICES.md).

Ringomote 是自由軟體，以 [GNU 通用公共授權第 3 版或其後版本](LICENSE)釋出。第三方元件則各依其原本的授權條款，詳見 [NOTICES.md](NOTICES.md)。
