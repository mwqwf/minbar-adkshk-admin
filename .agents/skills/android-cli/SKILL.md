---
name: android-cli
description: Provides instructions for installing and using the `android` CLI. The
  `android` command-line tool is a critical tool for Android development and helps
  you create new Android projects, install APKs onto devices and emulators and launch
  and run apps on them, manage and interact with Android virtual devices (including
  screenshots and UI inspection), manage Android SDK components, inspect built artifacts
  (APK, AAB, JAR, AAR) for build and optimization details, render Compose previews,
  look up official Android documentation, and discover and install official Android
  skills.
license: Complete terms in LICENSE.txt
metadata:
  author: Google LLC
  last-updated: '2026-11-14'
  keywords:
  - android-cli
  - sdk
  - emulator
  - skills
  - docs
  - knowledge base
  - project creation
  - screenshots
  - apk analysis
  - compose preview
---

This skill provides instructions for using the `android` CLI tool. The tool
includes various commands for creating projects, running applications,
interacting with devices, and managing the CLI environment.

## Installation

If the android tool is not in the path, install it. To install run the following
command:

- Linux x86-64: curl -fsSL https://dl.google.com/android/cli/latest/linux_x86_64/install.sh \| bash
- Linux ARM64: curl -fsSL https://dl.google.com/android/cli/latest/linux_arm64/install.sh \| bash
- Mac Arm: curl -fsSL https://dl.google.com/android/cli/latest/darwin_arm64/install.sh \| bash
- Mac Intel: curl -fsSL https://dl.google.com/android/cli/latest/darwin_x86_64/install.sh \| bash
- Windows: curl -fsSL https://dl.google.com/android/cli/latest/windows_x86_64/install.cmd -o "%TEMP%\\i.cmd" \&\& "%TEMP%\\i.cmd"

## SDK management

To manage the installation of Android SDKs and tools, use the `sdk` command.
For example:

- `android sdk install <package>[@<version>]...`: Install specific packages. Multiple packages can be specified, separated by spaces. `<version>` defaults to latest. For example: `android sdk install platforms/android-30@2
  platforms/android-34`
- `android sdk update [<pkg-name>]`: Update a specific package or all packages to the latest version.
- `android sdk remove <pkg-name>`: Remove a package from the local SDK.
- `android sdk list --all`: List installed and available SDK packages.

## Project creation

Create projects from templates using the `create` command.

For example: `android create empty-activity --name="My App" --output=./my-app`

## Interacting with Android devices

Use the `android layout` command to inspect the UI layout of an Android
application in JSON format. Use the `android screen` command to visually inspect
the UI and obtain bounding coordinates for visual regions.
`android layout` dumps the UI as JSON. `android screen capture -o <file path>` saves a PNG.
Both auto-select the device when only one is connected; with more than one, pass `--device=<serial>` (use `adb devices` to list serials).

### UI Dump

`android layout` returns a JSON tree of the UI elements on screen.

Each JSON object represents a UI element in the Android app. The following properties may be present:
- `text` - any literal text the element contains
- `resource-id` - the Android resource id used to refer to the element
- `content-desc` - a description of a UI element for use by accessibility tools
- `interactions` - the set of user interactions the element supports. May contain one or more of: `CHECKABLE`, `CLICKABLE`, `FOCUSABLE`, `SCROLLABLE`, `LONG-CLICKABLE`, `PASSWORD`
- `state` - the set of states the element is in. May contain one or more of `CHECKED`, `FOCUSED`, `SELECTED`
- `bounds` - the screen coordinates of the bounding rectangle of the element, in the format `[min X,min Y][max X, max Y]`
- `center` - the screen coordinates of the center of the element, in the format `[x,y]`
- `off-screen` - if true, the element is in the UI hierarchy but not within the bounds of the screen; it may require scrolling to view.
- `hidden` - if true, the element is in the UI hierarchy but not visible or interactive on screen.

Use `layout` as a primary means of examining an Android app.

By default, `layout` only returns elements that have content or are interactive:
- elements with non-empty `text` or `content-desc` properties
- elements with non-empty `interactions` or `state`
To return all elements, use the `--full` flag

If `layout` continues to fail, use `android screen capture --annotate` to inspect the app.

### Screenshot

`android screen capture -o <file path>` (or `--output=<file path>`) saves a PNG of the current device screen to `<file path>`.

Use `screen capture` as a secondary means of examining an Android app
Examples:
- Understanding the content of an on-screen image
- Looking at a `WebView` (web content does not always appear in the ui dump)
- Trying to find a UI element by its visual appearance

**IMPORTANT** : Always *VISUALLY* examine the PNG image returned from any `android screen` command BEFORE doing anything else.

#### Annotated Screenshot

`android screen capture --annotate -o <file path>` (or `-a -o <file path>`)
`android screen resolve --screenshot <path> --string <string>`

The `--annotate` command adds numerical labels and bounding boxes around UI elements. Use this command to locate UI elements that cannot
be located in the `layout` output.

To refer to these labels in input commands, use `screen resolve` to convert labels into coordinates:

`android screen resolve --screenshot <file path> --string "#3"` returns `<x coord of region 3> <y coord of region 3>`

To save turns, you can combine shell commands:

`adb shell input $(android screen resolve --screenshot screen.png --string "tap #34")`

This command taps on region #34 from `screen.png`

### Input

Use `adb shell input` for interacting with Android devices.
Refer to the `"interactions"` property of an element for what interactions can be performed on a particular element.

Interact with UI elements with their `center` coordinate or their `bounds` coordinates:
`json
{
"key": -248568265,
"class": "android.widget.Button",
"bounds": "[138,9][167,38]",
"center": "[152,23]"
}`
To tap on this button, you would execute `adb shell input tap 152 23`. This taps the center.

```json
{
  "key": 12487234,
  "class": "com.example.ui.ScrollableList",
  "bounds": "[100,200][400,600]",
  "center": "[250,400]"
}
```

To scroll down on this list, you would execute `adb shell input swipe 250 400 250 200 500`. This swipes from the center to the top over 500ms.

#### Text Input

To enter text, ensure the field is focused and execute `adb shell input text "<text>"`.

- **Spaces (`%s`):** Replace spaces with `%s` (Android ignores text after raw spaces): `adb shell input text "Hello%sworld"` (enters `"Hello world"`).
- **Special characters:** Escape shell metacharacters (`&`, `$`, `(`, `)`, `!`): `adb shell input text "AT\&T"` or `adb shell input text 'Price:\$10'`.
- **Submit / Enter:** `adb shell input keyevent 66`

### Android Interaction Rules

1. Always ensure text input fields have `"FOCUSED"` in their `"state"` list before entering text.
2. If an element has `"SCROLLABLE"` in its `"interactions"` list, try scrolling it when looking for missing UI elements.
3. Always scroll slowly when executing scroll inputs. The 5th argument to `adb shell input swipe` controls scroll duration.
4. Content may take time to load; if a `layout` is missing information after you take an action, wait a few seconds, then perform `layout` again to see if anything changes.

## Running journey tests

Journey tests involve device interaction; use the `android layout` and `android
screen` commands to evaluate Journeys. The instructions for device interaction
apply here as well.

A journey is an XML-specified test of an Android app's behavior. It consists of a list of `<action>` elements. For example:
`xml
<journey name="My Journey">
<description>
A sample journey to illustrate the format
</description>
<actions>
<action>

---
> **الدليل الكامل** (بقية الأقسام) في `references/FULL_GUIDE.md` — قُسّم لأن الأصل تجاوز 500 سطر (كشف تدريجي).

## فهرس الدليل الكامل
- Installation
- SDK management
- Project creation
- Interacting with Android devices
- UI Dump
- Screenshot
- Input
- Android Interaction Rules
- Running journey tests
- Taking Actions
- Verifying Expectations
- Handling failure
- Summarizing
- Journey: <Name of the journey>
- Results
- Action: Click the blue button ✅
- Action: Check if "Switch 2" is visible on the screen ✅
- Action: The home screen is shown ❌
- Action: Swipe down to view active notifications
- Doc searching
- Running APKs
- Managing emulators
- Managing skills
- Updating the CLI
- `android help` output
