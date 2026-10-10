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

## About this fork

This is a **personal fork** ([mhoogenbosch/PhairPlay](https://github.com/mhoogenbosch/PhairPlay)) of
[mazer666/PhairPlay](https://github.com/mazer666/PhairPlay), which I rebuilt **for my own home use
only**. It is not an official build, carries no support or warranty, and is tailored to my own
devices (a Nokia Streaming Box 8010, a TCL and a Xiaomi Google TV, and five Fire TV sticks) — your
mileage may vary.

**Current release: [v1.1.0-mh.17](https://github.com/mhoogenbosch/PhairPlay/releases/latest)** (2026-10-10).
Releases are versioned `1.1.0-mh.N` on top of upstream, titled `PhairPlay v1.1.0-mh.N — <what changed>`,
and listed in [CHANGELOG.md](CHANGELOG.md). CI builds the release APKs unsigned; they are signed
locally with one fixed key, so every release installs over the previous one (`adb install -r`).

### What this fork adds / changes
- **iOS 26 screen mirroring works** — fixes for the RTSP body-size limit, a watchdog for the
  `_airplay` mDNS name-conflict probe that could hang forever, and reliable rendering onto the video
  Surface.
- **Reliable foreground-on-connect** — the app is brought to the front the moment a sender connects
  (direct `startActivity` via the draw-over-other-apps permission), so mirroring appears instantly
  even from a closed app on an always-unlocked TV, where a full-screen-intent notification is ignored.
- **Receiver-appliance lifecycle** — the app steps aside to the previous app / launcher when a
  session ends, and otherwise keeps advertising quietly in the background.
- **Unique AirPlay identity per install** — a stable per-device `deviceid` derived from a persistent
  UUID, so several TVs on one LAN don't collide as a single receiver.
- **Headless display-name setter** — set the advertised device name over `adb` (broadcast intent),
  for scripted multi-device installs.
- **Self-healing advertisement** — a watchdog, an mDNS self-probe and a periodic health check bring
  the receiver back after a network drop or standby instead of leaving it invisible.
- **Dead sessions are cleaned up** — a sender that disappears without closing the connection (TV into
  standby while mirroring, phone off the Wi-Fi) is dropped after 30 s without NTP replies or as soon
  as the network is lost, instead of blocking every new sender until a restart.
- **Updates without interrupting** — after an update the receiver restarts itself
  (`MY_PACKAGE_REPLACED`), so `adb install -r` never has to bring the app to the front.
- **No black picture after Home or the screensaver** — the mirror decoder is rebuilt onto the new
  Surface from the cached SPS/PPS; Back is ignored while mirroring (stop on the sender instead).
- **On-device diagnostics** — an HTTP log dump (`:8001`) + live tail (`:8002`) and a persistent file
  log, so a receiver can be debugged without `adb logcat`.
- **Sensible defaults for these TVs** — Cast off by default (redundant next to built-in
  Chromecast), AirPlay on, start-on-boot on.
- **Miracast removed** — a sideloaded app cannot register as a Wi-Fi Display sink
  (`WifiP2pManager.setWfdInfo` needs the system-only `CONFIGURE_WIFI_DISPLAY` permission), and the
  upstream code had no media path anyway. Dropping it also drops the Wi-Fi Direct and location
  permissions. Fire TV has a built-in Miracast receiver (*Display Mirroring*) if you need one.

> **Note:** YouTube (and similar apps) blank their own video layer while screen-mirroring as a DRM
> measure — that's the app's choice, not something a receiver can override. Use the app's native
> Cast button for those instead.

---

## Current Status — v1.1.0-mh.17

PhairPlay's AirPlay 2 receiver is fully implemented and runs daily on the TVs above. Download the APK from this fork's [Releases page](https://github.com/mhoogenbosch/PhairPlay/releases); upstream releases are on [mazer666/PhairPlay](https://github.com/mazer666/PhairPlay/releases).

The AirPlay 2 stack is complete end-to-end: mDNS advertising, RTSP handshake, HomeKit-style pairing, FairPlay key decryption, H.264 mirroring, AAC-ELD/AAC-LC/ALAC audio, NTP A/V sync, and DACP reverse remote. Real-device validation with macOS and iOS senders is the current focus.

Google Cast receiver stack is in progress (control-plane implemented; media playback pending). Miracast is not part of this fork.

## Features

### AirPlay 2 (fully implemented)
- Screen mirroring from macOS 12+ and iOS/iPadOS 16+ — H.264 hardware decode
- FairPlay session decryption (fp-setup v2/v3 + legacy rsaaeskey) via native libplayfair
- HomeKit-style pairing (Ed25519/X25519) and legacy SRP PIN pairing
- Mirroring audio: AAC-ELD, AAC-LC, ALAC — with independent A/V start/stop
- System audio streaming (ALAC, unencrypted) — reliable path for app audio
- AirPlay video URL mode (`/play` content) + transport controls (play/pause/scrub)
- Now-playing metadata (DMAP) with album artwork overlay
- DACP reverse remote — TV remote controls the sender's playback
- NTP timing and UDP audio retransmit (packet-loss recovery)
- AirPlay photo receiver — JPEG/PNG from iOS Photos app displayed full-screen
- Access-control lockout after repeated failed pairing attempts

### App & Platform
- Android TV / Fire TV app shell with foreground service and status UI
- Mirror audio toggle and PIN-auth toggle in Settings
- Works on Google TV (Android 10+) and Fire TV (Android 7+)
- Google TV Cast Connect SDK lifecycle (full testing requires Cast app ID)
- Zero ads, zero analytics, zero internet required
- Open source — Apache 2.0 license

## What PhairPlay Does NOT Do

- **FairPlay DRM content** (Netflix, Disney+, Apple TV+) — Apple DRM; not decryptable by any open-source receiver
- **Apple Music in-app audio** — protected on every AirPlay path; use system audio output instead
- **Buffered audio playback** (AirPlay 2 type 103) — accepted but not played back yet
- **Cloud/remote streaming** — local network only
- **Cast media playback** — control plane is ready; media decode integration is in progress
- **Miracast** — removed in this fork (not possible for a sideloaded app)

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

### Option A: Download a Release APK (easiest)

Go to the [Releases page](https://github.com/mhoogenbosch/PhairPlay/releases) and download the APK for your device:

| APK | Device |
|-----|--------|
| `PhairPlay-v1.1.0-mh.N-googletv.apk` | Google TV, Android TV (Android 10+) |
| `PhairPlay-v1.1.0-mh.N-firetv.apk` | Amazon Fire TV (Android 7.1+) |

Then install it via ADB (see the Sideloading Guide below) or a sideloading app like *Downloader* on Fire TV.

**Updating:** `adb install -r <apk>` keeps the name and settings, and from mh.17 the receiver restarts
by itself — nothing on screen changes. Going *to* mh.17 from an older version works the same way.
Exception: TVs whose vendor blocks background service starts (TCL) need PhairPlay opened once.

### Option B: Build from Source

1. **Install prerequisites**
   ```bash
   # Install Android Studio from https://developer.android.com/studio
   # Install JDK 17 or later
   ```

2. **Clone the repository**
   ```bash
   git clone https://github.com/mhoogenbosch/PhairPlay.git
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

1. Launch PhairPlay on your TV once. With *Start on boot* on (the default) it keeps advertising in the background from then on.
2. **iPhone/iPad:** Control Center → **Screen Mirroring**, or the AirPlay button in an app. **Mac:** the **AirPlay** / Screen Mirroring icon in the menu bar.
3. Select your TV from the list (it appears under the name set in PhairPlay).
4. The TV switches to PhairPlay by itself and shows your screen; when you stop, it returns to what was on screen before.
5. To stop: end mirroring on the sender.

---

## Known Limitations

- **Personal fork** — tested on the devices listed above with iPhones on iOS 26/27. Issues are welcome in [this fork](https://github.com/mhoogenbosch/PhairPlay/issues).
- **Apple Music in-app audio is not decryptable.** macOS protects it with FairPlay on every AirPlay path. Route the Mac's system audio output instead (works fine).
- **FairPlay-protected video** (Netflix, Disney+, Apple TV+) cannot be mirrored — this is Apple's DRM, not a PhairPlay limitation.
- **Buffered audio (AirPlay 2 type 103)** is accepted but not yet played back.
- **AirPlay video from the YouTube app** is not possible: it asks for `fp-setup2`, a FairPlay variant no open-source receiver implements (answered with `421`, like UxPlay). Mirroring YouTube shows a blank video layer (YouTube's own DRM choice). Use YouTube's Cast button.
- **Google Cast** requires a registered Cast app ID for end-to-end testing; see [docs/guides/CAST_APP_ID.md](docs/guides/CAST_APP_ID.md).
- If your router has **AP isolation** or **multicast filtering** enabled, PhairPlay may not appear in the AirPlay menu. Disable these settings on your router.
- On very busy 2.4 GHz Wi-Fi networks, you may experience latency above 100 ms. Use 5 GHz or Ethernet for best results.
- **PIN auth is optional.** When disabled (default), any device on the same network can mirror to the TV. Enable PIN auth in Settings if you're on a shared network.

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
