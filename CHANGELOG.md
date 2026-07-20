# Changelog

All notable changes to PhairPlay will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

This fork ([mhoogenbosch/PhairPlay](https://github.com/mhoogenbosch/PhairPlay)) versions
its releases as `<semver>-mh.<n>` on top of the upstream
([mazer666/PhairPlay](https://github.com/mazer666/PhairPlay)) baseline.

---

## [Unreleased]

---

## [1.1.0-mh.3] - 2026-07-20

The **fleet-rollout** release. mh.2 made mirroring visible via a full-screen intent, but that
never fired on an always-unlocked TV, so the app still didn't come forward on connect. This release
foregrounds the app reliably, gives every TV a unique AirPlay identity, and lets each device be
named headlessly over adb — so all seven receivers can be installed and named in one scripted pass.

### Fixed
- **App now actually comes to the foreground on connect on an always-unlocked TV.** The
  full-screen intent added in mh.2 is *not honoured* while the device is interactive — Android
  degrades it to a heads-up notification, which a TV never surfaces — so the app stayed on the
  home screen when a sender connected and video rendered onto no Surface (black screen; verified
  2026-07-20 with logcat: session CONNECTED + H.264 flowing, Activity never launched). The service
  now starts `MainActivity` directly when it holds the draw-over-other-apps permission
  (`SYSTEM_ALERT_WINDOW`), which grants a background-activity-launch exemption; the full-screen
  intent remains as a fallback for devices without the permission. The video Surface is now present
  by the time iOS sends its connect-time IDR keyframe, so mirroring appears instantly with no manual
  app-open or disconnect/reconnect. Grant on a device with
  `adb shell appops set <pkg> SYSTEM_ALERT_WINDOW allow` (re-grant after any reinstall).
- **Unique AirPlay `deviceid` per install (fixes fleet identity collision).** Modern Android
  withholds the real hardware MAC, so every install fell back to the same hardcoded
  `aa:bb:cc:dd:ee:ff`. `deviceid` is the identity iOS keys an AirPlay receiver on, so multiple TVs
  on one LAN advertised a single identity — iOS merged them and showed one name for all. When no
  unique hardware MAC is available the `deviceid` is now derived from the per-install persistent
  UUID as a stable, locally-administered, unicast MAC (unique per device, stable across restarts and
  `install -r`). Also refreshes the name a previously-connected sender had cached.

### Added
- `SYSTEM_ALERT_WINDOW` permission (draw over other apps) — see above.
- **Headless display-name setter for scripted fleet rollout.** A new exported
  `DisplayNameReceiver` sets the advertised device name over adb without touching the on-screen
  Settings UI:
  `am broadcast -n <pkg>/.service.DisplayNameReceiver -a com.phairplay.action.SET_DISPLAY_NAME --es name "Woonkamer-TV"`.
  The receiver only persists the name; the running service now **observes** the setting and
  re-registers mDNS live, so the rename also takes effect immediately for an in-app change and never
  needs a manual restart. A blank name clears the override (falls back to the Android device name).

---

## [1.1.0-mh.2] - 2026-07-19

The release that makes **iOS 26 screen mirroring actually visible on the TV**. With
v1.1.0-mh.1's fixes the full mirror session already worked (verified: H.264 video decoded
at ~60 fps), but video only renders when the app's Activity — and thus its Surface — is in
the foreground, which the receiver-appliance lifecycle no longer guarantees.

### Added
- **App comes to the foreground when a sender connects.** A high-importance full-screen
  intent notification (new channel `phairplay_incoming_channel`) starts `MainActivity`
  when the AirPlay state hits CONNECTED, so the video Surface exists before frames arrive —
  no more black screen when mirroring starts while the app is closed. Ported from
  [JObersi10/PhairPlay](https://github.com/JObersi10/PhairPlay). Adds the
  `USE_FULL_SCREEN_INTENT` permission.
- **…and steps aside again when the session ends.** An auto-opened Activity moves its task
  to the back on disconnect, so the TV returns to whatever app was visible before the
  session (or the launcher when there was none). A manually opened app never auto-hides,
  and the "incoming connection" notification is withdrawn on disconnect.
- **Diagnostics: unknown mirror payload types are hexdumped once per session** (first 16
  header bytes + first 32 payload bytes). iOS 26 sends a steady ~25 KB "payload type 5"
  every second that no open-source receiver documents; this collects material to analyse it.

### Fixed
- **Double teardown escalated the mDNS name to "(3)/(4)".** TEARDOWN of the last stream and
  the subsequent socket close both fired `onStreamingStopped`, running two concurrent mDNS
  restarts that raced each other's fresh registration. A guard flag (ported from
  JObersi10/PhairPlay) makes teardown idempotent.
- `PhairPlayService` is marked `android:stopWithTask="false"`, matching the
  receiver-appliance lifecycle introduced in mh.1.

---

## [1.1.0-mh.1] - 2026-07-19

Based on upstream `v1.0.0-beta.1`.

### Fixed
- **mDNS name conflict left `_airplay._tcp` unregistered forever.** When the requested
  service name collides with another record on the same device (e.g. the TV's own Google
  Cast registration under the system device name), newer Android versions do not
  auto-rename: `MdnsAdvertiser` gets stuck probing and never delivers *any* callback, so
  the device never appears in AirPlay pickers. `MdnsService` now runs a 5-second watchdog
  per registration attempt and retries with a numbered suffix ("Name (2)", "Name (3)",
  max 3 attempts) on both an explicit registration failure and a silent stuck probe.
  Verified on a Nokia Streaming Box 8010 (Google TV), which conflicts with its own
  Google Cast record: now advertises as "Nokia Streaming Box 8010 (2)".
- **mDNS restart race escalated the name to "(2)/(3)" after every session.**
  `MdnsService.restart()` re-registered while the previous (asynchronous) unregistration
  was still in flight, conflicting with its own stale registration. `restart()` now waits
  for both unregistration callbacks (2-second timeout fallback) before re-registering.
- **RTSP message limit rejected the iOS mirror-stream SETUP.** The 64 KB
  `MAX_MESSAGE_BYTES` cap was smaller than the ~77 KB binary-plist SETUP that iOS 26
  senders emit for the mirror stream, so the sender tore the session down right after
  audio started. Raised to 1 MB (also submitted upstream as
  [mazer666/PhairPlay#11](https://github.com/mazer666/PhairPlay/pull/11)).

### Changed
- **Receiver-appliance lifecycle: the receiver keeps running when the UI goes away.**
  Backing out of the app (`MainActivity` finishing) and swiping it from recents
  (`onTaskRemoved`) no longer stop the foreground service, so the TV stays visible in
  AirPlay pickers — matching how a dedicated receiver box behaves. Stopping the receiver
  is now an explicit act via the in-app protocol toggles.

### Added
- **On-device diagnostics** (ported from
  [JObersi10/PhairPlay](https://github.com/JObersi10/PhairPlay), trimmed):
  - `LogBuffer` — 500-line in-memory ring buffer plus a persistent `files/phairplay.log`
    (capped at 1000 lines) and an uncaught-exception hook, so crash logs survive process
    death. The Timber tree is planted in **all** builds; release sideloads are now
    debuggable without adb.
  - `DiagnosticServer` — plain-HTTP log access on the LAN: full dump on port **8001**,
    live streaming tail on port **8002** (`curl http://<tv-ip>:8002`).

---

## [1.0.0-beta.1] - 2026-06-14

### Added

**AirPlay 2 receiver — full stack**
- Screen mirroring (H.264) from macOS 12+ and iOS/iPadOS 16+ via RTSP on port 7000
- FairPlay session decryption: fp-setup v2 (RAOP audio) and v3 (mirroring/Safari) via native libplayfair (JNI); legacy rsaaeskey RSA-OAEP recovery for AirPort Express compatibility
- HomeKit-style pairing: Ed25519 identity, X25519 ECDH key agreement, controller key persistence (`PairingStore`), failed-attempt lockout
- Legacy SRP-6a PIN pairing with on-screen PIN entry screen (`LegacyPairSetupPin`, `PinScreen`)
- `MirrorStreamServer` + `MirrorCrypto` — interleaved RTP reassembly, AES-128-CTR stream decryption (keystream always advanced to prevent reuse)
- `AudioStreamServer` — mirror realtime audio (type 96): UDP RTP, AES-128-CBC, AAC-ELD/AAC-LC decode via MediaCodec, RAOP retransmit, AudioTrack with volume
- `AlacDecoder` + native libalac — RAOP/SDP audio path: AES-128-CBC (per-packet IV) + Apple's ALAC decoder; decode-health mute guard (wrong key → silence, not static)
- `BufferedAudioServer` — AirPlay 2 buffered audio (type 103) accepted and instrumented
- `AirPlayVideoPlayer` — AirPlay video URL mode (`/play`) + transport controls (play/pause/scrub/stop)
- `NowPlayingInfo` (DMAP parser) + album artwork → `NowPlayingScreen` overlay
- `DacpClient` — `_dacp._tcp` discovery + reverse transport control from TV remote to sender (play/pause/skip/volume)
- `AirPlayNtpClient` — Apple NTP for A/V synchronisation
- `InfoResponder` — `GET /info` capability advertisement (plist)
- `PlistCodec` — Apple binary plist encode/decode
- `RaopRsa` — legacy rsaaeskey recovery (RSA-OAEP, AirPort Express key)
- `StreamStats` — per-session RTP statistics (packet count, duplicates, queue drops)
- `Base64Util` — pure-JVM Base64 so SDP parsing is testable without Android framework
- `SdpParser` — extended: codec/encryption/channel/rate parsing for all AirPlay audio types
- Aspect-fit (letterbox/pillarbox) video rendering with black background in `StreamingScreen`
- Real PNG bitmap launcher icon and TV banner (replaces placeholder XML)
- Mirror Audio toggle and PIN-auth toggle in Settings
- Receiver survives app restart/relaunch; mirroring and audio stop cleanly on app exit

**Native layer**
- CMake build for all ABIs (armeabi-v7a, arm64-v8a, x86, x86_64)
- `fairplay_jni.c` — JNI bridge for `playfair_decrypt` with full null/length/OOM validation
- Apple ALAC decoder (C++, vendored) + JNI bridge (`alac_jni.cpp`)
- Reverse-engineered FairPlay (C, `playfair/`) compiled for all ABIs
- Strict-aliasing fix in `modified_md5.c` (union type-punning) and `sap_hash.c` (memcpy + union)

**Test suite**
- 247 unit tests, 0 failures: FairPlay, RaopRsa, Base64Util, ALAC cookie, DMAP, legacy PIN SRP, audio stream server, RTSP handler, service controller
- Robolectric added for framework-dependent tests (Android Base64, Intent, etc.)

**Release infrastructure**
- `scripts/release.sh` — local release script: builds signed GoogleTV + FireTV APKs, creates git tag, publishes GitHub Release via `gh` CLI (no CI minutes consumed)
- First signed GitHub Release: [v1.0.0-beta.1](https://github.com/mazer666/PhairPlay/releases/tag/v1.0.0-beta.1)

### Changed
- `VideoDecoder`: SPS/PPS-driven reinit on resolution change, self-heal on decoder error, keyframe resync after drops, decoupled network reader (bounded queue, drop-under-load), re-attach to Surface after backgrounding
- `AudioPlayer`: extended to support ALAC and new audio stream types from `AudioStreamServer`
- `RtspHandler`: extended to 700+ lines — handles all AirPlay 2 verbs (ANNOUNCE, SETUP plist+SDP, RECORD, TEARDOWN stream-scoped, GET/SET_PARAMETER, FLUSH, PAUSE, photo PUT/DELETE, `/play`, `/rate`, `/scrub`, `/stop`, `/feedback`, buffered-audio control)
- `AirPlayReceiver`: event channel socket now closed via `use {}` block (fixes file-descriptor leak)
- `SettingsFragment`: mirror audio and PIN-auth toggles added

### Fixed
- `DatagramPacket` length reset before each `receive()` call in `AudioStreamServer` — prevented packet truncation when a smaller packet arrived first
- JNI bridge (`fairplay_jni.c`) now validates input arrays for null, length, and OOM before native access — prevents out-of-bounds reads and native crashes
- Strict-aliasing UB in `modified_md5.c` and `sap_hash.c` — union + memcpy replaces direct `uint32_t*` cast of `unsigned char*`
- `Cipher.getInstance()` moved out of hot path in `AudioStreamServer` (~92 allocations/s → 1 per session)

---

<!-- Format:
## [X.Y.Z] - YYYY-MM-DD

### Added
### Changed
### Fixed
### Removed
-->
