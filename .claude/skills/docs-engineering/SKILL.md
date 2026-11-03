---
name: docs-engineering
description: Writing/updating project documentation (README, PRIVACY-POLICY, NOTICES, changelogs), maintaining F-Droid metadata, and keeping KDoc on the primary classes, methods and tests. Use when the user asks to update docs, write changelogs, modify F-Droid store listings, or comment code for maintainability.
argument-hint: task description
---

# Document Engineering

You are performing documentation tasks for Ringomote, an Android app that lets a phone act as a 1st-generation Siri Remote for an Apple TV over Bluetooth.

## Bilingual Convention

This project maintains documentation in both **English (en-US)** and **台灣漢語 in Traditional Han script (zh-TW)**. When creating or updating user-facing text, always produce both language versions unless instructed otherwise.

- README.md uses interleaved bilingual sections (English heading/paragraph followed by the 漢文 equivalent).
- F-Droid metadata keeps separate locale directories.
- In-app strings live in `app/src/main/res/values/strings.xml` (English, the default) and `app/src/main/res/values-zh-rTW/strings.xml` (台灣漢語); update both together. Create `values-zh-rTW/` on first use.

## Project Documentation Files

| File | Purpose |
|------|---------|
| `README.md` | Project overview, pairing guide, build instructions (bilingual) |
| `PRIVACY-POLICY.md` | Privacy policy |
| `NOTICES.md` | Third-party license notices and trademark disclaimer |
| `CLAUDE.md` | Guidance for Claude Code |

These files may not exist yet. When asked to create one, follow the conventions here; do not create files the user didn't ask for.

When updating `NOTICES.md`, check `gradle/libs.versions.toml` and `app/build.gradle.kts` for current dependencies and their licenses.

## Trademarks and Affiliation

Apple, Apple TV, Siri and Siri Remote are trademarks of Apple Inc. Ringomote is an independent project.

- Any README, store listing or NOTICES text that names these products must say the app is not affiliated with or endorsed by Apple, and use the names only to describe compatibility ("works with Apple TV", "compatible with the Siri Remote (1st generation)").
- Do not use Apple logos, product imagery or Apple's remote artwork in store screenshots or graphics.
- Describe how the app works accurately: it presents itself to the Apple TV as a Bluetooth input device. Do not claim it is a genuine Siri Remote or that it supports features it cannot reproduce (e.g. Siri voice input, unless implemented).

## Privacy

Re-check `AndroidManifest.xml` and the code before stating any of this, and keep `PRIVACY-POLICY.md`, the README and the store listing consistent:

- Which Bluetooth permissions the app requests and why (`BLUETOOTH_CONNECT` / `BLUETOOTH_ADVERTISE` on Android 12+, `BLUETOOTH` / `BLUETOOTH_ADMIN` on older versions; location only if a scan path really needs it).
- That control happens directly between phone and Apple TV over Bluetooth; whether the app uses the network at all (`INTERNET` permission).
- What the app stores locally (e.g. the paired Apple TV's address or name) and whether it is included in backups (`backup_rules.xml`, `data_extraction_rules.xml`).
- That nothing is collected or sent anywhere, if that is still true.

## F-Droid Metadata

Metadata lives under `fastlane/metadata/android/` with two locales (create it on first use):

```
fastlane/metadata/android/
  en-US/
    title.txt              # App name (max 50 chars)
    short_description.txt  # Store tagline (max 80 chars)
    full_description.txt   # Full store listing (max 4000 chars)
    changelogs/<versionCode>.txt  # Per-version changelog
    images/                # Screenshots and graphics
  zh-TW/
    (same structure)
```

### Changelogs

- Changelog filenames use **versionCode** (integer), not versionName. Find the current versionCode in `app/build.gradle.kts` (`versionCode = ...`).
- Write changelogs in a friendly, first-person tone consistent with existing entries (see `fastlane/metadata/android/en-US/changelogs/` for examples, once there are some).
- Always create both `en-US` and `zh-TW` versions.
- Max 500 characters per changelog file.

### Store Descriptions

- `full_description.txt` is written in first person from the app's perspective ("Hello, it's me, Ringomote.").
- Use `*` for bullet points in en-US, `＊` (fullwidth asterisk) in zh-TW.
- Include the trademark/affiliation disclaimer (see above) and the requirements: Android 9 or later with Bluetooth, and an Apple TV that accepts the Siri Remote (1st gen) / Bluetooth input devices.

## Writing Style

- Keep language clear and approachable.
- For zh-TW text, use Traditional Han characters only; never Simplified characters.
- Address the reader politely as 「您」, never 「你」, in all 台灣漢語 text (store listings, changelogs, release notes, UI strings).
- Refer to the written language as 「漢文」, never 「中文」, out of respect for the many ethnic groups in China and across East Asia who also use it; the Taiwanese UI language is 「台灣漢語」. In English, write "Han script" or "Traditional Han characters" rather than "Chinese".
- Preserve the existing tone: the app "speaks" in first person in store listings and changelogs; README and other docs use standard third-person technical writing.
- Use consistent terms: 遙控器 (remote), 藍牙 (Bluetooth), 配對 (pair), 觸控板 / 觸控表面 (touch surface). Keep Apple's product names in their original form (Apple TV, Siri Remote).
- **Respect external resource providers' efforts.** When describing problems in upstream projects, dependencies, Android's Bluetooth stack, tvOS, or device vendors, use softened wording. Prefer "issue", "behavior", or "limitation" over "bug"; prefer "resolved" or "addressed" over "fixed the bug". Frame workarounds as collaborative rather than corrective. This applies to both en-US and zh-TW text (e.g., use 「問題」 rather than 「錯誤」 or 「bug」 when referring to upstream).

## Code Comments (KDoc)

Human maintainers must be able to understand the design from the source alone, so the critical and primary classes and methods carry KDoc, and so do the test classes.

- **What gets a comment.** Every public or primary class and object (the Bluetooth HID service/peripheral, the HID report descriptor and report encoders, the connection/pairing state holder, any foreground service, `MainActivity`, the main screens), every public entry point (e.g. connect, disconnect, send a button press, send a touch/swipe), state enums and sealed classes, and private functions whose purpose or rule is not obvious from their body. Trivial getters and self-explanatory one-liners need none.
- **What a comment says.** The role of the class in the app and the contract of the method: inputs, what happens when not connected or when permission is missing, which thread or callback it runs on (Bluetooth callbacks arrive on binder threads), and the protocol detail behind it — HID usage page and usage ID for each control, report ID and byte layout, GATT service/characteristic UUIDs, why a press is followed by a release report, and why an alternative was rejected. Do not restate the code line by line.
- **Report descriptors.** Annotate the descriptor byte array item by item (`// Usage Page (Consumer)`, `// Report ID (1)`, …) so it can be checked against the HID spec without a decoder.
- **Tests.** Each test class states what it covers and where its expected values come from (HID Usage Tables, a captured report from a real Siri Remote, a hand-encoded byte layout). Each test method gets a one-line note on the property it pins down, so a failure can be read without reverse-engineering the assertions.
- **When to update.** Adding a class, public method or test, or changing a contract, includes writing or revising its KDoc in the same change. Check new comments for the naming rules above (漢文 not 中文, Han script not Chinese) before finishing.
- **Form.** KDoc (`/** … */`) on declarations; `//` line comments inside bodies for a specific step. Write the prose in English. Use `[Name]` links for symbols in scope.

## Task: $ARGUMENTS
