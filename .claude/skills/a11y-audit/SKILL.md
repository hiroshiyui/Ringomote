---
name: a11y-audit
description: Audit and improve accessibility (a11y) of the app — TalkBack semantics, touch targets, contrast, font scaling, haptics, and keyboard focus on the remote screen. Use when the user asks to audit, review, fix or test accessibility, screen-reader support, contrast, or large-font behaviour.
argument-hint: file path, screen or component name, or "full" for the whole app
---

# Accessibility Audit

You are auditing and improving the accessibility of Ringomote: a Jetpack Compose app
(Material 3) that turns the phone into a 1st-generation Siri Remote for an Apple TV.
Findings must be concrete (file and line, what a TalkBack user hears or a large-font user
sees, and the fix), and fixes must keep both locales (`values/strings.xml`,
`values-zh-rTW/strings.xml`) in sync.

## Principles for this app

- **The touch surface is the hardest surface.** The Siri Remote's touch pad is driven by
  swipes and clicks, which TalkBack intercepts (explore-by-touch turns a swipe into focus
  movement). Every action on it must also be reachable another way: visible or
  accessibility-only up/down/left/right and select buttons, and/or
  `Modifier.semantics { customActions = … }` on the surface ("Swipe up", "Select", …).
  Never make a direction or select reachable only by a gesture.
- **Every remote button has a name that says what it does on the TV**, not what it looks
  like: "Menu" / "Back", "Home", "Play or pause", "Volume up", "Volume down". Icon-only
  buttons need a localised `contentDescription`; decorative icons next to a text label use
  `null`.
- **Press-and-hold must have an alternative.** Some Apple TV actions need a long press
  (e.g. holding Home, holding the touch surface to rearrange). Provide a long-click
  semantics action (`onLongClick` label) and do not rely on precise timing.
- **Connection state is announced.** Pairing / connecting / connected / disconnected and
  missing-permission states are visible as text and announced with
  `liveRegion = LiveRegionMode.Polite`; when disconnected, remote buttons are disabled or
  explain why, not silently ignored.
- **Feedback is not only visual.** Haptic feedback on press helps users who are not looking
  at the phone; make sure it respects the system haptic setting and is not the only signal.
- **Font scaling is a layout feature, not a bug.** The app must survive 2.0× without
  clipped or overlapping labels. Never use `dp` for text, and never give a text container a
  fixed `dp` height.
- **Language and tone.** Follow the project conventions in every new string: 漢文 not 中文,
  台灣漢語 for the UI language, 您 never 你.

## Checklist

### Screen reader (TalkBack)

- Every interactive element has a name: remote buttons, volume controls, the touch surface,
  device picker entries, permission and pairing buttons.
- Decorative images use `contentDescription = null`; informative ones describe content.
- Grouping: a device row (name + status) merges descendants; the remote buttons stay
  separate items.
- State: connected device, enabled/disabled buttons, selected device in a picker are
  announced.
- Reading order follows visual order (top to bottom around the remote layout); nothing
  important is unreachable by swipe navigation.
- Dialogs (permission rationale, device picker) move focus in and return it on dismiss.

### Touch and input

- Touch targets at least 48 × 48 dp (Material components default to it; custom `clickable`
  boxes and the remote-shaped buttons may not).
- The touch surface's custom `pointerInput` handling must be paired with semantics actions,
  since `pointerInput` alone is invisible to accessibility services.
- Keyboard and D-pad: focus is visible and the tab order is sensible (relevant for ChromeOS
  and external keyboards); arrow keys and Enter could map to remote directions and select.
- No gesture-only actions; everything is reachable with a tap.

### Visual

- Contrast: button labels and icons against the remote background meet WCAG AA (4.5:1 body,
  3:1 large text and graphical objects) in both light and dark themes and with dynamic
  colour.
- Do not convey meaning by colour alone (e.g. connection status needs text or an icon, not
  just a green/red dot).
- Font scale: run the app at 1.3× and 2.0× (`adb shell settings put system font_scale 2.0`,
  restore to `1.0` afterwards); check the remote layout, status text and device list.
- Display size: `adb shell wm density` changes should not break the remote layout or push
  buttons off screen; landscape and small screens keep all controls reachable.
- Reduce motion: press animations respect the system "remove animations" setting.
- Dark theme: both themes are checked.

### Content and language

- Strings are complete sentences where read aloud; abbreviations only when standard.
- Layouts use start/end rather than left/right where start/end exists (but the remote's
  left/right *directions* are physical and must not flip in RTL).

## How to run

When invoked without arguments, audit the files changed recently:
```bash
git diff --name-only HEAD~5
```
When given a file, screen or component, focus there. For `full`, go through:

1. `MainActivity.kt` and the navigation between screens
2. The remote screen (buttons, touch surface, volume)
3. Connection, pairing and permission screens or dialogs
4. `ui/theme/` (colours, typography)
5. `res/values/strings.xml` and `res/values-zh-rTW/strings.xml`

Use `find app/src/main -name '*.kt'` to locate the actual files.

### Device checks (when a device is attached)

```bash
adb shell settings put system font_scale 2.0      # then restore: 1.0
adb shell settings put secure enabled_accessibility_services com.google.android.marvin.talkback/com.google.android.marvin.talkback.TalkBackService
adb shell settings put secure accessibility_enabled 1
adb shell settings put secure enabled_accessibility_services ''   # restore
adb shell settings put secure accessibility_enabled 0             # restore
adb exec-out uiautomator dump /dev/tty   # inspect content-desc, clickable, bounds (48dp targets)
```
Use `uiautomator dump` to confirm `content-desc` on each control and that bounds meet
48 dp at the device density. Always restore any system setting you changed. If the phone is
paired with an Apple TV, confirm that each TalkBack-activated control still sends the right
input.

### Automated checks

- Lint: `./gradlew :app:lintDebug` and read the `Accessibility` category
  (`ContentDescription`, `TouchTargetSizeCheck`, `ClickableViewAccessibility`,
  `TextContrastCheck`).
- Compose tests may assert semantics with `onNodeWithContentDescription` and custom actions
  with `SemanticsActions.CustomActions`; put them under `app/src/androidTest/`.

## Output format

### Must fix
Barriers: controls with no name, gesture-only actions, unreachable content, clipped text at
large font, contrast that makes labels unreadable.

### Should fix
Degraded experience: missing state announcements, small targets, verbose or unlocalised
descriptions, missing haptic or long-press alternatives.

### Nice to have
Polish: live regions, grouping, reading-order tweaks.

For each finding give **file:line**, **what the user experiences**, and **the fix** (with
code when short). When asked to fix rather than audit, apply the fixes, keep both string
files in sync, build with `./gradlew :app:assembleDebug`, and verify on a device if one is
attached.

## Task: $ARGUMENTS
