# TV Remote Receiver

Android TV app: auto-starts a WebSocket server, shows a pairing QR, and turns JSON packets into
D-pad / media / volume / mouse input.

## Build & install
1. Open the folder in Android Studio (Hedgehog+), let Gradle sync (AGP 8.2 needs Gradle 8.2+).
2. `adb connect <tv-ip>:5555` then run, or `adb install app-debug.apk`.
3. Launch the app once (required for the boot receiver to fire on later boots).

## Enable input injection (pick one)
**A. Accessibility (any box, no root)** – press "Enable remote control access" on the dashboard, or:

    adb shell settings put secure enabled_accessibility_services \
      com.example.tvremote/com.example.tvremote.RemoteAccessibilityService
    adb shell settings put secure accessibility_enabled 1

**B. Privileged (custom ROM / AOSP / vendor box you control)** – sign with the platform key or place
in `/system/priv-app` with a privapp-permissions XML granting `android.permission.INJECT_EVENTS`.
`CommandDispatcher` detects this at runtime and uses `Instrumentation.sendKeyDownUpSync()` for everything.

## Wire protocol
    S->C {"type":"challenge","nonce":"<hex>"}
    C->S {"type":"auth","hmac":"<hex HMAC-SHA256(key=auth_token utf8, msg=nonce utf8)>"}
    S->C {"type":"auth_ok","capabilities":{"privileged_injection":false,"accessibility":true}}

    C->S {"type":"command","command":"DPAD_UP"}
    C->S {"type":"mouse","mode":"rel","x":12,"y":-4}        // pixel deltas
    C->S {"type":"mouse","mode":"abs","x":0.5,"y":0.5}      // 0..1 of screen
    C->S {"type":"command","command":"MOUSE_CLICK"}
    S->C {"type":"error","code":"ACCESSIBILITY_DISABLED","command":"DPAD_UP"}

Commands: DPAD_UP/DOWN/LEFT/RIGHT/CENTER, OK, ENTER, BACK, HOME, RECENTS, MENU, SETTINGS, SEARCH,
CHANNEL_UP/DOWN, VOLUME_UP/DOWN/MUTE, PLAY_PAUSE, PLAY, PAUSE, STOP, NEXT, PREVIOUS, REWIND,
FAST_FORWARD, NUM_0..NUM_9, MOUSE_CLICK, SCROLL_UP, SCROLL_DOWN.
(MENU, SETTINGS, SEARCH, CHANNEL_*, NUM_* need path B.)
