# Third-Party Notices 第三方聲明

Ringomote includes or depends on the following third-party resources.

Ringomote 包含或使用下列第三方資源。

## Libraries 函式庫

The following libraries are distributed under the [Apache License, Version 2.0](https://www.apache.org/licenses/LICENSE-2.0).

下列函式庫以 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 授權釋出。

| Library 函式庫 | Artifact | Version 版本 |
|---|---|---|
| AndroidX Core KTX | `androidx.core:core-ktx` | 1.19.1 |
| AndroidX Activity Compose | `androidx.activity:activity-compose` | 1.13.0 |
| AndroidX Lifecycle Runtime KTX | `androidx.lifecycle:lifecycle-runtime-ktx` | 2.11.0 |
| Jetpack Compose UI | `androidx.compose.ui:ui`, `ui-graphics`, `ui-tooling-preview` | BOM 2026.02.01 |
| Jetpack Compose Material 3 | `androidx.compose.material3:material3` | BOM 2026.02.01 |
| Kotlin Standard Library | `org.jetbrains.kotlin:kotlin-stdlib` | 2.2.10 |

Their transitive dependencies are also under the Apache License 2.0: other AndroidX libraries, kotlinx.coroutines, kotlinx.serialization, JetBrains Java Annotations (`org.jetbrains:annotations`), JSpecify and Guava ListenableFuture.

上述函式庫的間接相依套件同樣以 Apache License 2.0 授權：其他 AndroidX 函式庫、kotlinx.coroutines、kotlinx.serialization、JetBrains Java Annotations（`org.jetbrains:annotations`）、JSpecify 與 Guava ListenableFuture。

### Bouncy Castle

The Companion Link pairing (Ed25519, X25519, HKDF, ChaCha20-Poly1305) uses the [Bouncy Castle](https://www.bouncycastle.org/) lightweight crypto API, `org.bouncycastle:bcprov-jdk18on` 1.79, under the [Bouncy Castle License](https://www.bouncycastle.org/licence.html) (an MIT-style license):

> Copyright (c) 2000-2024 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)
>
> Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

Companion Link 配對（Ed25519、X25519、HKDF、ChaCha20-Poly1305）使用 [Bouncy Castle](https://www.bouncycastle.org/) 的輕量級密碼學 API（`org.bouncycastle:bcprov-jdk18on` 1.79），以 [Bouncy Castle License](https://www.bouncycastle.org/licence.html)（MIT 式授權）釋出，全文如上。

The protocol itself was worked out from the [pyatv](https://pyatv.dev/) project's documentation (MIT License); no pyatv code is included. The SRP-6a client's byte conventions were checked against test vectors from [srptools](https://github.com/idlesign/srptools) (BSD 3-Clause License); no srptools code is included either.

通訊協定內容參考自 [pyatv](https://pyatv.dev/) 專案的文件（MIT 授權）；本程式不包含 pyatv 的程式碼。SRP-6a 用戶端的位元組慣例以 [srptools](https://github.com/idlesign/srptools)（BSD 3-Clause 授權）的測試向量驗證；本程式亦不包含 srptools 的程式碼。

### Build and test tools 建置與測試工具

These are used only to build or test the app and are not included in the distributed APK.

下列工具僅用於建置或測試，不包含在發佈的 APK 中。

| Tool 工具 | License 授權 |
|---|---|
| Gradle (including the wrapper in `gradle/wrapper/`), Android Gradle Plugin, Kotlin Compose compiler plugin | Apache License 2.0 |
| AndroidX Test (JUnit ext, Espresso), Compose UI Test / Tooling | Apache License 2.0 |
| kotlinx-coroutines-test | Apache License 2.0 |
| JUnit 4 | [Eclipse Public License 1.0](https://www.eclipse.org/legal/epl-v10.html) |

## Artwork 圖像

### Material Icons

The Wi-Fi status icon (`app/src/main/res/drawable/ic_wifi.xml`) and the Menu and TV button icons (`ic_menu.xml`, `ic_tv.xml`) use path data from Google's [Material Icons](https://github.com/google/material-design-icons), licensed under the [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0).

Wi-Fi 狀態圖示（`app/src/main/res/drawable/ic_wifi.xml`）與 Menu、TV 按鍵圖示（`ic_menu.xml`、`ic_tv.xml`）使用 Google [Material Icons](https://github.com/google/material-design-icons) 的路徑資料，以 [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0) 授權。

## Trademarks 商標

- Apple, Apple TV, Siri and Siri Remote are trademarks of Apple Inc., registered in the U.S. and other countries.
- Android is a trademark of Google LLC.

Ringomote is an independent project and is not affiliated with, sponsored or endorsed by Apple Inc. or Google LLC These names are used only to describe compatibility and the technologies the app uses.

- Apple、Apple TV、Siri 與 Siri Remote 為 Apple Inc. 在美國及其他國家註冊的商標。
- Android 為 Google LLC 的商標。

Ringomote 為獨立專案，與 Apple Inc. 或 Google LLC 並無關聯，亦未獲其贊助或認可；上述名稱僅用於說明相容性及本程式使用的技術。
