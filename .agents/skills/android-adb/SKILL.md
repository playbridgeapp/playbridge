---
name: android-adb
description: Android device control via raw ADB commands. Use for device/emulator discovery, USB or Wi-Fi connection, app launch/force-stop, tap/swipe/keyevent/text input, screenshots, UI hierarchy dump, APK install/uninstall, and ADB troubleshooting on phone or TV hardware.
---

# Android ADB

Reference for controlling Android devices with raw `adb` commands.

Upstream source: [httprunner/skills `android-adb`](https://github.com/httprunner/skills/blob/main/android-adb/SKILL.md)
([skills.sh](https://www.skills.sh/httprunner/skills/android-adb)). Adapted for PlayBridge agent workflows.

## PlayBridge context

- Phone package: `com.playbridge.sender`
- TV package: `com.playbridge.player`
- Phone Gradle root: `mobile/android/`
- TV Gradle root: `tv/android/`
- For phone logcat tag recipes (detection, cast, proxy, downloads), also read
  `mobile/android/docs/logging.md` after this skill.
- Prefer debug APKs when verifying detection / proxy / network dumps; many of
  those paths are gated on `BuildConfig.DEBUG`.
- On macOS shells, quote logcat filters that contain `*:S` so zsh does not glob.

## Execution Constraints

- Use `adb -s <serial>` whenever more than one device is connected.
- Confirm screen resolution before coordinate actions: `adb -s SERIAL shell wm size`.
- Ask for missing required inputs before executing (serial, package/activity, coordinates, APK path).
- Surface actionable stderr on failure (authorization, cable/network, tcpip state).
- Prefer ADB Keyboard broadcast for CJK and special character input when ADB Keyboard is installed.

## Device And Server

```bash
# Start ADB server
adb start-server

# Stop ADB server
adb kill-server

# List devices
adb devices -l

# Target a specific device (use when multiple devices are online)
adb -s SERIAL <command>
```

## Wi-Fi Connection

```bash
# Enable tcpip (USB required first)
adb -s SERIAL tcpip 5555

# Get device IP
adb -s SERIAL shell ip route | grep src
# or
adb -s SERIAL shell ip addr show wlan0

# Connect over network
adb connect <ip>:5555

# Disconnect
adb disconnect <ip>:5555
```

## Device State

```bash
# Screen size
adb -s SERIAL shell wm size

# Current foreground app (package/activity from mCurrentFocus)
adb -s SERIAL shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'

# Get home launcher package (for back-home detection)
adb -s SERIAL shell cmd package resolve-activity --brief -c android.intent.category.HOME

# Raw shell command
adb -s SERIAL shell <command>
```

## App Lifecycle

```bash
# Verify package installed
adb -s SERIAL shell pm list packages | grep <package>

# Launch by package (via monkey)
adb -s SERIAL shell monkey -p <package> -c android.intent.category.LAUNCHER 1

# Launch by activity
adb -s SERIAL shell am start -W -n <package>/<activity>

# Launch by URI/scheme
adb -s SERIAL shell am start -W -a android.intent.action.VIEW -d "<scheme://path>"

# Force-stop
adb -s SERIAL shell am force-stop <package>
```

## Input Actions

```bash
# Tap
adb -s SERIAL shell input tap X Y

# Double tap (two taps with short delay)
adb -s SERIAL shell input tap X Y && sleep 0.1 && adb -s SERIAL shell input tap X Y

# Long press (swipe to same point with duration)
adb -s SERIAL shell input swipe X Y X Y 3000

# Swipe
adb -s SERIAL shell input swipe X1 Y1 X2 Y2 [duration_ms]

# Key event
adb -s SERIAL shell input keyevent KEYCODE_BACK
adb -s SERIAL shell input keyevent KEYCODE_HOME
adb -s SERIAL shell input keyevent KEYCODE_ENTER

# Return to home (press BACK repeatedly, check foreground against home launcher)
for i in $(seq 1 20); do
  adb -s SERIAL shell input keyevent KEYCODE_BACK
  sleep 0.5
  # Check if home reached by comparing current focus to home package
  CURRENT=$(adb -s SERIAL shell dumpsys window | grep mCurrentFocus)
  echo "Round $i: $CURRENT"
done
```

## Text Input

```bash
# Plain text input (ASCII only, spaces escaped as %s)
adb -s SERIAL shell input text "hello%sworld"

# Input via ADB Keyboard (supports CJK and special characters)
# First ensure ADB Keyboard is active:
adb -s SERIAL shell ime set com.android.adbkeyboard/.AdbIME
# Then send text (base64 encoded):
adb -s SERIAL shell am broadcast -a ADB_INPUT_B64 --es msg "$(echo -n 'your text' | base64)"

# Clear text via ADB Keyboard
adb -s SERIAL shell am broadcast -a ADB_CLEAR_TEXT
```

## Screenshot And UI Tree

```bash
# Screenshot to local file
adb -s SERIAL exec-out screencap -p > screenshot.png

# Screenshot to device then pull
adb -s SERIAL shell screencap -p /sdcard/screen.png
adb -s SERIAL pull /sdcard/screen.png ./screen.png

# Dump UI hierarchy XML
adb -s SERIAL shell uiautomator dump /sdcard/window_dump.xml
adb -s SERIAL pull /sdcard/window_dump.xml ./window_dump.xml
```

## App Install And Uninstall

```bash
# Install APK (replace existing)
adb -s SERIAL install -r /path/to/app.apk

# Uninstall
adb -s SERIAL uninstall <package>
```

## PlayBridge install shortcuts

```bash
# Phone FOSS debug (after assemble from mobile/android)
adb -s SERIAL install -r app/build/outputs/apk/foss/debug/app-foss-*-debug.apk

# TV FOSS debug (after assemble from tv/android)
adb -s SERIAL install -r player/app/build/outputs/apk/foss/debug/app-foss-*-debug.apk

# Launch phone / TV
adb -s SERIAL shell monkey -p com.playbridge.sender -c android.intent.category.LAUNCHER 1
adb -s SERIAL shell monkey -p com.playbridge.player -c android.intent.category.LAUNCHER 1
```

## Common Keycodes

| Keycode | Description |
|---------|-------------|
| `KEYCODE_BACK` | Back button |
| `KEYCODE_HOME` | Home button |
| `KEYCODE_ENTER` | Enter/confirm |
| `KEYCODE_DEL` | Delete/backspace |
| `KEYCODE_VOLUME_UP` | Volume up |
| `KEYCODE_VOLUME_DOWN` | Volume down |
| `KEYCODE_POWER` | Power button |
| `KEYCODE_TAB` | Tab key |
| `KEYCODE_ESCAPE` | Escape key |
