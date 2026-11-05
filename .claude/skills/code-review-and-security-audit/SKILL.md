---
name: code-review-and-security-audit
description: Review code for quality, correctness, and security vulnerabilities. Use when the user asks to review code, audit for security issues, or check for bugs and anti-patterns.
argument-hint: file path, component name, or scope of review
---

# Code Review and Security Audit

You are performing code review and security auditing for Ringomote, an Android app that makes the phone act as a 1st-generation Siri Remote for an Apple TV over the local Wi-Fi network (Companion Link).

## Scope

This skill covers two complementary concerns:

1. **Code Review** — correctness, readability, maintainability, and adherence to project conventions.
2. **Security Audit** — identifying vulnerabilities, unsafe patterns, and potential attack surfaces.

## Review Checklist

### Code Quality

- Null safety: proper use of Kotlin null-safe operators; avoid `!!` unless justified (network APIs return null for missing services and endpoints).
- Thread safety: `NsdManager` callbacks arrive on binder threads and socket I/O runs on `Dispatchers.IO`. Shared connection state must be safely published (e.g. `StateFlow`), and UI must not be touched from those threads.
- Main thread: no blocking calls or sleeps on the main thread; timing between press and release reports must not use `Thread.sleep` on it.
- Resource management: profile proxies are closed with `closeProfileProxy`, the GATT server is closed, advertising is stopped, and receivers are unregistered when no longer needed.
- Error handling: return values of `sendReport`, `notifyCharacteristicChanged`, `registerApp` and `startAdvertising` are checked; no silently swallowed exceptions (`SecurityException` from missing permissions in particular).
- Consistency: follows existing patterns in the codebase (Jetpack Compose + Material 3, coroutines/Flow for state).
- No dead code, unused imports, or redundant logic.

### Code Smells

- **Long methods**, **large classes**, **duplicated code** (e.g. report encoding repeated per button instead of one encoder), **deep nesting**.
- **Magic numbers**: Companion command codes, message keys, TLV types and crypto constants must be named constants with a comment pointing to the protocol notes or capture they came from.
- **Feature envy**, **inappropriate intimacy** (UI reaching into network internals).
- **Primitive obsession**: buttons and connection states as ints/strings instead of enums or sealed classes.
- **Long parameter lists**, **mutable shared state** (global `var` connection fields).

### Code Refactoring Suggestions

- **Extract method / class / interface**: keep the Companion link behind an interface (`CompanionLinkPort`) so the session and the UI can be tested without hardware.
- **Replace conditional with polymorphism** and **introduce sealed class/enum** for connection states and remote controls.
- **Use Kotlin idioms**: scope functions, `buildList`, `ByteArray` helpers, extension functions.
- **Simplify lifecycle management**: `lifecycleScope`, `repeatOnLifecycle`, `LifecycleObserver`; decide deliberately whether the connection lives in the activity, a ViewModel, or a foreground service.
- **Improve testability**: pure functions for message encoding (OPACK, TLV8, HAP) that unit tests in `app/src/test/` can pin down byte by byte.

### Companion Link Correctness

This app's main risk is the Apple TV not pairing, dropping the session, or receiving the wrong input. Check:

- **Pairing**: pair-setup (SRP-6a, HAP TLV states M1–M6) and pair-verify follow `docs/companion-link.md`; keys are stored per Apple TV and excluded from backups.
- **Session**: `_sessionStart`/`_sessionStop` bracket the remote session; every control maps to the right `_hidC` code; frames are encrypted with the session keys in order.
- **Press and release**: every button-down is followed by a button-up, including when the user's finger leaves the button, the app goes to the background, or the link drops mid-press. A stuck key on the Apple TV is a high-severity bug.
- **Connection lifecycle**: Wi-Fi toggled, Apple TV sleep/wake, app restart, a refused pairing (no retry storm), and the grace/back-off rules in `CompanionSession`.
- Prefer verifying bytes with a unit test against pyatv's notes, srptools vectors or a captured exchange over reasoning alone.

### Android-Specific Security

- **Permissions**: flag any newly added permission and question whether it is required (`INTERNET`, location, `SCHEDULE_EXACT_ALARM`, foreground service types).
- **Pairing**: the app must not auto-accept or silently bond with arbitrary hosts; pairing should only happen when the user has started it, and discoverable/advertising windows should be time-limited.
- **Input injection risk**: the app sends input to the TV, so any path that lets another app or a broadcast trigger reports (exported services, receivers, unvalidated intents) is a security issue.
- **Export controls**: verify that components (activities, services, receivers) are not inadvertently exported.
- **PendingIntent**: use `FLAG_IMMUTABLE` and explicit intents (e.g. foreground-service notification actions).
- **Privacy**: no logging of pairing keys, session keys or device names at release log levels; stored pairings are excluded from backups.
- **Backup**: check `backup_rules.xml` / `data_extraction_rules.xml` if any pairing data is stored.
- **R8**: ensure keep rules (`app/src/main/keepRules/`) don't strip classes referenced from the manifest or by reflection.

### General Security

- No hardcoded secrets, API keys, or credentials.
- No command injection via `Runtime.exec()` or `ProcessBuilder`.
- No path traversal vulnerabilities in file operations.
- No insecure random number generation for security-sensitive operations.
- Dependencies: check for known vulnerabilities in third-party libraries.

### Trademarks

- UI and resources must not ship Apple logos or Apple's remote artwork; product names are used only to describe compatibility.

## Output Format

Report findings using this structure:

### Critical / High

Issues that must be fixed — security vulnerabilities, crashes, stuck keys or wrong input sent to the TV, pairing that cannot complete.

### Medium

Issues that should be fixed — logic bugs, thread safety concerns, leaks, code smell.

### Low / Informational

Suggestions for improvement — style, readability, minor optimizations.

For each finding, include:
- **File and line number** (e.g. `HidPeripheral.kt:42`)
- **Description** of the issue
- **Impact** — what could go wrong
- **Recommendation** — how to fix it, with code if appropriate

## How to Run

When invoked without arguments, review recently changed files:
```bash
git diff --name-only HEAD~5
```
(Use a smaller range if the history is shorter.)

When invoked with a specific scope (file, directory, or component name), focus the review on that area.

For a full audit, systematically review:
1. Network layer (`network/companion/`: pairing, crypto, framing, session; `network/`: discovery)
2. UI layer (`MainActivity.kt`, remote screen, settings, `ui/theme/`)
3. Configuration (`AndroidManifest.xml`, keep rules, backup rules)
4. Tests (`app/src/test/`, `app/src/androidTest/`)
5. Dependencies (`gradle/libs.versions.toml`)

Use `find app/src -name '*.kt'` to locate the actual files; the names above describe roles, not fixed paths.

## Task: $ARGUMENTS
