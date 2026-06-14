# PhairPlay

PhairPlay is a free, open-source, ad-free AirPlay 2 receiver for Android TV and Fire TV. It lets your macOS or iOS/iPadOS device mirror its screen and audio directly to your TV — no Apple TV required.

```
 macOS (Monterey+)            Android TV / Fire TV
 iOS / iPadOS (16+)           ┌──────────────────────┐
 ┌────────────────┐  AirPlay  │                      │
 │  [Your Screen] │ ────────► │  [Your TV Screen]    │
 │                │           │                      │
 └────────────────┘           └──────────────────────┘
      Click AirPlay →              PhairPlay
      Select your TV →             (this app)
      Done. ✓
```

---

## Current Implementation Status

PhairPlay currently builds installable debug APKs for Google TV and Fire TV and is ready for real-device testing. The implemented receiver stack includes the Android TV UI, foreground service control, AirPlay mDNS advertising, RTSP handshake handling, H.264/AAC/ALAC pipeline pieces, AirPlay `/photo` JPEG/PNG handling, Miracast Wi-Fi Direct/WFD advertising plus RTSP control-plane handling, and Google TV Cast Connect SDK startup.

Not everything in the full product spec is proven end-to-end yet. AirPlay, Miracast, and Cast must still be validated with real sender devices. Google Cast also requires a registered Cast application ID before full receiver testing; see [docs/guides/CAST_APP_ID.md](docs/guides/CAST_APP_ID.md).

## Implemented / In Progress Features

- Android TV / Fire TV app shell with service status UI and settings
- Foreground receiver service with start, stop, and restart controls
- AirPlay mDNS advertisement and RTSP session handling
- AirPlay photo receiver endpoint (`/photo`) for JPEG and PNG display
- Hardware-accelerated H.264 decoder components
- AAC/ALAC audio playback components
- Miracast Wi-Fi Direct / WFD advertisement and RTSP control-plane handling
- Google TV Cast Connect SDK lifecycle, with Fire TV correctly disabled
- Zero ads, zero analytics, zero internet required
- Works on Google TV (Android 10+) and Fire TV (Android 7+)
- Open source — Apache 2.0 license

## What PhairPlay Does NOT Do

- No FairPlay DRM content (Netflix, Disney+, etc.)
- No cloud/remote streaming — local network only
- No audio-only AirPlay from Apple Music (screen mirroring only in v1.0)

---

## Requirements

**On your TV:**
- Google TV (Android 10+) or Amazon Fire TV (Android 7+)
- Connected to the same Wi-Fi network as your Mac
- Sideloading enabled (for Fire TV) or ADB enabled (for Google TV)

**On your Mac:**
- macOS 12 (Monterey) or later
- Connected to the same Wi-Fi network as your TV

**Network:**
- Both devices on the same subnet (common home router setup works)
- Multicast/mDNS must not be blocked (most home routers are fine)
- 5 GHz Wi-Fi or Ethernet strongly recommended for best performance

---

## Installation

### Option A: Build from Source

1. **Install prerequisites**
   ```bash
   # Install Android Studio from https://developer.android.com/studio
   # Install JDK 17 or later
   ```

2. **Clone the repository**
   ```bash
   git clone https://github.com/mazer666/PhairPlay.git
   cd PhairPlay
   ```

3. **Build the APK**
   ```bash
   # For Google TV:
   ./gradlew assembleGoogletvDebug

   # Google TV with a registered Cast App ID:
   ./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>

   # For Fire TV:
   ./gradlew assembleFiretvDebug
   ```
   The APK will be in `app/build/outputs/apk/`.

   To run the same local checks used by CI before testing on a TV:
   ```bash
   ./gradlew :test-runner:test
   ./gradlew :app:lintGoogletvDebug :app:lintFiretvDebug \
     :app:assembleGoogletvDebug :app:assembleFiretvDebug
   ```

4. **Install via ADB**
   ```bash
   # Enable ADB on your TV first (see below)
   adb connect <TV-IP-ADDRESS>

   # Google TV:
   adb install app/build/outputs/apk/googletv/debug/app-googletv-debug.apk

   # Fire TV:
   adb install app/build/outputs/apk/firetv/debug/app-firetv-debug.apk
   ```

---

## Sideloading Guide

### Google TV (e.g., Chromecast with Google TV)

1. Go to **Settings → System → About → Android TV OS build** and click it 7 times to enable Developer Options.
2. Go to **Settings → System → Developer Options** and enable **USB debugging**.
3. Note your TV's IP address from **Settings → Network & Internet**.
4. On your Mac/PC, run:
   ```bash
   adb connect <TV-IP>
   adb install app-googletv-debug.apk
   ```
5. Launch PhairPlay from your app list.

### Fire TV (Fire TV Stick, Fire TV Cube, etc.)

1. Go to **Settings → My Fire TV → About** and click **Build** 7 times to enable Developer Options.
2. Go to **Settings → My Fire TV → Developer Options** and enable:
   - **ADB debugging** → ON
   - **Apps from Unknown Sources** → ON
3. Note your Fire TV's IP address from **Settings → My Fire TV → About → Network**.
4. On your Mac/PC, run:
   ```bash
   adb connect <FireTV-IP>
   adb install app-firetv-debug.apk
   ```
5. Launch PhairPlay from **Apps → Your Apps & Games**.

---

## How to Use

1. Launch PhairPlay on your TV. You will see the Waiting Screen with your TV's name.
2. On your Mac, click the **AirPlay** icon in the menu bar (or go to **System Preferences → Displays → AirPlay Display**).
3. Select your TV from the list (it should appear as your TV's name).
4. Your Mac's screen will appear on the TV instantly.
5. To stop: click the AirPlay icon on your Mac and select "Turn Off AirPlay Mirroring", or just quit PhairPlay on the TV.

---

## Known Limitations

- **Real-device validation is still required.** The APKs build and unit tests pass, but AirPlay mirroring, AirPlay photo transfer, Miracast, and Cast still need hardware sender testing before a release claim.
- **Google Cast is not ready for end-to-end testing without a Cast app ID.** A receiver must be registered in the Google Cast Developer Console first.
- **Miracast is control-plane ready, not full WFD media playback yet.** Wi-Fi Direct discovery and WFD RTSP responses exist; MPEG-TS/RTP media ingest, HDCP negotiation, and audio/video decode integration remain future work.
- **FairPlay-protected content** (Netflix, Disney+, Apple TV+, etc.) cannot be mirrored — this is an Apple DRM restriction, not a PhairPlay limitation.
- **Audio-only AirPlay** (streaming from Apple Music app) is not fully supported in v1.0.
- If your router has **AP isolation** or **multicast filtering** enabled, PhairPlay may not appear in the AirPlay menu. Disable these settings on your router.
- On very busy 2.4 GHz Wi-Fi networks, you may experience latency above 100ms. Use 5 GHz or Ethernet for best results.
- **No sender authentication.** The AirPlay 2 pairing PhairPlay implements is non-authenticating (no PIN/password), so **any device on the same network can mirror to the TV**. Run PhairPlay only on a trusted LAN; do not expose port 7000 to untrusted networks.

For real-device failures, run `tools/collect-device-logs.sh` before restarting the app. It captures package state, memory, CPU, and filtered PhairPlay logs into `device-test-logs/`.

---

## Contributing

Contributions are welcome! Please read [docs/CONTRIBUTING.md](docs/CONTRIBUTING.md) before submitting a pull request.

Key points:
- Follow the coding rules in CONTRIBUTING.md (file size ≤400 lines soft / ≤550 lines hard max, class comments, test coverage)
- All PRs require passing CI (build + tests + lint)
- Discuss major changes in a GitHub Issue first

## License

Apache License 2.0 — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- [openairplay/airplay-spec](https://github.com/openairplay/airplay-spec) — Community-maintained AirPlay protocol documentation
- [UxPlay](https://github.com/FDH2/UxPlay) — Open-source AirPlay mirror server (reference implementation)
- [RPiPlay](https://github.com/FD-/RPiPlay) — AirPlay mirroring for Raspberry Pi (reference implementation)
