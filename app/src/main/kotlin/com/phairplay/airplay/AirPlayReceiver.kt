package com.phairplay.airplay

import android.content.Context
import android.view.Surface
import com.phairplay.airplay.handshake.AirPlayNtpClient
import com.phairplay.airplay.handshake.AudioStreamServer
import com.phairplay.airplay.handshake.BufferedAudioServer
import com.phairplay.airplay.handshake.MirrorStreamServer
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.ServerSocket
import java.net.Socket

/**
 * AirPlayReceiver — Top-level orchestrator for the AirPlay 2 receiver pipeline.
 *
 * WHY: Coordinates all AirPlay components into a single lifecycle:
 * - [MdnsService]: mDNS advertising (makes device visible in sender pickers)
 * - [RtspHandler]: RTSP handshake (OPTIONS → ANNOUNCE → SETUP → RECORD)
 * - [VideoDecoder]: H.264 hardware decode via MediaCodec → SurfaceView
 * - [AudioPlayer]: AES-128-CTR decrypt + AAC/ALAC decode → AudioTrack
 *
 * HOW: [PhairPlayService] creates this receiver and calls [start]/[stop].
 * The pipeline activates lazily — VideoDecoder and AudioPlayer are created
 * only after RECORD is received, when [SessionDescription] is available.
 *
 * For audio-only streams (music, podcasts), only [AudioPlayer] is started —
 * no [VideoDecoder] and no fullscreen streaming surface is needed.
 *
 * State changes are reported via [onStateChanged] to [PhairPlayService].
 *
 * Example:
 *   val receiver = AirPlayReceiver(
 *       context = context,
 *       displayName = settings.effectiveDisplayName,
 *       videoSurfaceProvider = { streamingScreen.getSurface() },
 *       onStateChanged = { state -> /* update UI */ }
 *   )
 *   receiver.start()
 *   receiver.stop()
 */
class AirPlayReceiver(
    private val context: Context,
    /** User-configured display name from Settings (blank = use system device name). */
    private val displayName: String = "",
    /** Advertised mirroring resolution (from the "high resolution" setting). */
    private val mirrorWidth: Int = 1920,
    private val mirrorHeight: Int = 1080,
    /** Whether to accept the mirroring audio stream (experimental — see AppSettings.mirrorAudioEnabled). */
    private val audioEnabled: Boolean = false,
    /** Require HomeKit-style SRP PIN pairing before streaming (AppSettings.airPlayPinAuthEnabled). */
    private val pinAuthEnabled: Boolean = false,
    /** Lazy Surface provider — called only for video streams when RECORD arrives. */
    private val videoSurfaceProvider: () -> Surface?,
    private val onStateChanged: (ProtocolState) -> Unit,
    /**
     * Called with the sender name when a streaming session starts (RECORD received).
     *
     * The name is extracted from the RTSP `User-Agent` header. The caller
     * ([PhairPlayService]) uses this to update the [ActiveConnection] and notification
     * text with the real sender identifier instead of the generic "AirPlay Sender".
     *
     * Guaranteed to be called BEFORE [onStateChanged] is called with [ProtocolState.CONNECTED].
     */
    private val onSenderNameChanged: (String) -> Unit = {},
    /** Called when iOS/macOS sends a JPEG/PNG to the `/photo` endpoint. */
    private val onPhotoReceived: (bytes: ByteArray, imageType: PhotoImageType) -> Unit = { _, _ -> },
    /** Called when iOS/macOS clears the currently displayed `/photo`. */
    private val onPhotoCleared: () -> Unit = {},
    /**
     * Called with the actual mDNS-registered name after [start].
     *
     * The name may differ from [displayName] if another device on the network already uses
     * the same name — NsdManager resolves the collision by appending " (2)", " (3)", etc.
     * The UI can use this callback to show the user the real registered name.
     */
    private val onActualNameRegistered: (String) -> Unit = {},
    /**
     * Audio-only "now playing" state. Emits a [NowPlayingInfo] when audio is streaming WITHOUT video
     * (system audio, Apple Music, podcasts) so the UI can show a now-playing card instead of a black
     * surface; emits null when video is mirroring (the video screen takes over) or audio stops.
     */
    private val onNowPlayingChanged: (NowPlayingInfo?) -> Unit = {},
    /** Pairing PIN to show ([pin]) or hide (null) on the TV during SRP pair-setup. */
    private val onPinChanged: (pin: String?) -> Unit = {},
    /**
     * Where [onStateChanged] runs. Main in production (Android UI rule); tests pass an inline
     * dispatcher. Must preserve submission order — every state goes through it in sequence.
     */
    private val stateDispatcher: CoroutineDispatcher = Dispatchers.Main
) {

    // Persistent store of paired controllers (for PIN access control / pair-verify).
    private val pairingStore = com.phairplay.airplay.handshake.PairingStore(context)

    // SupervisorJob: child coroutine failures don't propagate to siblings.
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.IO + job)

    // Child components
    private var mdnsService: MdnsService? = null
    private var rtspHandler: RtspHandler? = null
    private var timingHandler: TimingHandler? = null
    private var videoDecoder: VideoDecoder? = null
    private var audioPlayer: AudioPlayer? = null

    // UDP socket for receiving audio RTP packets — opened after RECORD, closed on TEARDOWN
    @Volatile private var audioSocket: DatagramSocket? = null

    // AirPlay 2 mirroring: data stream server + event channel + keys (set during SETUP).
    @Volatile private var mirrorServer: MirrorStreamServer? = null
    @Volatile private var audioServer: AudioStreamServer? = null
    @Volatile private var bufferedAudioServer: BufferedAudioServer? = null
    @Volatile private var urlVideoPlayer: AirPlayVideoPlayer? = null

    // Reverse remote control (TV → sender). Created lazily once a sender advertises DACP-ID.
    private val dacpClient = DacpClient(context)
    @Volatile private var ntpClient: AirPlayNtpClient? = null
    @Volatile private var eventSocket: ServerSocket? = null
    @Volatile private var eventClientSocket: java.net.Socket? = null
    @Volatile private var mirrorAesKey: ByteArray? = null
    @Volatile private var mirrorEcdhSecret: ByteArray? = null
    @Volatile private var mirrorAesIv: ByteArray? = null

    // ─── Now-playing (audio-only) state ──────────────────────────────────────
    // The now-playing card shows only when audio plays WITHOUT video. We track both stream kinds
    // plus the latest DMAP metadata/artwork and recompute on every change (see [emitNowPlaying]).
    @Volatile private var audioPlaying = false
    @Volatile private var videoPlaying = false

    // ─── Session state (receiver-owned) ──────────────────────────────────────
    // One lock serialises every session start and teardown. TEARDOWN of the last stream and the
    // subsequent socket close both call onStreamingStopped; only the call that flips [sessionActive]
    // from true to false emits ADVERTISING and restarts mDNS — a second, concurrent mDNS restart
    // races the first one's fresh registration and escalates the name to "(3)"/"(4)" (mh.2).
    // Until mh.15 the guard was a one-way flag that only the legacy RECORD path reset, so from the
    // second mirror / AirPlay 2 audio session after a service start the teardown was a no-op: the
    // UI stayed on CONNECTED (black screen, Back swallowed) and every session's NTP client + event
    // socket leaked. Every session start now re-arms it.
    private val sessionLock = Any()
    @Volatile private var sessionActive = false
    // Bumped on every session start, so a media pipeline launched for an older session can tell it
    // has been superseded before it builds anything.
    @Volatile private var sessionGeneration = 0
    // Address of the sender of the current session (null = unknown → accept everything). Media
    // sockets drop packets / connections from any other host.
    @Volatile private var sessionPeer: java.net.InetAddress? = null
    @Volatile private var npSenderName = "AirPlay"
    // Name mDNS actually registered (may carry a " (2)" collision suffix); GET /info must match it.
    @Volatile private var registeredName: String? = null
    @Volatile private var npTitle: String? = null
    @Volatile private var npArtist: String? = null
    @Volatile private var npAlbum: String? = null
    @Volatile private var npArtwork: ByteArray? = null

    /**
     * Starts the AirPlay receiver.
     *
     * 1. Starts mDNS advertising with the configured display name.
     * 2. Opens the RTSP server socket (port 7000).
     * 3. Emits [ProtocolState.ADVERTISING] once both mDNS services are registered.
     *
     * Non-blocking — all network work runs in background coroutines.
     */
    fun start() {
        Logger.i("AirPlayReceiver starting (displayName='$displayName')")
        scope.launch {
            try {
                startTimingHandler()
                startMdnsService()
                startRtspHandler()
            } catch (e: Exception) {
                Logger.e("Failed to start AirPlayReceiver", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Stops the AirPlay receiver and releases all resources.
     *
     * Stops RTSP handler, mDNS advertising, video decoder, and audio player.
     * Cancels all background coroutines.
     *
     * MUST be called when [PhairPlayService] stops or is destroyed.
     */
    fun stop() {
        Logger.i("AirPlayReceiver stopping")
        try {
            rtspHandler?.stop()
            timingHandler?.stop()
            mdnsService?.stop()
            dacpClient.stop()
            synchronized(sessionLock) {
                sessionActive = false
                releaseMediaComponents()
                releaseSessionKeys()
            }
        } catch (e: Exception) {
            Logger.e("Error during AirPlayReceiver stop", e)
        } finally {
            scope.cancel()
        }
    }

    /**
     * Sends a DACP transport command (see [DacpClient] constants) from the TV remote back to the
     * AirPlay sender — e.g. play/pause or skip what the Mac/iPhone is streaming. No-op if no sender
     * has advertised a DACP identity yet.
     */
    fun sendRemoteCommand(command: String) = dacpClient.sendCommand(command)

    /** True once a sender has advertised DACP reverse-control (so the TV remote can drive playback). */
    fun isRemoteControlAvailable(): Boolean = dacpClient.isAvailable

    /**
     * Re-registers the mDNS advertisement without touching the RTSP/timing servers.
     *
     * For when the network came back under us — the TV waking from standby being the case that
     * prompted this. An NsdManager registration does not survive its interface going down, and
     * nothing brings it back on its own, so the receiver keeps listening on port 7000 while
     * being invisible in every AirPlay picker. Restarting the whole receiver would do it too,
     * but that needlessly drops the sockets; only the advertisement is stale.
     *
     * No-op when the receiver was never started.
     *
     * @return false when there is no mDNS service to restart (receiver never got that far), so
     *   the caller can escalate to a full receiver restart instead.
     */
    fun readvertise(): Boolean {
        val mdns = mdnsService ?: return false
        Logger.i("Re-advertising AirPlay over mDNS")
        scope.launch {
            try {
                mdns.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to re-advertise mDNS", e)
            }
        }
        return true
    }

    /**
     * Tears down the current session, if any, for a sender that is gone without having closed the
     * connection (#19). Safe to call at any time; a no-op without an active session.
     */
    fun dropSession(reason: String): Boolean = rtspHandler?.dropActiveSession(reason) ?: false

    // ─── Private: startup ────────────────────────────────────────────────────

    private fun startTimingHandler() {
        timingHandler = TimingHandler().also { it.start(scope) }
        Logger.d("Timing handler started on UDP port ${TimingHandler.TIMING_PORT}")
    }

    private fun startMdnsService() {
        mdnsService = MdnsService(
            context = context,
            // mDNS reports the advertisement, not the session: while a session runs, its
            // DISABLED/ADVERTISING (from the per-session restart, readvertise() or the self-healing
            // watchdogs) must not overwrite CONNECTED — that used to drop the streaming screen and
            // move the app to the back mid-mirror. The session end emits the right state itself.
            onStateChange = { state -> emitState(state, fromMdns = true) },
            onActualNameRegistered = { actualName ->
                registeredName = actualName
                onActualNameRegistered(actualName)
            }
        ).also { it.start(displayName.ifBlank { null }) }
        Logger.d("mDNS service started")
    }

    private fun startRtspHandler() {
        rtspHandler = RtspHandler(
            context = context,
            displayWidth = mirrorWidth,
            displayHeight = mirrorHeight,
            audioEnabled = audioEnabled,
            videoSurfaceProvider = videoSurfaceProvider,
            onStreamingStarted = { session -> onStreamingStarted(session) },
            onStreamingStopped = { keepKeys -> onStreamingStopped(keepSessionKeys = keepKeys) },
            onPhotoReceived = { bytes, imageType -> onPhotoReceived(bytes, imageType) },
            onPhotoCleared = { onPhotoCleared() },
            onMirrorSetupKeys = { aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort, senderName ->
                startMirrorKeys(aesKey, ecdhSecret, aesIv, remoteAddr, senderTimingPort, senderName)
            },
            receiverNameProvider = {
                registeredName ?: displayName.ifBlank { com.phairplay.util.NetworkUtils.getDeviceName(context) }
            },
            onMirrorStreamStart = { streamConnectionId -> startMirrorStream(streamConnectionId) },
            onMirrorAudioStart = { sampleRate, channels, ct, spf -> startMirrorAudio(sampleRate, channels, ct, spf) },
            onMirrorAudioStop = { stopMirrorAudio() },
            onMirrorVideoStop = { stopMirrorVideo() },
            onBufferedAudioStart = { startBufferedAudio() },
            onBufferedAudioStop = { stopBufferedAudio() },
            onAudioFlush = { nextSeq -> audioServer?.flush(nextSeq) },
            onVolume = { v -> audioServer?.setVolume(v) },
            onNowPlayingMetadata = { title, artist, album ->
                npTitle = title; npArtist = NowPlayingInfo.cleanArtist(artist); npAlbum = album
                emitNowPlaying()
            },
            onArtwork = { bytes ->
                npArtwork = bytes.takeIf { it.isNotEmpty() }
                emitNowPlaying()
            },
            onVideoPlay = { url, start -> startUrlVideo(url, start) },
            onVideoRate = { rate -> urlVideoPlayer?.setRate(rate) },
            onVideoScrub = { pos -> urlVideoPlayer?.scrub(pos) },
            onVideoStop = { stopUrlVideo() },
            onPlaybackInfo = { urlVideoPlayer?.info() },
            onRemoteControlInfo = { dacpId, activeRemote -> dacpClient.configure(dacpId, activeRemote) },
            pinAuthEnabled = pinAuthEnabled,
            pairingStore = pairingStore,
            onShowPin = { pin -> onPinChanged(pin) }
        ).also { it.start(scope) }
        Logger.i("RTSP handler started on port 7000 (audioEnabled=$audioEnabled pinAuth=$pinAuthEnabled)")
    }

    // ─── Private: streaming lifecycle ────────────────────────────────────────

    /**
     * Marks a session as running (or keeps it running) and remembers its sender address. Under
     * [sessionLock]. Returns the new session generation.
     */
    private fun activateSessionLocked(peer: java.net.InetAddress?): Int {
        sessionActive = true
        if (peer != null) sessionPeer = peer
        return ++sessionGeneration
    }

    /**
     * Called by [RtspHandler] when RECORD is received and [SessionDescription] is ready.
     *
     * Wires the media pipeline:
     * - video stream: creates [VideoDecoder] + wires [RtspHandler.onVideoNalUnit]
     * - audio stream: creates [AudioPlayer]
     * - audio-only:   only [AudioPlayer], app stays on HomeScreen
     */
    private fun onStreamingStarted(session: SessionDescription) {
        val generation = synchronized(sessionLock) {
            // A repeated RECORD (resume after PAUSE) used to stack a second AudioPlayer and a second
            // UDP receiver on top of the first (the bind failed, the old player leaked). Replace them.
            releaseLegacyPipelineLocked()
            activateSessionLocked(rtspHandler?.peerAddress)
        }
        Logger.i("Streaming started — video=${session.hasVideo} audio=${session.hasAudio} " +
                 "audioOnly=${session.isAudioOnly}")

        scope.launch {
            try {
                synchronized(sessionLock) {
                    // Superseded (session ended or a newer one started) before we got to run.
                    if (generation != sessionGeneration || !sessionActive) return@launch
                    if (session.hasVideo) startVideoDecoder(session)
                    if (session.hasAudio) startAudioPlayer(session)
                }
                // Legacy (SDP) session: reflect its stream kinds into now-playing state so an
                // audio-only RAOP session shows the now-playing card.
                npSenderName = session.senderName.ifBlank { npSenderName }
                videoPlaying = session.hasVideo
                audioPlaying = session.hasAudio
                emitNowPlaying()
                // Notify PhairPlayService of the sender name BEFORE emitting CONNECTED,
                // so the name is ready when the ActiveConnection is created.
                onSenderNameChanged(session.senderName)
                emitState(ProtocolState.CONNECTED)
            } catch (e: Exception) {
                Logger.e("Failed to start media pipeline", e)
                emitState(ProtocolState.ERROR)
            }
        }
    }

    /**
     * Called when streaming ends: TEARDOWN (of the session or of its last stream), the control
     * connection closing, or URL video ending.
     *
     * Releases media components and re-advertises so the device reappears in sender pickers
     * immediately. Idempotent and serialised: only the call that actually ends a running session
     * emits ADVERTISING and restarts mDNS.
     *
     * @param keepSessionKeys true when only the last *stream* went away (stream-level TEARDOWN):
     *   media stops, but the FairPlay/ECDH keys, NTP client and event channel stay until the session
     *   TEARDOWN or the control connection closes — so a re-SETUP of stream 96/110 on the same
     *   connection still works. false = full teardown.
     */
    private fun onStreamingStopped(keepSessionKeys: Boolean = false) {
        val wasActive = synchronized(sessionLock) {
            val active = sessionActive
            sessionActive = false
            sessionGeneration++
            releaseMediaComponents()
            if (!keepSessionKeys) releaseSessionKeys()
            active
        }
        if (!wasActive) {
            if (!keepSessionKeys) Logger.d("Session resources released (no session was running)")
            return
        }
        Logger.i("Streaming stopped — media released" + if (keepSessionKeys) " (session keys kept)" else "")
        emitState(ProtocolState.ADVERTISING)

        scope.launch {
            try {
                mdnsService?.restart(displayName.ifBlank { null })
            } catch (e: Exception) {
                Logger.e("Failed to restart mDNS after streaming", e)
            }
        }
    }

    /**
     * A stream is being (re-)added on a live connection. When the session had ended with its last
     * stream (keys kept), this brings it back: CONNECTED again so the streaming screen returns.
     * Under [sessionLock].
     */
    private fun ensureSessionActiveLocked() {
        if (sessionActive) return
        activateSessionLocked(null)
        Logger.i("Stream re-added on the same connection — session active again")
        onSenderNameChanged(npSenderName)
        emitState(ProtocolState.CONNECTED)
    }

    // ─── Private: media pipeline ──────────────────────────────────────────────

    /**
     * Initializes [VideoDecoder] with SPS/PPS from the [SessionDescription].
     *
     * Resolution hint: AirPlay SDP does not include width/height — the actual
     * resolution is embedded in the SPS NAL unit. We pass [DEFAULT_VIDEO_WIDTH] ×
     * [DEFAULT_VIDEO_HEIGHT] as a hint; MediaCodec reads the real size from SPS.
     *
     * [RtspHandler.onVideoNalUnit] is wired here so RTP interleaved NAL units
     * flow directly into [VideoDecoder.decodeNalUnit].
     */
    private fun startVideoDecoder(session: SessionDescription) {
        val surface = videoSurfaceProvider() ?: run {
            Logger.w("VideoDecoder: no surface available — skipping video pipeline")
            return
        }
        val sps = session.spsBytes ?: run {
            Logger.w("VideoDecoder: no SPS in SDP — skipping")
            return
        }
        val pps = session.ppsBytes ?: run {
            Logger.w("VideoDecoder: no PPS in SDP — skipping")
            return
        }

        videoDecoder = VideoDecoder(surface).also { decoder ->
            decoder.initialize(sps, pps, DEFAULT_VIDEO_WIDTH, DEFAULT_VIDEO_HEIGHT)
            rtspHandler?.onVideoNalUnit = { nalUnit, ptsUs ->
                decoder.decodeNalUnit(nalUnit, ptsUs)
            }
        }
        Logger.i("VideoDecoder started (${DEFAULT_VIDEO_WIDTH}x${DEFAULT_VIDEO_HEIGHT} hint)")
    }

    /**
     * Initializes [AudioPlayer] with codec and encryption params from [SessionDescription].
     *
     * When the SDP contains no AES key/IV (unencrypted or missing keys), null is passed —
     * [AudioPlayer.initialize] skips cipher setup entirely and writes audio payload directly.
     * This prevents a zero-key cipher from producing garbage audio (S6-4 fix).
     */
    private fun startAudioPlayer(session: SessionDescription) {
        audioPlayer = AudioPlayer().also { player ->
            player.initialize(
                aesKey     = session.aesKey.takeIf { session.isAudioEncrypted },
                aesIv      = session.aesIv.takeIf  { session.isAudioEncrypted },
                sampleRate = session.sampleRate,
                channels   = session.channels,
                codec      = session.audioCodec,
                alacFramesPerPacket = session.alacFramesPerPacket
            )
        }
        Logger.i("AudioPlayer started (${session.sampleRate}Hz × ${session.channels}ch, " +
                 "codec=${session.audioCodec}, encrypted=${session.isAudioEncrypted})")

        startAudioUdpReceiver()
    }

    /**
     * Opens a UDP socket on [AUDIO_RTP_PORT] and feeds every received packet to
     * [AudioPlayer.playAudioPacket].
     *
     * WHY UDP: AirPlay audio is sent as RTP over UDP — low latency is more important
     * than guaranteed delivery. A missing packet produces a brief audio glitch,
     * which is far less disruptive than the buffering delays that TCP would introduce.
     *
     * The socket is closed in [releaseMediaComponents] when streaming ends.
     */
    private fun startAudioUdpReceiver() {
        scope.launch(Dispatchers.IO) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket(AUDIO_RTP_PORT)
                audioSocket = socket
                Logger.i("Audio UDP receiver listening on port $AUDIO_RTP_PORT")

                val buf    = ByteArray(MAX_AUDIO_PACKET_BYTES)
                val packet = DatagramPacket(buf, buf.size)

                while (isActive) {
                    packet.length = buf.size
                    socket.receive(packet)
                    // copyOf trims to actual packet length before passing to the player
                    audioPlayer?.playAudioPacket(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                // SocketException thrown when the socket is closed by releaseMediaComponents — expected
                if (socket != null && audioSocket === socket) {
                    Logger.e("Audio UDP receiver error (unexpected)", e)
                } else {
                    Logger.d("Audio socket closed (expected during shutdown)")
                }
            }
        }
    }

    // ─── Private: AirPlay 2 mirroring ─────────────────────────────────────────

    /**
     * Mirror SETUP msg 1: stash the decrypted AES key + pairing secret, open the event
     * channel (macOS connects to it), and switch the UI to the streaming surface.
     * @return the event channel's TCP port.
     */
    private fun startMirrorKeys(
        aesKey: ByteArray,
        ecdhSecret: ByteArray,
        aesIv: ByteArray,
        remoteAddress: java.net.InetAddress,
        senderTimingPort: Int,
        senderName: String? = null,
    ): Pair<Int, Int> = synchronized(sessionLock) {
        senderName?.let { npSenderName = it }
        // A repeated key message must not stack a second NTP client / event socket on the first.
        closeTimingAndEventLocked()
        mirrorAesKey = aesKey
        mirrorEcdhSecret = ecdhSecret
        mirrorAesIv = aesIv
        activateSessionLocked(remoteAddress)
        val event = ServerSocket(0)
        eventSocket = event
        // Accept + drain the event connection. We don't act on events yet, but macOS expects
        // the advertised event port to be connectable, so keep it open and readable.
        scope.launch(Dispatchers.IO) {
            try {
                event.accept().use { s ->
                    eventClientSocket = s
                    Logger.i("Event channel: macOS connected from ${s.inetAddress.hostAddress}")
                    val buf = ByteArray(4096)
                    val input = s.getInputStream()
                    while (isActive && input.read(buf) != -1) { /* drain */ }
                }
            } catch (e: Exception) {
                if (eventSocket === event) Logger.d("Event channel closed")
            } finally {
                eventClientSocket = null
            }
        }
        // AirPlay 2 NTP is receiver-initiated: poll the sender's timing port so macOS proceeds.
        val ntp = AirPlayNtpClient(remoteAddress, senderTimingPort, onSenderSilent = {
            dropSession("sender stopped answering NTP timing requests")
        }).also { ntpClient = it; it.start(scope) }
        onSenderNameChanged(senderName ?: "AirPlay")
        emitState(ProtocolState.CONNECTED)
        Logger.i("Mirror keys set (sender='${senderName ?: "?"}'); eventPort=${event.localPort} timingPort=${ntp.localPort}")
        event.localPort to ntp.localPort
    }

    /**
     * Mirror SETUP msg 2: start the data-stream server for the requested stream.
     * @return the data server's TCP port (macOS connects here to send H.264).
     */
    private fun startMirrorStream(streamConnectionId: Long): Int = synchronized(sessionLock) {
        val aesKey = mirrorAesKey ?: run { Logger.e("mirror stream start before keys set"); return 0 }
        val ecdhSecret = mirrorEcdhSecret ?: return 0
        mirrorServer?.stop()                     // never two data servers for one session
        ensureSessionActiveLocked()
        MirrorStreamServer(aesKey, ecdhSecret, streamConnectionId, videoSurfaceProvider, mirrorWidth, mirrorHeight)
            .also { mirrorServer = it; it.start(scope); videoPlaying = true; emitNowPlaying() }
            .dataPort
            .also { Logger.i("Mirror data server started on port $it") }
    }

    /** Mirror SETUP audio stream (type 96): start the AAC-ELD / AAC-LC / ALAC audio server. @return (dataPort, controlPort). */
    private fun startMirrorAudio(sampleRate: Int, channels: Int, codecType: Int, framesPerPacket: Int): Pair<Int, Int> =
        synchronized(sessionLock) {
            val aesKey = mirrorAesKey ?: run { Logger.e("audio start before keys set"); return 0 to 0 }
            val ecdhSecret = mirrorEcdhSecret ?: return 0 to 0
            val aesIv = mirrorAesIv ?: return 0 to 0
            audioServer?.stop()                  // never two audio servers for one session
            ensureSessionActiveLocked()
            val server = AudioStreamServer(aesKey, ecdhSecret, aesIv, sampleRate, channels, codecType, framesPerPacket)
                .also { audioServer = it; it.start(scope) }
            audioPlaying = true
            emitNowPlaying()
            Logger.i("Mirror audio server started: dataPort=${server.dataPort} controlPort=${server.controlPort}")
            server.dataPort to server.controlPort
        }

    /** Stops ONLY the mirror audio stream (macOS dynamic-stream TEARDOWN) — video keeps running. */
    private fun stopMirrorAudio() = synchronized(sessionLock) {
        audioServer?.stop()
        audioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Mirror audio stream stopped (video mirroring continues)")
    }

    /** Stops ONLY the mirror video stream (macOS dynamic-stream TEARDOWN) — audio keeps playing. */
    private fun stopMirrorVideo() = synchronized(sessionLock) {
        mirrorServer?.stop()
        mirrorServer = null
        videoPlaying = false
        emitNowPlaying()   // audio may still be playing → now-playing card can take over
        Logger.i("Mirror video stream stopped (audio playback continues)")
    }

    /**
     * AirPlay video URL mode (non-mirroring): show the streaming surface and hand the URL to
     * [AirPlayVideoPlayer], which fetches + plays it via MediaPlayer onto the same Surface.
     */
    private fun startUrlVideo(url: String, startFraction: Double) {
        val player = synchronized(sessionLock) {
            activateSessionLocked(rtspHandler?.peerAddress)
            onSenderNameChanged("AirPlay")
            emitState(ProtocolState.CONNECTED)   // shows StreamingScreen → Surface becomes available
            urlVideoPlayer ?: AirPlayVideoPlayer(
                surfaceProvider = videoSurfaceProvider,
                onEnded = { stopUrlVideo() }
            ).also { urlVideoPlayer = it }
        }
        player.play(url, startFraction)
        Logger.i("AirPlay URL video started: $url (start=$startFraction)")
    }

    /** Stops AirPlay video URL playback (POST /stop or end-of-media) and ends the session. */
    private fun stopUrlVideo() {
        synchronized(sessionLock) {
            urlVideoPlayer?.release()
            urlVideoPlayer = null
        }
        onStreamingStopped()
        Logger.i("AirPlay URL video stopped")
    }

    /** Starts the AirPlay 2 buffered audio-only stream (type 103, Apple Music → TV); returns its TCP port. */
    private fun startBufferedAudio(): Int = synchronized(sessionLock) {
        bufferedAudioServer?.stop()
        ensureSessionActiveLocked()
        val server = BufferedAudioServer().also { bufferedAudioServer = it; it.start(scope) }
        audioPlaying = true   // buffered audio (type 103) is always audio-only
        emitNowPlaying()
        Logger.i("Buffered audio server started: dataPort=${server.dataPort}")
        server.dataPort
    }

    /** Stops the buffered audio-only stream (type 103 TEARDOWN). */
    private fun stopBufferedAudio() = synchronized(sessionLock) {
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        audioPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
        Logger.i("Buffered audio stream stopped")
    }

    /** Legacy (SDP) pipeline only: video decoder, AudioPlayer and its UDP socket. Under [sessionLock]. */
    private fun releaseLegacyPipelineLocked() {
        rtspHandler?.onVideoNalUnit = null
        try { audioSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        audioSocket = null
        videoDecoder?.release()
        videoDecoder = null
        audioPlayer?.release()
        audioPlayer = null
    }

    /** NTP client + event channel of the AirPlay 2 session. Under [sessionLock]. */
    private fun closeTimingAndEventLocked() {
        ntpClient?.stop()
        ntpClient = null
        try { eventClientSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventClientSocket = null
        try { eventSocket?.close() } catch (e: Exception) { /* non-fatal */ }
        eventSocket = null
    }

    /**
     * Clears the FairPlay/ECDH keys, the NTP client and the event channel — only on a FULL teardown
     * (session TEARDOWN or control-connection close). A per-stream teardown (stopMirrorAudio /
     * stopMirrorVideo) and the removal of the LAST stream keep them, so macOS/iOS can re-add a stream
     * on the same live session without re-sending keys. Clearing here prevents a brand-new control
     * connection from reusing a previous session's stale keys. Under [sessionLock].
     */
    private fun releaseSessionKeys() {
        closeTimingAndEventLocked()
        mirrorAesKey = null
        mirrorEcdhSecret = null
        mirrorAesIv = null
        sessionPeer = null
    }

    /** Stops every media stream/player (not the session keys — see [releaseSessionKeys]). */
    private fun releaseMediaComponents() {
        releaseLegacyPipelineLocked()
        mirrorServer?.stop()
        mirrorServer = null
        audioServer?.stop()
        audioServer = null
        bufferedAudioServer?.stop()
        bufferedAudioServer = null
        urlVideoPlayer?.release()
        urlVideoPlayer = null
        // Session fully torn down — clear now-playing so the UI leaves the audio card.
        audioPlaying = false
        videoPlaying = false
        clearNowPlayingMetadata()
        emitNowPlaying()
    }

    /** Pushes the current now-playing state out: a [NowPlayingInfo] when audio plays without video, else null. */
    private fun emitNowPlaying() {
        val show = audioPlaying && !videoPlaying
        onNowPlayingChanged(
            if (show) NowPlayingInfo(npSenderName, npTitle, npArtist, npAlbum, npArtwork) else null
        )
    }

    /** Drops stale track metadata/artwork when an audio stream ends (so it can't bleed into the next). */
    private fun clearNowPlayingMetadata() {
        npTitle = null; npArtist = null; npAlbum = null; npArtwork = null
    }

    // ─── Private: state emission ─────────────────────────────────────────────

    /**
     * Dispatches [state] on [stateDispatcher] (Main: Android UI rule), in submission order.
     *
     * [fromMdns] states describe the advertisement, not the session: they are dropped while a
     * session is running so they can never replace CONNECTED. The check runs when the state is
     * delivered, so a late mDNS event queued before a session start is still overridden by the
     * CONNECTED that follows it, and one delivered during the session is ignored.
     */
    private fun emitState(state: ProtocolState, fromMdns: Boolean = false) {
        scope.launch(stateDispatcher) {
            if (fromMdns && sessionActive) {
                Logger.d("mDNS state $state while a session is active — UI stays CONNECTED")
                return@launch
            }
            onStateChanged(state)
        }
    }

    // ─── Test seams ──────────────────────────────────────────────────────────

    internal fun startMirrorKeysForTest(remote: java.net.InetAddress, senderTimingPort: Int, name: String?) =
        startMirrorKeys(ByteArray(16), ByteArray(32), ByteArray(16), remote, senderTimingPort, name)
    internal fun startMirrorAudioForTest(ct: Int) = startMirrorAudio(44100, 2, ct, 352)
    internal fun startMirrorStreamForTest() = startMirrorStream(1L)
    internal fun onStreamingStoppedForTest(keepKeys: Boolean) = onStreamingStopped(keepKeys)
    internal fun emitMdnsStateForTest(state: ProtocolState) = emitState(state, fromMdns = true)
    internal val hasNtpClient: Boolean get() = ntpClient != null
    internal val hasEventSocket: Boolean get() = eventSocket != null
    internal val hasSessionKeys: Boolean get() = mirrorAesKey != null
    internal val isSessionActive: Boolean get() = sessionActive

    companion object {
        // Hint dimensions for MediaCodec configuration.
        // Real resolution is encoded in the H.264 SPS NAL unit.
        private const val DEFAULT_VIDEO_WIDTH  = 1920
        private const val DEFAULT_VIDEO_HEIGHT = 1080

        /**
         * UDP port for receiving audio RTP packets.
         * Advertised in the RTSP SETUP response so the sender knows where to send audio.
         * Must not conflict with the RTSP port (7000) or timing port ([TimingHandler.TIMING_PORT]).
         */
        internal const val AUDIO_RTP_PORT = 6001

        /**
         * Maximum UDP audio packet size in bytes.
         * ALAC frames are typically ≤ 8 KB. 16 KB is a safe upper bound.
         */
        private const val MAX_AUDIO_PACKET_BYTES = 16 * 1024
    }
}
