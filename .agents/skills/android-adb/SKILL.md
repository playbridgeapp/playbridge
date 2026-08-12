---
name: android-adb
description: Android device control via raw ADB for PlayBridge phone/TV work. Use when discovering devices on the LAN, connecting over Wi-Fi ADB, force-stopping apps, rebuilding and installing FOSS debug APKs with ABI splits, launching apps, screenshots, UI dumps, input, logcat, or ADB troubleshooting.
---

# Android ADB

Reference for controlling Android devices with raw `adb` commands during PlayBridge Android work.

Upstream source: [httprunner/skills `android-adb`](https://github.com/httprunner/skills/blob/main/android-adb/SKILL.md)
([skills.sh](https://www.skills.sh/httprunner/skills/android-adb)). Extended with PlayBridge LAN discovery, Smart TV preference, ABI-split installs, and stop → rebuild → install → launch flow.

## PlayBridge context

| Item | Value |
|---|---|
| Phone package | `com.playbridge.sender` |
| TV package | `com.playbridge.player` |
| Phone Gradle root | `mobile/android/` |
| TV Gradle root | `tv/android/` |
| Phone FOSS debug APKs | `mobile/android/app/build/outputs/apk/foss/debug/app-foss-*-debug.apk` |
| TV FOSS debug APKs | `tv/android/player/app/build/outputs/apk/foss/debug/app-foss-*-debug.apk` |
| Default ADB port | `5555` |

- Prefer **debug / FOSS debug** APKs for on-device verification. Many detection, proxy, and network dumps are gated on `BuildConfig.DEBUG`.
- On macOS, quote logcat filters that contain `*:S` so zsh does not glob.
- For phone logcat tag recipes, also read `mobile/android/docs/logging.md`.
- Always run Gradle from the project that owns the change, via:
  `zsh -c "source ~/.zshrc && ./gradlew …"`.

## Default workflow for Android work

When the user is working on Android phone or TV and on-device verification is useful (or they ask to install/run), do this **before** assuming a serial:

1. **Discover** devices already known to ADB and on the LAN (see [LAN discovery](#lan-discovery)).
2. **Classify** each hit (phone vs TV / Smart TV, authorized vs unauthorized, PlayBridge package present).
3. **Prefer**:
   - TV / `tv/android` work → **Smart TV** targets first (`SmartTV`, Hisense/BRAVIA/Android TV leanback, `com.playbridge.player` installed, `_androidtvremote2` / Cast TV names).
   - Phone / `mobile/android` work → phone / non-TV targets first (`com.playbridge.sender`, handheld product).
4. **List** candidates to the user (IP/serial, name, model, ADB state, package presence).
5. **Ask** which device to use when more than one is usable, or when the preferred class is missing / unauthorized.
6. **Connect** with `adb connect <ip>:5555` and keep using `adb -s SERIAL` for every later command.
7. For install/run cycles, use [Stop → rebuild → install → launch](#stop--rebuild--install--launch).

Do **not** silently pick a random device when several are online. Do **not** treat `unauthorized` as connected.

## Execution constraints

- Use `adb -s <serial>` whenever more than one device is listed (including leftover unauthorized entries).
- Confirm screen resolution before coordinate taps: `adb -s SERIAL shell wm size`.
- Ask for missing required inputs (serial when ambiguous, coordinates, APK path outside the defaults).
- Surface actionable stderr (authorization dialog, offline, `INSTALL_FAILED_NO_MATCHING_ABIS`, tcpip/Wi-Fi issues).
- Prefer ADB Keyboard broadcast for CJK / special characters when that IME is installed.

## LAN discovery

### 1. Known ADB devices

```bash
adb start-server
adb devices -l
```

### 2. Find local IPv4 subnet

```bash
ipconfig getifaddr en0   # common Wi-Fi on macOS
# fallback: other en* / ifconfig inet lines excluding 127.0.0.1 and VPN tunnels if needed
```

### 3. Scan the /24 for TCP 5555

```bash
python3 - <<'PY'
import concurrent.futures, socket, time
subnet = "192.168.1."  # replace with local /24 prefix
port, timeout = 5555, 0.35

def probe(ip: str):
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.settimeout(timeout)
    try:
        start = time.time()
        s.connect((ip, port))
        return ip, (time.time() - start) * 1000
    except Exception:
        return ip, None
    finally:
        s.close()

open_hosts = []
with concurrent.futures.ThreadPoolExecutor(max_workers=64) as ex:
    for ip, ms in ex.map(probe, [f"{subnet}{i}" for i in range(1, 255)]):
        if ms is not None:
            open_hosts.append((ip, ms))
for ip, ms in sorted(open_hosts, key=lambda t: tuple(map(int, t[0].split(".")))):
    print(f"{ip}:{port} open rtt_ms={ms:.0f}")
PY
```

### 4. Optional mDNS labels (names only; still verify via ADB)

```bash
# brief browse; kill after a few seconds
dns-sd -B _androidtvremote2._tcp local.
dns-sd -B _adb._tcp local.
dns-sd -B _googlecast._tcp local.
```

Useful instance-name hints: `SmartTV…`, `BRAVIA…`, `Master bedroom TV`, `GoogleTV…`.

### 5. Probe each open host

```bash
adb connect IP:5555
adb devices -l
```

For each `device` (authorized) serial:

```bash
SERIAL=IP:5555
adb -s "$SERIAL" shell getprop ro.product.manufacturer
adb -s "$SERIAL" shell getprop ro.product.model
adb -s "$SERIAL" shell getprop ro.product.cpu.abi
adb -s "$SERIAL" shell getprop ro.product.cpu.abilist
adb -s "$SERIAL" shell settings get global device_name
adb -s "$SERIAL" shell pm path com.playbridge.player
adb -s "$SERIAL" shell pm path com.playbridge.sender
adb -s "$SERIAL" shell pm list packages | grep -E 'leanback|android.tv' | head
```

Present a table to the user, for example:

| Serial | Name | Model | Kind | ADB | PlayBridge |
|---|---|---|---|---|---|
| `192.168.1.34:5555` | Master bedroom TV | Hisense SmartTV 4K FFM | Smart TV | device | `player` |
| `192.168.1.32:5555` | ? | ? | ? | unauthorized | — |

Then ask which serial to use (default recommendation: Smart TV for TV work).

## Wi-Fi / USB connection

```bash
# USB first-time wireless enable
adb -s USB_SERIAL tcpip 5555
adb -s USB_SERIAL shell ip route | grep src

adb connect IP:5555
adb disconnect IP:5555
```

`unauthorized` → user must accept the RSA prompt on the TV/phone. Re-run `adb connect` / `adb devices -l` after they accept.

## Device state

```bash
adb -s SERIAL shell wm size
adb -s SERIAL shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb -s SERIAL shell dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'
adb -s SERIAL shell pidof com.playbridge.player   # or com.playbridge.sender
adb -s SERIAL shell cmd package resolve-activity --brief -c android.intent.category.HOME
```

## App lifecycle

```bash
adb -s SERIAL shell pm list packages | grep playbridge
adb -s SERIAL shell am force-stop com.playbridge.player
adb -s SERIAL shell am force-stop com.playbridge.sender
adb -s SERIAL shell monkey -p com.playbridge.player -c android.intent.category.LAUNCHER 1
adb -s SERIAL shell monkey -p com.playbridge.sender -c android.intent.category.LAUNCHER 1
adb -s SERIAL shell am start -W -n com.playbridge.player/.MainActivity
```

## Stop → rebuild → install → launch

Standard on-device loop after code changes (TV example; swap roots/packages for phone).

### 1. Force-stop

```bash
SERIAL=192.168.1.34:5555   # chosen target
adb -s "$SERIAL" shell am force-stop com.playbridge.player
adb -s "$SERIAL" shell pidof com.playbridge.player || echo stopped
```

### 2. Rebuild FOSS debug from the owning root

```bash
# TV
cd tv/android
zsh -c "source ~/.zshrc && ./gradlew :player:app:assembleFossDebug"

# Phone
cd mobile/android
zsh -c "source ~/.zshrc && ./gradlew :app:assembleFossDebug"
```

Outputs (ABI splits + universal):

- TV: `player/app/build/outputs/apk/foss/debug/app-foss-{arm64-v8a,armeabi-v7a,universal}-debug.apk`
- Phone: `app/build/outputs/apk/foss/debug/app-foss-{arm64-v8a,armeabi-v7a,universal}-debug.apk`

### 3. Pick APK by device ABI

```bash
ABI=$(adb -s "$SERIAL" shell getprop ro.product.cpu.abi | tr -d '\r')
# Examples seen in the field:
#   arm64-v8a  → app-foss-arm64-v8a-debug.apk
#   armeabi-v7a → app-foss-armeabi-v7a-debug.apk   # some Hisense Smart TVs are 32-bit only
```

Match split APK to ABI. If install fails with `INSTALL_FAILED_NO_MATCHING_ABIS`, install the other ABI split or the universal APK — **do not** assume every TV is arm64.

```bash
# TV paths from tv/android
case "$ABI" in
  arm64-v8a) APK=player/app/build/outputs/apk/foss/debug/app-foss-arm64-v8a-debug.apk ;;
  armeabi-v7a|armeabi) APK=player/app/build/outputs/apk/foss/debug/app-foss-armeabi-v7a-debug.apk ;;
  *) APK=player/app/build/outputs/apk/foss/debug/app-foss-universal-debug.apk ;;
esac

adb -s "$SERIAL" install -r "$APK"
```

### 4. Launch and verify foreground

```bash
adb -s "$SERIAL" shell monkey -p com.playbridge.player -c android.intent.category.LAUNCHER 1
sleep 2
adb -s "$SERIAL" shell pidof com.playbridge.player
adb -s "$SERIAL" shell dumpsys window | grep -E 'mCurrentFocus|mFocusedApp'
adb -s "$SERIAL" shell dumpsys package com.playbridge.player | grep -E 'versionName|versionCode|lastUpdateTime|primaryCpuAbi'
```

Success looks like focus/resumed activity on `com.playbridge.player/.MainActivity` (or the phone equivalent) and a non-empty `pidof`.

### Phone variant

Same loop with:

- package `com.playbridge.sender`
- assemble `:app:assembleFossDebug` from `mobile/android`
- APKs under `app/build/outputs/apk/foss/debug/`

## Input actions

```bash
adb -s SERIAL shell input tap X Y
adb -s SERIAL shell input tap X Y && sleep 0.1 && adb -s SERIAL shell input tap X Y
adb -s SERIAL shell input swipe X Y X Y 3000
adb -s SERIAL shell input swipe X1 Y1 X2 Y2 [duration_ms]
adb -s SERIAL shell input keyevent KEYCODE_BACK
adb -s SERIAL shell input keyevent KEYCODE_HOME
adb -s SERIAL shell input keyevent KEYCODE_ENTER
adb -s SERIAL shell input keyevent KEYCODE_DPAD_CENTER   # TV remote center
adb -s SERIAL shell input keyevent KEYCODE_DPAD_UP
adb -s SERIAL shell input keyevent KEYCODE_DPAD_DOWN
adb -s SERIAL shell input keyevent KEYCODE_DPAD_LEFT
adb -s SERIAL shell input keyevent KEYCODE_DPAD_RIGHT
```

## Text input

```bash
# ASCII only; spaces as %s
adb -s SERIAL shell input text "hello%sworld"

# ADB Keyboard (CJK / special chars) when installed
adb -s SERIAL shell ime set com.android.adbkeyboard/.AdbIME
adb -s SERIAL shell am broadcast -a ADB_INPUT_B64 --es msg "$(echo -n 'your text' | base64)"
adb -s SERIAL shell am broadcast -a ADB_CLEAR_TEXT
```

## Screenshot and UI tree

```bash
adb -s SERIAL exec-out screencap -p > screenshot.png
adb -s SERIAL shell uiautomator dump /sdcard/window_dump.xml
adb -s SERIAL pull /sdcard/window_dump.xml ./window_dump.xml
```

## Install / uninstall

```bash
adb -s SERIAL install -r /path/to/app.apk
adb -s SERIAL uninstall com.playbridge.player
adb -s SERIAL uninstall com.playbridge.sender
```

## Common keycodes

| Keycode | Description |
|---------|-------------|
| `KEYCODE_BACK` | Back |
| `KEYCODE_HOME` | Home |
| `KEYCODE_ENTER` | Enter |
| `KEYCODE_DPAD_CENTER` | TV select / OK |
| `KEYCODE_DPAD_UP` / `DOWN` / `LEFT` / `RIGHT` | TV d-pad |
| `KEYCODE_DEL` | Delete |
| `KEYCODE_VOLUME_UP` / `DOWN` | Volume |
| `KEYCODE_POWER` | Power |
| `KEYCODE_TAB` | Tab |
| `KEYCODE_ESCAPE` | Escape |

## Troubleshooting

| Symptom | What to do |
|---|---|
| `unauthorized` | Accept RSA prompt on device; `adb kill-server && adb start-server`; reconnect |
| No port 5555 open | Enable wireless debugging / ADB over network on the TV; or USB + `tcpip 5555` |
| `INSTALL_FAILED_NO_MATCHING_ABIS` | Read `ro.product.cpu.abi` and install the matching split (many Smart TVs are `armeabi-v7a` only) |
| Install succeeds, blank focus | Wait 1–2s and re-check `mCurrentFocus` / `mResumedActivity`; re-launch with `monkey` or `am start` |
| Glob fails on logcat `*:S` | Quote filters in zsh |
| Wrong app updated | Confirm package (`player` vs `sender`) and Gradle root before assemble |
