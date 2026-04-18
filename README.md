# PartPulse Display Launcher — Android TV App

A kiosk-style Android TV launcher that loads your WorkForce / PartPulse display URL
in a full-screen WebView with optional PIN-locked settings.

---

## Features

| Feature | Detail |
|---|---|
| Full-screen WebView | Loads any `http://` or `https://` URL — no HTTPS required for LAN IPs |
| Auto-refresh | Off / 30 s / 1 min / 2 min / 5 min / 10 min — configurable in Settings |
| 4-digit PIN lock | Guards the Settings screen; default PIN is **1234** — change on first boot! |
| Floating toolbar | Shows URL, Refresh, Settings buttons; auto-hides after 5 s |
| Boot auto-start | App launches automatically after the TV powers on |
| TV Leanback | Appears in the Android TV home apps row |
| Kiosk back button | Back navigates WebView history, never exits the app |
| Persistent settings | All config survives reboots via SharedPreferences |

---

## Default configuration

```
URL:             http://192.168.0.62:5000/#/display
Auto-refresh:    60 seconds
PIN:             1234  ← CHANGE THIS FIRST
PIN lock:        enabled
Toolbar:         visible (auto-hides)
```

---

## Build instructions

### Requirements
- Android Studio Hedgehog 2023.1+ (or newer)
- JDK 17
- Android SDK 34 (targetSdk) + SDK 21 (minSdk — Android 5.0)

### Steps

```bash
# 1. Extract the archive (if you have the tar.gz)
tar -xzf displaylauncher-apk-project.tar.gz
cd displaylauncher

# 2. Open in Android Studio
#    File → Open → select the 'displaylauncher' folder
#    Let Gradle sync (first sync downloads ~200 MB)

# 3. Build a debug APK (no signing needed)
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/app-debug.apk

# 4. OR build a release APK and sign it
./gradlew assembleRelease
# Then sign via: Build → Generate Signed Bundle / APK in Android Studio

# 5. Install via ADB
adb connect <TV_IP>:5555
adb install app/build/outputs/apk/debug/app-debug.apk
```

---

## Enable ADB on your Android TV

1. Settings → Device Preferences → About → click **Build** 7× times
2. Settings → Device Preferences → Developer Options → enable **USB Debugging**
3. Settings → Device Preferences → Developer Options → enable **ADB over network**
4. Note the IP shown (or find it in Network settings)
5. `adb connect <IP>:5555`

---

## Remote management via Tailscale

Since you already use Tailscale on your Proxmox servers, install it on each TV box:

```bash
# From any TV's ADB shell:
adb -s <TV_IP>:5555 shell

# Inside the shell — install Tailscale APK from their download page, then:
# (Tailscale is available on Google Play on Android TV devices)

# Reboot a TV remotely
adb -s <tailscale-tv-ip>:5555 reboot

# Push a settings broadcast to reload
adb -s <tailscale-tv-ip>:5555 shell am start \
  -n com.partpulse.displaylauncher/.DisplayActivity
```

Name suggestion for 4 TVs: `display-workshop-1`, `display-workshop-2`, `display-office-1`, `display-office-2`

---

## Changing the default URL without rebuilding

Edit `AppPrefs.java` → `DEFAULT_URL` constant before building, or just change it
in the app's Settings screen after installation.

---

## Package name

`com.partpulse.displaylauncher`

---

## File structure

```
displaylauncher/
├── app/
│   ├── build.gradle
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/com/partpulse/displaylauncher/
│       │   ├── AppPrefs.java           ← SharedPreferences wrapper
│       │   ├── BootReceiver.java       ← Auto-start on boot
│       │   ├── DisplayActivity.java    ← Main WebView screen
│       │   ├── PinActivity.java        ← PIN entry numpad
│       │   └── SettingsActivity.java   ← Settings form
│       └── res/
│           ├── drawable/               ← Dots, buttons, icon SVG
│           ├── layout/                 ← activity_display/pin/settings.xml
│           ├── values/                 ← strings.xml, styles.xml
│           └── xml/                    ← network_security_config.xml
├── build.gradle
├── settings.gradle
├── gradle.properties
└── README.md
```
