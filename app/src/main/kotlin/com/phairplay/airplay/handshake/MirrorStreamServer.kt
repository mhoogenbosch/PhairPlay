package com.phairplay.airplay.handshake

import android.view.Surface
import com.phairplay.airplay.StreamStats
import com.phairplay.airplay.VideoDecoder
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MirrorStreamServer — receives and decodes the AirPlay 2 mirroring video stream.
 *
 * macOS connects to [dataPort] and sends [128-byte header][payload] packets:
 *  - payload_size = little-endian int at header offset 0
 *  - payload_type = little-endian short at header offset 4, low byte
 *      • type 1: unencrypted avcC (SPS/PPS) → (re)configure the decoder
 *      • type 0: AES-CTR-encrypted H.264 (AVCC) → decrypt, convert to Annex-B, decode
 *      • type 2: heartbeat, normally an EMPTY payload (payload_size 0) → ignore
 *      • type 5: streaming report (binary plist, sender-side stats; plus a fixed ~25 KB trailer
 *        while the iPhone is locked) → ignored, first occurrence hexdumped
 *
 * Architecture: a network thread reads + decrypts packets (keeping the AES-CTR keystream strictly
 * ordered) and pushes work onto a bounded queue; a separate decoder thread consumes it. This way
 * the socket is always drained fast — if the decoder can't keep up (this SoC is modest, and the
 * audio codec competes for resources), frames are dropped from the queue instead of stalling the
 * socket, which previously caused macOS to drop the whole session.
 *
 * Reference: RPiPlay lib/raop_rtp_mirror.c (raop_rtp_mirror_thread).
 */
class MirrorStreamServer(
    aesKey: ByteArray,
    ecdhSecret: ByteArray,
    streamConnectionId: Long,
    private val surfaceProvider: () -> Surface?,
    private val width: Int = 1920,
    private val height: Int = 1080,
) {
    private sealed class Item
    private class Config(val sps: ByteArray, val pps: ByteArray) : Item()
    private class Frame(val annexB: ByteArray) : Item()

    private val cipher = MirrorCrypto.streamCipher(aesKey, ecdhSecret, streamConnectionId)
    private val serverSocket = ServerSocket(0)            // OS-assigned free port
    private val queue = ArrayBlockingQueue<Item>(QUEUE_CAPACITY)

    @Volatile private var running = false
    @Volatile private var client: Socket? = null
    @Volatile private var decoder: VideoDecoder? = null   // owned by the decoder thread
    private var lastSps: ByteArray? = null
    private var lastPps: ByteArray? = null
    // The Surface the current decoder was built against. The SurfaceView destroys its Surface when
    // the app backgrounds and recreates it on return — but `SurfaceHolder.getSurface()` hands back
    // the SAME Java object, so identity alone can't tell a dead surface from a live one. The decoder
    // thread therefore re-checks on every poll (not only when a frame arrives — a static iPhone
    // screen sends few) and also treats `!isValid` as a loss. See [surfaceNeedsRebuild].
    @Volatile private var configuredSurface: Surface? = null
    // Consecutive rebuilds triggered by an unhealthy decoder without a frame decoding in between —
    // bounds a rebuild storm if the codec keeps failing on this surface.
    private var unhealthyRebuilds = 0
    private var framePtsUs = 0L
    private var framesIn = 0
    private var framesDropped = 0
    private var lastStatMs = 0L
    // Set by the reader thread when a frame is dropped under load; the decoder thread then skips
    // frames until the next keyframe (IDR) so it never decodes a reference-broken, corrupt stream.
    @Volatile private var awaitingKeyframe = false

    // Unknown payload types we already hexdumped this session (first occurrence only).
    private val dumpedPayloadTypes = mutableSetOf<Int>()

    /** The OS-assigned TCP port macOS should connect to (returned in the SETUP response). */
    val dataPort: Int get() = serverSocket.localPort

    fun start(scope: CoroutineScope) {
        running = true
        scope.launch(Dispatchers.IO) { runReader() }
        scope.launch(Dispatchers.IO) { runDecoder() }
    }

    fun stop() {
        running = false
        runCatching { client?.close() }
        runCatching { serverSocket.close() }
        queue.clear()
        Logger.i("MirrorStreamServer stopped")
    }

    // ─── Network reader thread: read + decrypt (ordered), enqueue, never block on decode ──────
    private fun runReader() {
        try {
            Logger.i("MirrorStreamServer listening on data port $dataPort")
            val socket = serverSocket.accept().also { client = it }
            Logger.i("Mirror data connection from ${socket.inetAddress.hostAddress}")
            val input = socket.getInputStream()
            val header = ByteArray(128)
            while (running && !socket.isClosed) {
                if (!readFully(input, header, 128)) break
                val payloadSize = leInt(header, 0)
                val payloadType = leShort(header, 4) and 0xFF
                // 0 is legal: the type-2 heartbeat carries no payload. Treating it as corrupt (as
                // before) ended a healthy mirror the moment a sender sent one.
                if (payloadSize < 0 || payloadSize > MAX_PAYLOAD) {
                    Logger.w("Mirror: bad payloadSize=$payloadSize type=$payloadType — stopping")
                    break
                }
                val payload = ByteArray(payloadSize)
                if (payloadSize > 0 && !readFully(input, payload, payloadSize)) break
                when (payloadType) {
                    0 -> {
                        // ALWAYS advance the AES-CTR keystream, in order, for every video payload —
                        // skipping any packet desyncs the keystream and corrupts all later frames.
                        val annexB = MirrorCrypto.avccToAnnexB(cipher.update(payload))
                        if (annexB.isNotEmpty()) enqueue(Frame(annexB))
                    }
                    1 -> parseConfig(payload)?.let { enqueue(it) }
                    2 -> Logger.v("Mirror: heartbeat ($payloadSize B)")
                    else -> {
                        // Unknown types are ignored, but hexdump the first occurrence per type per
                        // session: iOS 26 sends a steady ~25KB payload type 5 whose meaning is
                        // still unidentified — this gives us material to analyse it offline.
                        if (dumpedPayloadTypes.add(payloadType)) {
                            val hdr = header.take(16).joinToString(" ") { "%02x".format(it) }
                            val head = payload.take(32).joinToString(" ") { "%02x".format(it) }
                            Logger.i("Mirror: first payload type $payloadType ($payloadSize B) header16=$hdr head32=$head")
                        } else {
                            Logger.v("Mirror: ignoring payload type $payloadType ($payloadSize B)")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror reader error", e)
        } finally {
            running = false
            Logger.i("Mirror data connection ended")
        }
    }

    /** Bounded enqueue — if the decoder is behind, drop the oldest item to keep latency bounded. */
    private fun enqueue(item: Item) {
        framesIn++
        if (!queue.offer(item)) {
            queue.poll()
            queue.offer(item)
            framesDropped++
            awaitingKeyframe = true        // a frame was lost — resync the decoder at the next IDR
        }
        StreamStats.videoQueue = queue.size
        if (framesIn % 300 == 0) {
            val now = System.currentTimeMillis()
            if (lastStatMs != 0L) StreamStats.videoFps = (300_000L / (now - lastStatMs).coerceAtLeast(1)).toInt()
            lastStatMs = now
            StreamStats.videoDropPct = framesDropped * 100 / framesIn
            Logger.i("Video stats: in=$framesIn dropped=$framesDropped " +
                "(${StreamStats.videoDropPct}%) queue=${queue.size}/$QUEUE_CAPACITY ${StreamStats.videoFps}fps")
        }
    }

    private fun parseConfig(payload: ByteArray): Config? = try {
        val spsSize = ((payload[6].toInt() and 0xFF) shl 8) or (payload[7].toInt() and 0xFF)
        val sps = payload.copyOfRange(8, 8 + spsSize)
        val ppsLenOffset = 8 + spsSize + 1                   // skip the 1-byte PPS count
        val ppsSize = ((payload[ppsLenOffset].toInt() and 0xFF) shl 8) or
            (payload[ppsLenOffset + 1].toInt() and 0xFF)
        Config(sps, payload.copyOfRange(ppsLenOffset + 2, ppsLenOffset + 2 + ppsSize))
    } catch (e: Exception) {
        Logger.e("Mirror: failed to parse SPS/PPS", e); null
    }

    // ─── Decoder thread: consume the queue; the only thread that touches the decoder ──────────
    private fun runDecoder() {
        try {
            while (running) {
                checkSurface()
                val item = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                when (item) {
                    is Config -> configureDecoder(item.sps, item.pps)
                    is Frame -> decodeFrame(item.annexB)
                }
            }
        } catch (e: Exception) {
            if (running) Logger.e("Mirror decoder thread error", e)
        } finally {
            decoder?.release()
            decoder = null
        }
    }

    /**
     * Rebinds the decoder when the live Surface is gone, replaced or invalid. Runs on the decoder
     * thread only (MediaCodec is not thread-safe) — at every queue poll and before every frame.
     */
    private fun checkSurface() {
        val live = surfaceProvider()
        val configured = configuredSurface
        if (!surfaceNeedsRebuild(live, configured, liveValid = live?.isValid == true,
                hasDecoder = decoder != null, hasConfig = lastSps != null && lastPps != null)) return
        Logger.i("Mirror: surface ${if (live == null || !live.isValid) "lost" else "changed/restored"} — re-attaching decoder")
        rebuildDecoder(live)
    }

    private fun configureDecoder(sps: ByteArray, pps: ByteArray) {
        // New SPS/PPS (or first config) — cache it and (re)build against the current surface.
        unhealthyRebuilds = 0
        val d = decoder
        val surface = awaitSurface()
        if (d != null && d.isHealthy && sps.contentEquals(lastSps) && pps.contentEquals(lastPps) &&
            surface === configuredSurface) return
        lastSps = sps
        lastPps = pps
        rebuildDecoder(surface)
    }

    /**
     * (Re)creates the decoder for [surface] from the cached SPS/PPS. Safe to call on a surface
     * change (app background→foreground): releases the old decoder and resyncs at the next keyframe.
     * If the surface or config isn't available yet, leaves the decoder null and retries on a later
     * frame (frames are dropped until then).
     */
    private fun rebuildDecoder(surface: Surface?) {
        decoder?.release()
        decoder = null
        configuredSurface = surface
        val sps = lastSps ?: return
        val pps = lastPps ?: return
        if (surface == null || !surface.isValid) return        // backgrounded — wait for the surface to return
        try {
            val sc = MirrorCrypto.START_CODE
            decoder = VideoDecoder(surface).also { it.initialize(sc + sps, sc + pps, width, height) }
            awaitingKeyframe = true                            // a fresh decoder must start at an IDR
            StreamStats.videoRes = "${width}x${height}"
            Logger.i("Mirror decoder (re)built for surface (sps=${sps.size}B pps=${pps.size}B)")
        } catch (e: Exception) {
            // Surface died between the check and configure(); the next poll retries — bounded, so a
            // codec that never configures doesn't log a stack trace every 200 ms forever.
            Logger.e("Mirror decoder rebuild failed", e)
            decoder?.release()
            decoder = null
            if (++unhealthyRebuilds > MAX_UNHEALTHY_REBUILDS) { lastSps = null; lastPps = null }
        }
    }

    private fun decodeFrame(annexB: ByteArray) {
        checkSurface()
        val d = decoder ?: return                              // need surface + SPS/PPS first
        if (!d.isHealthy) {
            // Rebuild from the CACHED SPS/PPS. Wiping them (as before) left the screen black for the
            // rest of the session: the iPhone only resends SPS/PPS at session start or on a rotation/
            // resolution change, while audio kept playing. The usual cause is a codec that was writing
            // into a surface that had just been torn down (Home, screensaver).
            if (++unhealthyRebuilds > MAX_UNHEALTHY_REBUILDS) {
                // Give up on the cached config (the old behaviour): wait for the sender's next SPS/PPS.
                Logger.w("Mirror: decoder keeps failing — waiting for new SPS/PPS from the sender")
                d.release(); decoder = null; lastSps = null; lastPps = null
                return
            }
            Logger.w("Mirror: decoder unhealthy — rebuilding with cached SPS/PPS ($unhealthyRebuilds/$MAX_UNHEALTHY_REBUILDS)")
            rebuildDecoder(surfaceProvider())
            return
        }
        if (awaitingKeyframe) {
            // After a dropped frame the stream is reference-broken; skip until the next IDR so we
            // don't feed the decoder predicted frames with missing references (which smear/blocky).
            if (!isKeyframe(annexB)) return
            awaitingKeyframe = false
            Logger.i("Mirror: resynced on keyframe after a dropped frame")
        }
        if (framePtsUs == 0L) Logger.i("Mirror: first video frame fed to decoder (${annexB.size}B)")
        d.decodeNalUnit(annexB, framePtsUs)
        if (d.isHealthy) unhealthyRebuilds = 0
        framePtsUs += FRAME_INTERVAL_US
    }

    /** True if the Annex-B frame contains an IDR NAL unit (type 5) — a decodable resync point. */
    private fun isKeyframe(annexB: ByteArray): Boolean {
        var i = 0
        while (i + 3 < annexB.size) {
            if (annexB[i].toInt() == 0 && annexB[i + 1].toInt() == 0 && annexB[i + 2].toInt() == 1) {
                if ((annexB[i + 3].toInt() and 0x1F) == 5) return true   // IDR slice
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    /** The streaming Surface appears shortly after CONNECTED is emitted; poll briefly. */
    private fun awaitSurface(): Surface? {
        repeat(SURFACE_WAIT_TRIES) {
            if (!running) return null
            surfaceProvider()?.let { return it }
            try { Thread.sleep(SURFACE_WAIT_MS) } catch (_: InterruptedException) { return null }
        }
        return surfaceProvider()
    }

    private fun readFully(input: InputStream, buf: ByteArray, len: Int): Boolean {
        var read = 0
        while (read < len) {
            val n = input.read(buf, read, len - read)
            if (n == -1) return false
            read += n
        }
        return true
    }

    private fun leInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or ((b[off + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    companion object {
        private const val MAX_PAYLOAD = 8 * 1024 * 1024        // 8 MB sanity cap per frame
        private const val FRAME_INTERVAL_US = 1_000_000L / 60  // monotonic PTS hint (~60fps)
        private const val QUEUE_CAPACITY = 90                  // ~1.5s @60fps before dropping
        private const val SURFACE_WAIT_TRIES = 50
        private const val SURFACE_WAIT_MS = 100L
        private const val MAX_UNHEALTHY_REBUILDS = 5
    }
}

/**
 * True when the decoder must be rebound to the live surface.
 *
 * - The surface went away, or a different one is live (`!==`).
 * - Same object but invalid while a decoder still renders into it: release that decoder.
 * - Same object, valid again, no decoder (released during the outage) and a cached
 *   SPS/PPS to rebuild from: rebuild now instead of waiting for a frame.
 */
internal fun surfaceNeedsRebuild(
    live: Any?,
    configured: Any?,
    liveValid: Boolean,
    hasDecoder: Boolean,
    hasConfig: Boolean,
): Boolean = when {
    live !== configured -> true
    live == null -> false                                      // still gone — nothing to do
    !liveValid -> hasDecoder
    else -> !hasDecoder && hasConfig
}
