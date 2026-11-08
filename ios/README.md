# PhoneLink — iPhone companion app

SwiftUI app implementing the protocol-v1 **server** side:

| File | Role |
|---|---|
| `PhoneLinkApp.swift` | App entry point |
| `LinkCoordinator.swift` | Glue + published UI state, stale-artwork guard via `seq` |
| `LinkServer.swift` | WebSocket server on port **8777** (Network.framework `NWListener`, zero dependencies) |
| `ArtworkService.swift` | Artwork chain: LRU cache → **YouTube Music** (InnerTube, unofficial — matches YT-exclusive uploads) → **iTunes Search API** fallback |
| `LocationStreamer.swift` | CoreLocation @ 1 Hz, background-capable, maps to protocol `gps` JSON |
| `ContentView.swift` | Status screen: clients, now playing, artwork preview, GPS |

## Option A — IPA from CI (no Mac needed)

**Direct download:**
https://github.com/hamoodymussa90-pixel/phonelink/releases/latest/download/PhoneLink-unsigned.ipa

1. On Windows, install **Sideloadly** (sideloadly.io), plug in the iPhone,
   drop the IPA in, and sign in with your Apple ID — Sideloadly signs and installs it.
   Free Apple IDs: re-sideload every 7 days; a $99/yr developer account removes that.
2. On the iPhone: Settings → General → VPN & Device Management → trust your certificate.

## Option B — Build in Xcode (Mac)

1. Install XcodeGen (`brew install xcodegen`), run `xcodegen generate` in this folder
   (uses `project.yml`, which also generates `Info.plist` with the location/background keys).
2. Open `PhoneLink.xcodeproj`, set your Team under Signing & Capabilities, build to the phone.

## First run

1. Launch the app, grant Location **Always** when prompted
   (Settings → PhoneLink → Location → *Always* — this is what keeps GPS + artwork
   flowing while the phone is locked).
2. Turn on **Personal Hotspot**; join the head unit to it.
3. The head unit connects to `ws://172.20.10.1:8777` and everything flows.

## Verify from a laptop before the head unit is ready

Join the laptop to the iPhone's hotspot, then run the protocol smoke test
against the real app:

```
python ../test/fake_headunit_client.py ws://172.20.10.1:8777
```

Expect `OK gps`, `OK artwork` (source `ytmusic` or `itunes`), `SMOKE TEST PASSED`.

## Notes & known risks

- **YouTube Music lookup is an unofficial API** (same InnerTube endpoint the
  ytmusicapi Python library uses). If Google changes the response shape,
  `ArtworkService` silently falls back to iTunes — artwork keeps working.
- **Lock-screen behavior**: the active CoreLocation stream keeps the process
  alive while locked, which also keeps the WebSocket server and artwork
  lookups running. Do a 30-min locked-drive soak test to confirm on your
  iOS version. Expect the blue location indicator.
- iOS may pause hotspot clients aggressively if **Low Power Mode** is on;
  disable it in the car or keep the phone on a charger.
