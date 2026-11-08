# Head Unit ⇄ iPhone Link

Android head-unit receiver + iPhone companion app + test harness for protocol v1:

- **[BtMetadataListener.kt](./android/BtMetadataListener.kt)** — reads title/artist/album from the
  Bluetooth stack's AVRCP MediaSession. If the Android build has AVRCP 1.6 cover art enabled,
  it also delivers the *real* artwork from the phone — the online lookup then becomes a fallback only.
- **[PhoneLinkClient.kt](./android/PhoneLinkClient.kt)** — WebSocket client (OkHttp) to the iPhone app:
  sends `track`, receives `artwork` (JSON header + one binary JPEG frame) and `gps` at 1 Hz;
  auto-reconnect with capped backoff; stale-artwork discard via `seq`.
- **[BleLinkClient.kt](./android/BleLinkClient.kt)** — the same link over **Bluetooth LE**
  (no hotspot needed); same `PhoneLinkClient.Listener` interface.
- **[LinkForegroundService.kt](./android/LinkForegroundService.kt)** — glue + `DashboardBus`
  observable state with 3-sample speed smoothing.
- **[ios/](./ios/README.md)** — the **PhoneLink iPhone app** (SwiftUI): WebSocket server,
  **BLE GATT server**, background GPS streaming, and artwork lookup chain **YouTube Music → iTunes**.
- **[test/fake_iphone_server.py](./test/fake_iphone_server.py)** — stands in for the iPhone app:
  real YouTube Music + iTunes artwork lookup and simulated GPS, so the head unit can be
  developed/tested today.
- **[test/fake_headunit_client.py](./test/fake_headunit_client.py)** — protocol smoke test client;
  also works against the real iPhone app over the hotspot.

## Get the IPA

**Direct download (no login needed):**
https://github.com/hamoodymussa90-pixel/phonelink/releases/latest/download/PhoneLink-unsigned.ipa

Every push to `main` rebuilds it via GitHub Actions (`.github/workflows/build-ipa.yml`)
and updates the `latest` release. Install from Windows with **Sideloadly** (or AltStore),
signing in with your Apple ID. Free Apple IDs must re-sideload every 7 days.

## Gradle

```kotlin
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
```

## AndroidManifest.xml

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<!-- BLE transport (API 31+) -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<application ...>
    <service
        android:name=".BtMetadataListener"
        android:exported="false"
        android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
        <intent-filter>
            <action android:name="android.service.notification.NotificationListenerService" />
        </intent-filter>
    </service>

    <service
        android:name=".LinkForegroundService"
        android:exported="false"
        android:foregroundServiceType="connectedDevice" />
</application>
```

## One-time setup on the head unit

1. Pair the iPhone over Bluetooth (A2DP/AVRCP) as usual.
2. Grant **Notification access** to the app
   (Settings → Apps → Special app access → Notification access) — this is what allows
   reading the Bluetooth MediaSession.
3. Connect the head unit's Wi-Fi to the iPhone's **Personal Hotspot**.
   The iPhone is then reachable at the gateway IP, normally `ws://172.20.10.1:8777`.
4. Start `LinkForegroundService`.

## AVRCP cover-art feasibility check (do this first!)

With the iPhone connected and music playing, run:

```
adb shell dumpsys media_session | grep -A5 bluetooth
```

and check in `BtMetadataListener` logs whether `local art: true` appears.
If your build ships with `avrcp_controller_enable_cover_art` (AOSP, Android 11+) or
`persist.bluetooth.avrcpcontroller.support_cover_art=true`, the phone's actual artwork
arrives over Bluetooth and the iPhone app is only needed for GPS.

## Testing without the iPhone app

```
pip install websockets ytmusicapi
python test/fake_iphone_server.py          # terminal 1 (laptop on same network)
python test/fake_headunit_client.py        # terminal 2 — protocol smoke test
```

Artwork lookup chain in the test server matches the iPhone app:
**YouTube Music (InnerTube) → iTunes fallback** (`--no-ytmusic` to skip YT Music).

Then point `PhoneLinkClient(serverUrl = "ws://<laptop-ip>:8777")` at the laptop to
exercise the real head-unit app: it will receive genuine artwork and a
simulated GPS track (a car driving a loop at ~60 km/h).

## Protocol v1 recap

| Direction | Frame | Content |
|---|---|---|
| HU → phone | text | `{"type":"track","seq":N,"title","artist","album","durationMs"}` |
| phone → HU | text | `{"type":"artwork","seq":N,"status":"ok\|not_found\|error","format","bytes","source"}` |
| phone → HU | binary | JPEG bytes (only when `status=="ok"`, immediately after header) |
| phone → HU | text | `{"type":"gps","ts","lat","lon","speedMps","headingDeg","altitudeM","hAccM"}` @ 1 Hz |
| both | text | `{"type":"ping"}` / `{"type":"pong"}` every 10 s |

## Bluetooth (BLE) transport

The same info also flows over **BLE** — no hotspot needed. The iPhone advertises a
custom GATT service (`7E400001-B5A3-F393-E0A9-E50E24DCCA9E`); the head unit uses
[BleLinkClient.kt](./android/BleLinkClient.kt) (same `PhoneLinkClient.Listener`
interface, so the dashboard code doesn't change):

| Characteristic | UUID suffix | Direction | Content |
|---|---|---|---|
| GPS | `...0002` | notify | one JSON `gps` message per notification, 1 Hz |
| TRACK | `...0003` | write | JSON `track` message from the head unit |
| ART_META | `...0004` | notify | JSON artwork header incl. `chunks` count |
| ART_DATA | `...0005` | notify | binary chunks: `seq:u16be, index:u16be` + payload |

Extra Android permissions: `BLUETOOTH_SCAN` + `BLUETOOTH_CONNECT` (API 31+).

Notes:
- BLE moves ~5–15 KB/s, so the iPhone downscales artwork to ~300 px for this
  transport (Wi-Fi still gets the full 600 px).
- iPhones can't do classic Bluetooth SPP to non-MFi devices — BLE GATT is the
  supported path.
- When the iPhone app is backgrounded, iOS hides the advertised service UUID
  from Android scanners. Connect once while the app is in the foreground; the
  established connection then survives locking/backgrounding
  (`bluetooth-peripheral` background mode is enabled).
