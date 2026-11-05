---
name: release-engineering
description: Release engineering tasks including version bumping, building release APKs, creating git tags, writing changelogs, and preparing F-Droid releases. Use when the user asks to prepare a release, bump version, tag a release, or build for distribution.
argument-hint: task description
---

# Release Engineering

You are performing release engineering tasks for Ringomote.

## Version Scheme

- **versionName**: semver-style `MAJOR.MINOR.PATCH` (e.g. `1.0.1`). The initial value is `1.0`; move to three parts from the first release on.
- **versionCode**: monotonically increasing integer (e.g. `2`)
- Both are defined in `defaultConfig` of `app/build.gradle.kts`

There is no version-bump Gradle task in this project. Edit `versionCode` (+1) and `versionName` in `app/build.gradle.kts` by hand.

## Release Process

1. **Ensure all changes are committed** on the working branch.
2. **Bump version** — edit `versionCode` and `versionName` in `app/build.gradle.kts`.
3. **Write changelogs** for F-Droid (see Changelog section below).
4. **Run the tests** — `./gradlew :app:test`, so the tag lands on a known good state.
5. **Check on real hardware** — the emulator is not on the TV's network. Ask the user to confirm the release candidate pairs with an Apple TV and that every remote button works (see Hardware Check below) before tagging.
6. **Create release commit** — commit message format: `Release <versionName>` with `Signed-off-by` trailer.
7. **Tag the release** — lightweight tag matching versionName (e.g. `1.0.1`). Tags are NOT annotated.
8. **Build signed APKs** — only now, after the commit and tag: F-Droid builds reproducibly from the tagged commit, so the published APKs have to be built from exactly that commit. Signing requires a keystore passphrase, so **do not run the build automatically**. Prompt the user to build the signed APKs manually in Android Studio (Build > Generate Signed APK) or via the command line. Output goes to `app/build/outputs/apk/release/` (debug builds to `app/build/outputs/apk/debug/`). Android Studio may also leave copies in `app/release/` and `app/debug/`, but the user signs the ones under `app/build/outputs/apk/`. Wait for the user to confirm the build is complete before proceeding.

   **Clean first, then verify the signed build matches F-Droid's.** Kotlin incremental compilation can emit slightly different line-number tables when it recompiles one file alone; that changes R8's mapping file and the `r8-map-id` string embedded in `classes.dex` while the code stays the same, and F-Droid's reproducible-build check then fails with "CHUNKED_SHA256 digest mismatch" and `classes.dex`/`baseline.prof` differing. So before the user signs, make a clean reference build and note its dex hash:
   ```bash
   ./gradlew clean :app:assembleRelease
   unzip -p app/build/outputs/apk/release/<name>-release-unsigned.apk classes.dex | sha256sum
   ```
   The signed build the user then runs reuses those compiled classes, so its dex must hash the same. The APK also embeds the git revision it was built from in `META-INF/version-control-info.textproto`, and F-Droid diffs that file too, so HEAD must be the tagged commit while the user builds: commit nothing else between the release commit and the signed build (or `git checkout --detach <tag>` first and return to `main` afterwards), and check the embedded revision with `unzip -p <signed apk> META-INF/version-control-info.textproto`. After the user confirms, check `unzip -p <signed apk> classes.dex | sha256sum` against the noted hash; if it differs, clean and sign again.
9. **GPG-sign** the APKs (see GPG Signing below), then **push** the commit and tag and create the GitHub Release when the user confirms.

### Git Conventions

- Release commit message: `Release X.Y.Z`
- Tag name: `X.Y.Z` (just the version, no `v` prefix)
- Tags are lightweight (not annotated): `git tag <versionName>`
- Commits are signed off: `--signoff`

## Hardware Check

Before tagging, the user should confirm on a physical phone and Apple TV:

- Fresh pairing from Settings (Bonjour discovery, PIN) works, and reconnection after the app is restarted, the phone's Wi-Fi is toggled, and the Apple TV wakes from sleep (Wake button).
- Every Siri Remote (1st gen) control the app offers does what the real remote does: touch surface (swipe and click), Menu, TV/Home, Play/Pause, Volume up/down.

Record any control the app cannot reproduce (e.g. Siri voice input) in the changelog rather than hiding it.

## Changelogs (F-Droid)

Changelogs live at:
```
fastlane/metadata/android/en-US/changelogs/<versionCode>.txt
fastlane/metadata/android/zh-TW/changelogs/<versionCode>.txt
```

If `fastlane/metadata/android/` does not exist yet, set it up with `/docs-engineering` first (title, descriptions, both locales).

- Filename is the **versionCode** (integer), not the versionName.
- Max 500 characters per file.
- Written in a friendly, first-person tone from the app's perspective.
- Always create both en-US and zh-TW versions.
- Use `*` for bullet points in en-US, `＊` (fullwidth asterisk) in zh-TW.
- To determine what changed, review commits since the previous release tag:
  ```bash
  git log --oneline <previous-tag>..HEAD
  ```

## Build Commands Reference

```bash
./gradlew :app:assembleDebug        # Debug build
./gradlew :app:assembleRelease      # Release build (R8 optimized)
./gradlew :app:test                 # Unit tests
./gradlew :app:lintDebug            # Lint
./gradlew :app:connectedAndroidTest # Instrumented tests
./gradlew :app:clean                # Clean
```

## GPG Signing

GPG signing requires interactive passphrase entry — **do not run `gpg` commands automatically**. Instead, prompt the user to sign the APKs manually.

APK names depend on `base.archivesName` in `app/build.gradle.kts`. If it is not set, the outputs are `app-release.apk` / `app-debug.apk`; suggest setting it to `ringomote_v<versionName>` (once, in its own commit, before a release) so assets carry versioned names. Always list `app/build/outputs/apk/{debug,release}/` to confirm the actual names before giving commands.

Provide the user with the exact commands to run, e.g.:
```bash
gpg --detach-sign --armor app/build/outputs/apk/release/ringomote_v1.0.1-release.apk
gpg --detach-sign --armor app/build/outputs/apk/debug/ringomote_v1.0.1-debug.apk
```

Wait for the user to confirm signing is complete before proceeding to the GitHub Release step.

Verifying the signatures does not need a passphrase, so do it before uploading. gpg prints in the user's locale, so force English output and look for `GOODSIG`:
```bash
LC_ALL=C gpg --status-fd 1 --verify <apk-file>.asc <apk-file> | grep GOODSIG
```

## GitHub Release

After building and GPG-signing the APKs, create a GitHub Release using `gh`. Find the repository from `git remote get-url origin`; if no remote is configured, ask the user before creating anything.

### Release Notes

Write a human-friendly release note body — do **not** rely solely on `--generate-notes`. The note should:

- Start with both the **en-US** and **zh-TW** F-Droid changelogs (already written earlier in the process), separated by a blank line.
- Append the auto-generated changelog (commit list) below as a "Full Changelog" section for completeness.

Structure:
```markdown
<en-US changelog content>

<zh-TW changelog content>

<!-- auto-generated below -->
**Full Changelog**: https://github.com/<owner>/<repo>/compare/<previous-tag>...<versionName>
```

For the first release there is no previous tag; link to the commit list for the tag instead (`https://github.com/<owner>/<repo>/commits/<versionName>`).

### Creating the Release

```bash
gh release create <versionName> \
  --title "<versionName>" \
  --notes "$(cat <<'EOF'
<release notes body as described above>
EOF
)" \
  <apk-and-asc-files...>
```

Upload **both** debug and release APKs along with their `.asc` signature files (4 assets total), all from `app/build/outputs/apk/{debug,release}/`.

The release title matches the tag/versionName (e.g. `1.0.1`).

## Important Reminders

- Always confirm with the user before pushing commits/tags or creating GitHub releases.
- Run tests before tagging a release if the user hasn't already.
- The release APK requires a signing configuration — the user handles this outside of version control.
- F-Droid builds from source using the tagged commit, so the tag must point to a buildable state.

## Task: $ARGUMENTS
