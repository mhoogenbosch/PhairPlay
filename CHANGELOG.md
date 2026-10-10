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

## [1.1.0-mh.17] - 2026-10-10

### Changed
- **The receiver restarts itself after an update.** An update kills the app and nothing started the
  service again, so the TV was no AirPlay receiver until the app was opened — an update therefore
  had to bring the app to the front and interrupt whatever was playing. `BootReceiver` now also
  handles `MY_PACKAGE_REPLACED` (same "Start on boot" setting), which Android delivers to the new
  version and which is exempt from the background foreground-service start restriction. An
  `adb install -r` now updates a TV without touching the screen. TVs whose vendor blocks background
  service starts (TCL) still need the app opened once.

---

## [1.1.0-mh.16] - 2026-10-10

### Fixed
- **A sender that vanished no longer blocks the receiver until a restart** (#19). Once a session
  was set up the control socket had no idle timeout, so a sender that disappeared without closing
  the connection (TV into standby while mirroring, phone off the Wi-Fi) left the session open
  forever: the TV stayed advertised, but every new sender got `503`. Seen in a Sony X90J standby
  log as a `NTP client send error` every 2 s all night. Two ways out now:
  - **NTP silence:** the receiver polls the sender's timing port every 2 s during mirroring; after
    30 s without a single reply the session is torn down.
  - **Network lost:** when the TV's last network goes away, the open session is dropped at once —
    a sender on a link-local address keeps its TCP socket alive across the drop otherwise.
  Unit tests cover the NTP watchdog (silent sender, answering sender, stop before the timeout).

---

## [1.1.0-mh.15] - 2026-10-10

### Removed
- **Miracast receiver.** A sideloaded app cannot become a Wi-Fi Display sink: senders find a sink
  through the WFD information element in Wi-Fi Direct frames, and `WifiP2pManager.setWfdInfo` needs
  the system-only `CONFIGURE_WIFI_DISPLAY` permission. The inherited code advertised `_wfd._tcp`
  over DNS-SD, ran the RTSP side as a server (in WFD the source is the server), and had no media
  path — on these TVs it only ever showed an error card. Removed: `miracast/` package and tests,
  the Home card, the Settings toggle, the stored setting, `Protocol.MIRACAST`, the icon/colour/
  strings in all locales, and the `CHANGE_WIFI_STATE`, `ACCESS_COARSE_LOCATION`,
  `ACCESS_FINE_LOCATION` and `NEARBY_WIFI_DEVICES` permissions. AirPlay and Cast are unchanged.

---

## [1.1.0-mh.14] - 2026-10-07

### Changed
- **Back is ignored while screen mirroring** (idea: RajaRakshith/PhairPlay). It used to close the
  app while the session kept running — the iPhone still said "mirroring", the TV showed the
  launcher. Stop mirroring on the sender instead. On the now-playing card and photos Back works as
  before.

### Added — AirPlay-video diagnostics (YouTube app)

Finding (iPhone, iOS 27.0.1, AirPlay 980.77.1): the YouTube app first plays an audio-only session,
then reconnects and asks `POST /fp-setup2` — a FairPlay variant no open-source receiver implements
(UxPlay answers 421 too). It never reaches `/play` or `/reverse`, so YouTube video can't be supported
for now; UxPlay's HLS path is documented working with iOS 26 (AirPlay 960.x). The changes below stop
the hang and keep the exchange visible should a future iOS/YouTube version take another route.

- **A second connection while a session is active is inspected instead of refused with 503.**
  AirPlay video from the YouTube app opens one for `POST /reverse` (PTTH) — the channel a receiver
  uses to fetch the HLS playlists from the iPhone (FCUP). It now gets `101 Switching Protocols`
  and everything the sender sends on it is logged; `GET /info` is answered; anything else still
  gets 503. The secondary connection never touches the primary session's state.
- **`POST /fp-setup2` is answered with `421 Misdirected Request`** instead of `501`. The YouTube app
  asks for this FairPlay variant before AirPlay video; with 501 it waited forever on an open
  connection (seen on the Nokia: the video hung on "connecting"). UxPlay can't do fp-setup2 either
  and answers 421, after which the sender continues.
- **`POST /play` and `POST /action` are logged in full** (plists decoded, nested plists and
  playlists shown). A `mlhls://` (sender-mediated HLS) URL is acknowledged but not handed to the
  player, which can't play it — playback is phase 2.

## [1.1.0-mh.13] - 2026-10-07

### Fixed
- **Now-playing card overflowed the screen.** The album art was a fixed 360 dp — 720 px on a
  1080p xhdpi TV — so the art touched the top edge and the "Audio from …" line fell off the bottom.
  The art is now at most half the screen height, the card keeps a 5 % TV-safe margin, and the album
  and sender lines ellipsize instead of wrapping.
- **Spotify's "• Video" marker is stripped from the artist** ("Lady Gaga, Bruno Mars • Video",
  "OneRepublic • Video beschikbaar").
- **Audio protocol markers no longer look like packet loss** (idea: 2archiver/Hearth). Header-only
  RTP packets, the AAC-ELD no-data marker (`00 68 34 00`) and the ALAC format packet occupy a
  sequence number but carry no audio. They were dropped (header-only) or fed to the decoder (the
  others); a dropped one read as a gap — a needless resend request and up to 32 packets of hold.
  They now advance the sequence and never reach the decoder.
- **RTSP FLUSH now flushes** (idea: 2archiver/Hearth). It was acknowledged and ignored, so after a
  pause/seek/skip up to ~1 s of stale audio could still play. The queue and reorder state are
  cleared and the stream re-anchors at the `RTP-Info: seq=` of the new audio.
- **Malformed mirror config packets are rejected cleanly** (idea: 2archiver/Hearth): every avcC
  length is range-checked, an `hvc1` (HEVC) header is refused, and `avccToAnnexB` can no longer be
  tricked by a length prefix that overflows `i + len` into ending the mirror with an exception.

### Added
- **Dutch translation** (`values-nl`).
- **The sender's own streaming report is read** (ported from Matej-Hajek/PhairPlayPhone,
  Apache-2.0). Mirror payload type 5 is a binary plist with the iPhone's encoder fps, rtt, loss and
  bitrate vs. link capacity (plus a 25,000-byte trailer while it is locked). It used to be discarded;
  it is now logged every ~10 s (`Mirror: sender enc=60fps rtt=31ms loss=0.00% tx=…`) and shown as a
  SENDER line in the debug overlay — next to our own `Video stats` it tells whether a bad picture is
  the iPhone, the network or the TV.

## [1.1.0-mh.12] - 2026-10-07

Fixes picked from a review of all 39 forks of mazer666/PhairPlay. Each was re-implemented on this
fork's code (none cherry-picks cleanly); credit to the forks named below.

### Fixed
- **Black mirror picture after Home or the screensaver, with audio still playing.** Two gaps,
  together (idea: RajaRakshith/PhairPlay):
  - When the video decoder hit an error it threw away the cached SPS/PPS and waited for new ones —
    but the iPhone only sends those at session start or on a rotation/resolution change, so the
    picture stayed black for the rest of the session. It now rebuilds from the cached SPS/PPS
    (at most 5 times in a row before falling back to the old behaviour).
  - The surface check only ran when a frame arrived and only compared object identity, but
    `SurfaceHolder.getSurface()` returns the same object after the surface is recreated. The
    decoder thread now checks on every poll (200 ms), also on `Surface.isValid`, and rebinds as
    soon as the surface is back.
- **A sender that vanished mid-handshake blocked the receiver until a restart** (idea: 2archiver/Hearth).
  The RTSP server serves one control connection at a time and had no read timeout, so a phone that
  dropped off Wi-Fi during pairing kept port 7000 occupied: the TV stayed visible in AirPlay pickers
  but no one could connect. The control socket now times out after 120 s of silence until a stream
  is set up; established sessions are unaffected.
- **Overlay observers piled up** (idea: pdmashkov/AirPlayer). Every return to the app re-bound the
  service and added 4 more state collectors that lived until the Activity was destroyed. The
  previous set is now cancelled first.
- **The surface provider was cleared on every `onStop`** (same source), forcing a decoder teardown and
  a keyframe wait even when the surface survived a short stop. It is now only cleared when the
  Activity is finishing.
- **`GET /info` reported the Android system name** (e.g. "Nokia Streaming Box 8010") instead of the
  configured/advertised name (idea: 2archiver/Hearth). It now returns the name mDNS registered.
- **RTSP header names are matched case-insensitively** (idea: 2archiver/Hearth). A lower-case
  `content-length` used to leave the body in the socket and desync the connection.
- **Zero-length mirror packets no longer end the mirror** (idea: 2archiver/Hearth). Type 2 is an empty
  heartbeat; `payloadSize == 0` was treated as corrupt and stopped the stream.
- **libalac is no longer fed once a legacy audio stream is muted** for a bad key (idea:
  prowsejeremy/PhairPlay) — garbage input can crash the native decoder.

### Changed
- **Mirror sessions show the sender's name** (e.g. "Anna's iPhone") from the SETUP plist instead of
  the generic "AirPlay" (idea: 2archiver/Hearth).
- **Audio-only playback (iPhone music: realtime ALAC / AAC-LC) is steadier** (idea:
  prowsejeremy/PhairPlay): ~200 ms pre-roll before `play()`, a 2× AudioTrack buffer and the playback
  thread at `THREAD_PRIORITY_URGENT_AUDIO`. Mirroring audio (AAC-ELD) keeps the minimum buffer and
  no pre-roll, so lip-sync is unchanged.

### CI
- The Android job now also runs `:app:test<Flavor>DebugUnitTest` (idea: Matej-Hajek/PhairPlayPhone).
  Those tests (MdnsService, NetworkUtils, …) are excluded from the JVM test-runner and were not run
  anywhere: `NetworkUtilsTest` no longer compiled since the deviceid change in mh.3, and
  `MdnsServiceTest` failed on a stubbed `NsdServiceInfo` and on mh.11's asynchronous restart. Both
  fixed.
- GitHub Actions bumped to their current majors — `actions/checkout@v7`, `actions/setup-java@v6`,
  `actions/upload-artifact@v7`, `android-actions/setup-android@v4` — clearing the Node.js 20 and
  setup-java v4 deprecation warnings on every job. No effect on the APK (JDK stays Temurin 17).
- Dependabot now watches `github-actions` weekly, so action updates arrive as PRs.

---

## [1.1.0-mh.11] - 2026-09-22

### Fixed
- **AirPlay could end up "Disabled" with the toggle on — and stay there.** Seen 2026-09-21 on a
  Google TV box: the receiver still listened on port 7000 and reported itself running, but the
  Home screen said "Disabled" and no iPhone could see it; only a reboot helped. That state is
  only reachable when an mDNS restart never completes (`stop()` emits Disabled, Advertising is
  emitted once *both* registrations confirm). Two gaps closed in `MdnsService`:
  - `_raop._tcp` now has the same stuck-probing watchdog as `_airplay._tcp`; a silent RAOP
    registration used to block Advertising forever.
  - `stop()`/`start()`/`restart()` are synchronized and the pending-unregistration count is set
    before callbacks can observe it. Before, a fast unregistration callback could complete a
    pending restart while `stop()` was still tearing down, and the tail of `stop()` then wiped
    the brand-new listeners and watchdog.
  - `registerService()` exceptions are caught and treated as a failed attempt instead of
    crashing the Handler thread they were thrown on.

### Added
- **Start-to-Advertising watchdog.** A start that has not reached "both registered" within 20 s
  is restarted (max 3×), then reported as Error — instead of sitting on Disabled indefinitely.
- **mDNS self-probe.** After both registrations confirm, the service browses `_airplay._tcp` for
  10 s and checks that its own name comes back over the wire. "Android says registered" turned
  out not to mean "senders can see us"; if the name is not seen, the advertisement is redone
  (max 2× per process, then the probe only logs so a platform that never shows its own
  services in discovery cannot make the name flap).
- **Service-level health check** every 5 min: AirPlay enabled but state Disabled/Error for
  more than 60 s → re-advertise (or restart the receivers when there is nothing to re-advertise);
  receiver missing → start it.
- **Unsigned release build in CI** (`release-build.yml`, manual/tag-triggered): produces both
  flavors as an artifact for local signing, so releases no longer require an x86_64 build host.

### Changed
- `phairplay.log` keeps 5000 lines (was 1000) and no longer stores VERBOSE lines; a single
  mirror session used to overwrite the whole file, which is why the 2026-09-21 incident left no
  trace. Trimming now has hysteresis instead of rewriting the file on every line once full.

---

## [1.1.0-mh.10] - 2026-08-28

### Added
- **In-app "Display over other apps" prompt.** Without `SYSTEM_ALERT_WINDOW` the service can't bring
  itself to the foreground on a TV, so mirroring decodes but never becomes visible (black screen /
  stuck on "mirroring"). The Home screen now shows a banner when the permission is missing, with a
  button that opens the system grant screen directly — for users who sideload over FTP and can't run
  the `appops` grant that the ADB install applies. The banner clears automatically once granted
  (re-checked on resume). Diagnosed from a field log in #15.

## [1.1.0-mh.9] - 2026-08-27

### Changed
- The persistent diagnostic log (`phairplay.log`) is now written to the app's **external**
  files directory (`/sdcard/Android/data/<package>/files/phairplay.log`) when available,
  instead of internal storage. This makes it retrievable over **FTP or a file manager without
  adb or root** — for users who sideload by FTP and cannot capture logcat. Falls back to
  internal storage when no external files dir is available. Requested in #15 to debug the
  Safari-fullscreen video crash. The `:8001` dump and `:8002` live-tail are unchanged.

---

## [1.1.0-mh.8] - 2026-08-27

### Fixed
- **A window you opened yourself is no longer backed out from under you when a session ends.** Since
  mh.4 the app steps aside with `moveTaskToBack` after a session, so a TV that was auto-opened for an
  incoming stream returns to whatever was on screen before. That is right for the auto-opened case, but
  its fallback heuristic — "no interaction seen, so we must have been auto-opened" — misread the one
  case where you launch the app from the launcher and then start casting without touching the TV remote
  again: `onUserInteraction` never fires, because the button press that launched the app went to the
  launcher, not to the app. Ending the stream then retreated the window the user had deliberately
  opened, leaving whatever was behind it — on a TV usually nothing, i.e. a **black screen**. A launch
  without `EXTRA_AUTO_OPENED` (which only the service ever sets) now counts as user intent, so it never
  retreats. Appliance behaviour is unchanged: that path always carries the extra. Reported by
  @sigurdshilfe on a Sony X90J.

---

## [1.1.0-mh.7] - 2026-08-26

Two receiver-reliability fixes, both from an upstream bug report that turned out to apply here
just as much: nothing in the app kept the screen awake during a session, and nothing brought the
mDNS advertisement back after the network dropped.

### Fixed
- **The TV's screensaver no longer interrupts an active session.** There was no
  `FLAG_KEEP_SCREEN_ON`, no wake lock and not even the `WAKE_LOCK` permission anywhere in the
  codebase, so any mirror, photo or audio session simply ran into the TV's own screensaver /
  power-saving timeout. The window flag is now held for as long as the overlay is up — mirroring,
  photos and audio-only now-playing all count as "in use" — and cleared when it comes down.
  `FLAG_KEEP_SCREEN_ON` rather than a `PowerManager` wake lock: no permission needed, scoped to
  this window, and Android releases it by itself if the activity dies, so a crash can never leave
  the panel burning.
- **The receiver becomes discoverable again after the TV wakes from standby.** Standby takes the
  Wi-Fi interface down, an `NsdManager` registration does not survive that, and the app had no
  path back: on wake it was still listening on port 7000, still reported itself as running, and
  was invisible in every AirPlay picker until it was restarted by hand. A
  `ConnectivityManager.NetworkCallback` now re-registers the advertisement whenever a network
  becomes available, via a new `AirPlayReceiver.readvertise()` that restarts only mDNS and leaves
  the RTSP and timing sockets alone.
- **Wi-Fi may no longer filter away mDNS queries.** `CHANGE_WIFI_MULTICAST_STATE` had been in the
  manifest from the start but nothing ever used it. `MdnsService` now holds a `MulticastLock` for
  as long as it advertises, so power-save cannot drop the multicast frames that carry every
  sender's query. The lock is deliberately kept across a `restart()`, which would otherwise open a
  pointless window where queries are filtered again.

---

## [1.1.0-mh.6] - 2026-07-20

Cosmetic fix so the app's status screen matches what senders see.

### Fixed
- **Home screen shows the actual advertised name.** The "Visible as: …" label read the system device
  name (e.g. "Nokia Streaming Box 8010") instead of the configured display name, so it disagreed with
  what senders actually see. It now shows `effectiveDisplayName` (falling back to the system name only
  when unset) and updates live on a rename. (`WaitingScreen` had the same bug but is unused/dead code.)

---

## [1.1.0-mh.5] - 2026-07-20

Makes the mh.4 receiver defaults actually take effect at runtime, and gets CI green.

### Fixed
- **The mh.4 default-receiver change now actually takes effect.** `SettingsRepository.toAppSettings()`
  had its own hardcoded fallbacks (`?: true`/`?: false`) for unset preference keys, which silently
  overrode the `AppSettings` data-class defaults — so on a fresh install Miracast/Cast were still on
  and start-on-boot still off despite mh.4. Fallbacks now come from `AppSettings.DEFAULT` (one source
  of truth), so the intended defaults (AirPlay-only, start-on-boot on) apply at runtime.
- **CI green again.** Android Lint failed the build on `ExportedReceiver` for the new
  `DisplayNameReceiver` (exported without a permission). It's intentionally exported (adb-reachable,
  benign rename-only), so the check is suppressed on that element with `tools:ignore`. Also excluded
  the receiver from the JVM test-runner and updated `AppSettingsTest` to the new defaults.

---

## [1.1.0-mh.4] - 2026-07-20

Appliance polish ahead of the multi-TV rollout, plus fork documentation.

### Fixed
- **The app steps aside after a session even when it was already foregrounded at connect.**
  `moveTaskToBack` on session end depended on a flag set only from the `EXTRA_AUTO_OPENED` intent, but
  when the app is already at the front `FLAG_ACTIVITY_REORDER_TO_FRONT` delivers no `onNewIntent`, so
  the app stayed on screen after the sender disconnected. Now a session that starts while the user
  isn't actively using the app is treated as opened-for-session (retreat on end); real remote/touch
  input marks the stint as user-driven so the app isn't yanked away from someone using it.

### Changed
- **Default receivers tuned for these TVs: Miracast off, Cast off, Start-on-boot on.** Miracast's
  Wi-Fi Direct permission isn't granted on Google TV / Fire TV (it always errored), and the devices
  have Chromecast built in so PhairPlay's Cast receiver is redundant; a receiver appliance should
  also advertise again after a reboot. Applies to fresh installs; existing installs keep their values.

### Docs
- README gained an "About this fork" section: personal fork for home use only (no support/warranty),
  the fork's feature list, and a note on why YouTube can't be screen-mirrored.

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
