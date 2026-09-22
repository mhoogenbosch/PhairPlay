package com.phairplay.airplay

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger
import com.phairplay.util.NetworkUtils
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MdnsService — Advertises PhairPlay as an AirPlay 2 receiver on the local network.
 *
 * WHY: For macOS/iOS to show PhairPlay in the AirPlay menu, the device must announce
 * itself using mDNS (Multicast DNS, the same protocol as Apple's Bonjour).
 * Without this advertisement, no sender would know PhairPlay exists.
 *
 * HOW: Registers two mDNS services using Android's [NsdManager]:
 * - `_airplay._tcp` — main AirPlay service with feature flags and device info
 * - `_raop._tcp`    — audio streaming service (required even for screen mirroring)
 *
 * Both services use port [AIRPLAY_PORT] (7000), which is where [RtspHandler] listens.
 *
 * The service name shown in AirPlay pickers is determined by [displayNameOverride]:
 * - If set: uses the user-configured name from Settings
 * - If blank/null: falls back to [NetworkUtils.getDeviceName]
 *
 * State changes are reported via [onStateChange] callback.
 *
 * Self-healing (mh.11): the receiver is an appliance that must stay findable for weeks
 * without anyone looking at it, so the advertisement supervises itself:
 * - every registration (not only `_airplay`) has a probe watchdog for the stuck-probing case;
 * - a start that has not reached "both registered" within [ADVERTISE_TIMEOUT_MS] is restarted
 *   (bounded by [MAX_ADVERTISE_RESTARTS]) instead of sitting in DISABLED forever;
 * - after both services register, a self-probe browses `_airplay._tcp` and checks that our own
 *   name actually comes back over the wire — "Android says registered" is not the same as
 *   "an iPhone can see us" (observed 2026-09-21 on a Google TV box: registered, invisible,
 *   only a reboot helped). Not visible → re-advertise, bounded by [MAX_SELF_PROBE_RESTARTS].
 *
 * Example:
 *   val mdns = MdnsService(context, onStateChange = { state -> /* update UI */ })
 *   mdns.start(displayNameOverride = "Living Room TV")
 *   mdns.stop()
 *   mdns.restart(displayNameOverride = "Living Room TV")
 */
class MdnsService(
    private val context: Context,
    private val onStateChange: (ProtocolState) -> Unit = {},
    /**
     * Called with the actual mDNS service name after registration completes.
     *
     * Android's NsdManager resolves name collisions automatically: if another device
     * on the network is already registered as "PhairPlay", Android will register us as
     * "PhairPlay (2)" instead. The [onActualNameRegistered] callback delivers the name
     * that was actually registered (which may differ from the requested name).
     *
     * The caller can use this to update the UI (e.g., show "Registered as: PhairPlay (2)")
     * or log the divergence for debugging.
     *
     * Only the `_airplay._tcp` service name is reported (not the `_raop._tcp` name,
     * which has a MAC address prefix and is not shown to users).
     */
    private val onActualNameRegistered: (String) -> Unit = {}
) {

    // Android's built-in mDNS manager — handles multicast registration
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    /**
     * Wi-Fi multicast lock, held for as long as we advertise.
     *
     * Wi-Fi hardware drops multicast and broadcast frames that are not addressed to this
     * device whenever it is allowed to power-save, and mDNS is entirely multicast. Without
     * this lock a sender's query can simply never reach us, which looks exactly like "the TV
     * is not discoverable" even though the registration is live. The manifest has asked for
     * CHANGE_WIFI_MULTICAST_STATE since the beginning; nothing ever used it.
     *
     * Costs battery on a phone — irrelevant here, this is a mains-powered TV receiver whose
     * entire job is to be findable.
     */
    private val multicastLock: WifiManager.MulticastLock? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.createMulticastLock("PhairPlay-mdns").apply { setReferenceCounted(false) }
    }.getOrElse {
        Logger.w("Could not create Wi-Fi multicast lock: ${it.message}")
        null
    }

    // Listeners track registration state; held to enable unregistration later
    private var airPlayListener: NsdManager.RegistrationListener? = null
    private var raopListener: NsdManager.RegistrationListener? = null

    // Count of how many services have confirmed registration.
    // Only when both reach 2 do we emit ProtocolState.ADVERTISING.
    @Volatile
    private var registeredCount = 0

    // Guard against double-start
    @Volatile
    private var isStarted = false

    // Used for delayed registration retries, watchdogs and the restart-timeout fallback.
    private val handler = Handler(Looper.getMainLooper())

    // Unregistration is asynchronous: these track in-flight unregisters so [restart] can
    // wait for the old registrations to actually disappear before re-registering (otherwise
    // we race our own stale registration and the name escalates to "(2)"/"(3)").
    @Volatile
    private var pendingUnregistrations = 0
    @Volatile
    private var restartPending = false
    @Volatile
    private var restartDisplayName: String? = null
    private var restartFallback: Runnable? = null

    // The display-name override the current advertisement was started with. Self-initiated
    // restarts (watchdog, self-probe) re-use it so a supervised restart never changes the name.
    private var currentDisplayNameOverride: String? = null

    // Per-service registration watchdogs (keyed by service label): when the requested name
    // conflicts with another record on the SAME device (e.g. the TV's own Google Cast
    // registration), MdnsAdvertiser can get stuck probing forever and never delivers ANY
    // callback. Silence past the probe timeout is therefore treated as a name conflict.
    // Until mh.11 only `_airplay` had this; a silent `_raop` left the state on DISABLED forever.
    private val registrationWatchdogs = HashMap<String, Runnable>()

    // Start-to-ADVERTISING supervision: see [onAdvertiseWatchdog].
    private var advertiseWatchdog: Runnable? = null
    private var advertiseRestarts = 0

    // Self-probe: see [startSelfProbe].
    private var probeStart: Runnable? = null
    private var probeTimeout: Runnable? = null
    private var probeListener: NsdManager.DiscoveryListener? = null
    private var probeFailures = 0
    private var expectedAirPlayName: String? = null

    /**
     * Starts mDNS advertising.
     *
     * Registers both the `_airplay._tcp` and `_raop._tcp` services.
     * The device will appear in the macOS/iOS AirPlay menu within ~1-3 seconds.
     *
     * Idempotent: calling it twice without [stop] in between is a no-op.
     *
     * @param displayNameOverride User-configured display name from Settings.
     *   Pass `null` or blank to use the Android system device name.
     */
    @Synchronized
    fun start(displayNameOverride: String? = null) {
        if (isStarted) {
            Logger.w("MdnsService.start() called but already registered — ignoring")
            return
        }
        isStarted = true
        registeredCount = 0
        currentDisplayNameOverride = displayNameOverride
        expectedAirPlayName = null

        acquireMulticastLock()

        val effectiveName = resolveDisplayName(displayNameOverride)
        Logger.i("Starting mDNS advertising as '$effectiveName'")

        registerAirPlayService(effectiveName)
        registerRaopService(effectiveName)
        armAdvertiseWatchdog()
    }

    /**
     * Stops mDNS advertising.
     *
     * Unregisters both mDNS services. The device disappears from sender pickers
     * within ~5-10 seconds (mDNS goodbye packet sent immediately, but senders cache briefly).
     *
     * Safe to call even if [start] was never called.
     *
     * Synchronized (like [start], [restart] and the callback bookkeeping) so an unregistration
     * callback can never interleave with the teardown: before mh.11 `pendingUnregistrations`
     * was assigned *after* the asynchronous unregister calls, so a fast callback could see the
     * stale count, complete a pending [restart] early and have the tail of this method wipe the
     * brand-new listeners and watchdog again.
     */
    @Synchronized
    fun stop() {
        Logger.i("Stopping mDNS advertising")
        cancelAllRegistrationWatchdogs()
        cancelAdvertiseWatchdog()
        stopSelfProbe()

        val listeners = listOfNotNull(airPlayListener, raopListener)
        airPlayListener = null
        raopListener = null
        registeredCount = 0
        isStarted = false
        expectedAirPlayName = null

        var submitted = 0
        for (listener in listeners) {
            try {
                nsdManager.unregisterService(listener)
                submitted++
            } catch (e: Exception) {
                // Unregistration errors are non-fatal: the service will expire via mDNS TTL.
                // Not counted — a pending restart would otherwise stall on a callback that
                // never comes (the conflict-retry in registration covers any stale record).
                Logger.e("Error unregistering mDNS service (non-fatal)", e)
            }
        }
        // Still inside the lock: the unregistration callbacks run [onUnregistrationDone], which
        // is synchronized on this object too, so they queue up until this method returns.
        pendingUnregistrations = submitted

        // Kept across a restart(): stop() is its first half, and dropping the lock in
        // between would leave a window where queries are filtered again for no reason.
        if (!restartPending) releaseMulticastLock()
        onStateChange(ProtocolState.DISABLED)
    }

    /**
     * Restarts mDNS advertising.
     *
     * Used after a streaming session ends to immediately re-advertise the device
     * in sender pickers.
     *
     * Unregistration is asynchronous, so this waits for [NsdManager] to confirm both
     * unregistrations (with a [RESTART_TIMEOUT_MS] fallback) before re-registering.
     * Re-registering while the old registration is still live made NsdManager treat it
     * as a conflict and escalate the name to "Name (2)"/"(3)" after every teardown.
     *
     * @param displayNameOverride Updated display name, if changed in Settings.
     */
    @Synchronized
    fun restart(displayNameOverride: String? = null) {
        Logger.d("Restarting mDNS advertising")
        restartFallback?.let { handler.removeCallbacks(it) }
        restartFallback = null
        restartDisplayName = displayNameOverride
        restartPending = true
        stop()
        if (pendingUnregistrations <= 0) {
            completeRestart("immediate")
        } else {
            restartFallback = Runnable { completeRestart("timeout") }.also {
                handler.postDelayed(it, RESTART_TIMEOUT_MS)
            }
        }
    }

    /** Runs the deferred [restart] once unregistration completed (or timed out). */
    @Synchronized
    private fun completeRestart(reason: String) {
        if (!restartPending) return
        restartPending = false
        restartFallback?.let { handler.removeCallbacks(it) }
        restartFallback = null
        Logger.d("mDNS restart proceeding ($reason)")
        start(restartDisplayName)
    }

    /** Bookkeeping for [restart]: called from every unregistration callback. */
    @Synchronized
    private fun onUnregistrationDone() {
        if (pendingUnregistrations > 0) pendingUnregistrations--
        if (pendingUnregistrations == 0 && restartPending) completeRestart("unregistered")
    }

    private fun acquireMulticastLock() {
        val lock = multicastLock ?: return
        if (!lock.isHeld) {
            runCatching { lock.acquire() }
                .onSuccess { Logger.d("Wi-Fi multicast lock acquired") }
                .onFailure { Logger.w("Could not acquire multicast lock: ${it.message}") }
        }
    }

    private fun releaseMulticastLock() {
        val lock = multicastLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
                .onSuccess { Logger.d("Wi-Fi multicast lock released") }
                .onFailure { Logger.w("Could not release multicast lock: ${it.message}") }
        }
    }

    // ─── Private helpers ─────────────────────────────────────────────────────

    /**
     * Determines the effective name to advertise.
     * Uses [override] if non-blank; otherwise reads from the Android system.
     */
    private fun resolveDisplayName(override: String?): String {
        val trimmed = override?.trim() ?: ""
        return if (trimmed.isNotEmpty()) trimmed else NetworkUtils.getDeviceName(context)
    }

    /**
     * Registers the `_airplay._tcp` mDNS service.
     *
     * TXT records tell senders what features PhairPlay supports.
     * See TECHNICAL_SPEC.md §8 for bit-level breakdown of the `features` value.
     *
     * @param displayName The name shown in sender AirPlay pickers.
     */
    private fun registerAirPlayService(displayName: String, attempt: Int = 1) {
        val attemptName = nameForAttempt(displayName, attempt)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = attemptName
            serviceType = SERVICE_TYPE_AIRPLAY
            port = AIRPLAY_PORT

            // Core identity TXT records
            setAttribute("deviceid", NetworkUtils.getMacAddress(context))
            setAttribute("features", AIRPLAY_FEATURES)
            setAttribute("model", AIRPLAY_MODEL)
            setAttribute("srcvers", AIRPLAY_SERVER_VERSION)
            setAttribute("vv", "2")                             // AirPlay protocol version 2
            setAttribute("pi", NetworkUtils.getPersistentUuid(context))
            setAttribute("flags", "0x4")                        // Screen-mirroring receiver
        }

        val retry = { registerAirPlayService(displayName, attempt + 1) }
        val listener = createRegistrationListener(
            serviceLabel = SERVICE_TYPE_AIRPLAY,
            onRegisteredName = { actualName ->
                // Detect collision auto-renaming: NsdManager appended " (2)", " (3)", etc.
                if (actualName != attemptName) {
                    Logger.w("mDNS name collision detected: requested='$attemptName' " +
                             "actual='$actualName' — NsdManager resolved automatically")
                }
                expectedAirPlayName = actualName
                onActualNameRegistered(actualName)
            },
            onSuccess = {
                cancelRegistrationWatchdog(SERVICE_TYPE_AIRPLAY)
                incrementAndCheckBothRegistered()
            },
            onFailure = { errorCode ->
                cancelRegistrationWatchdog(SERVICE_TYPE_AIRPLAY)
                retryOrFail(SERVICE_TYPE_AIRPLAY, errorCode, attempt, retry)
            }
        )
        airPlayListener = listener
        submitRegistration(SERVICE_TYPE_AIRPLAY, serviceInfo, listener, attempt, retry)
    }

    /**
     * Registers the `_raop._tcp` mDNS service.
     *
     * RAOP (Remote Audio Output Protocol) is the audio component of AirPlay.
     * macOS and iOS require it even for screen mirroring — not only for audio-only streams.
     *
     * RAOP service name format required by the AirPlay protocol:
     *   `"<MACADDRESS_NOCOLONS>@<DeviceName>"`
     *   e.g., `"AABBCCDDEEFF@Living Room TV"`
     *
     * @param displayName The device name portion of the RAOP service name.
     */
    private fun registerRaopService(displayName: String, attempt: Int = 1) {
        val macHex = NetworkUtils.getMacAddress(context).replace(":", "").uppercase()

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "$macHex@${nameForAttempt(displayName, attempt)}"  // required RAOP format
            serviceType = SERVICE_TYPE_RAOP
            port = AIRPLAY_PORT

            setAttribute("cn", "0,1,2,3")        // Cipher numbers (encryption types)
            setAttribute("da", "true")             // Digest authentication capable
            setAttribute("et", "0,3,5")            // Encryption types supported
            setAttribute("md", "0,1,2")            // Metadata types supported
            setAttribute("sv", "false")            // Software volume control
            setAttribute("tp", "UDP")              // Transport for audio RTP
            setAttribute("vn", "65537")            // Version number (required)
            setAttribute("vs", AIRPLAY_SERVER_VERSION)
            setAttribute("am", AIRPLAY_MODEL)
        }

        val retry = { registerRaopService(displayName, attempt + 1) }
        val listener = createRegistrationListener(
            serviceLabel = SERVICE_TYPE_RAOP,
            onRegisteredName = null,  // RAOP name has MAC prefix — not shown to users
            onSuccess = {
                cancelRegistrationWatchdog(SERVICE_TYPE_RAOP)
                incrementAndCheckBothRegistered()
            },
            onFailure = { errorCode ->
                cancelRegistrationWatchdog(SERVICE_TYPE_RAOP)
                retryOrFail(SERVICE_TYPE_RAOP, errorCode, attempt, retry)
            }
        )
        raopListener = listener
        submitRegistration(SERVICE_TYPE_RAOP, serviceInfo, listener, attempt, retry)
    }

    /**
     * Hands a registration to [NsdManager] and arms its probe watchdog.
     *
     * `registerService` itself can throw (e.g. an `IllegalArgumentException` from NsdService
     * about the TXT record or a listener that is already in use). Before mh.11 that exception
     * escaped from whatever thread called [start] — on a watchdog- or callback-driven restart
     * that was a Handler thread, i.e. an uncaught crash. Now it is just another failed attempt.
     */
    private fun submitRegistration(
        serviceLabel: String,
        serviceInfo: NsdServiceInfo,
        listener: NsdManager.RegistrationListener,
        attempt: Int,
        retry: () -> Unit
    ) {
        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Logger.e("mDNS $serviceLabel registerService threw", e)
            retryOrFail(serviceLabel, errorCode = -2, attempt = attempt, retry = retry)
            return
        }
        armRegistrationWatchdog(serviceLabel, listener, serviceInfo.serviceName, attempt, retry)
    }

    /**
     * No callback at all within [PROBE_TIMEOUT_MS] = probing stuck on a conflict → cancel the
     * pending registration and retry with a suffixed name (see [registrationWatchdogs]).
     */
    @Synchronized
    private fun armRegistrationWatchdog(
        serviceLabel: String,
        listener: NsdManager.RegistrationListener,
        attemptName: String,
        attempt: Int,
        retry: () -> Unit
    ) {
        cancelRegistrationWatchdog(serviceLabel)
        val watchdog = Runnable { onRegistrationWatchdog(serviceLabel, listener, attemptName, attempt, retry) }
        registrationWatchdogs[serviceLabel] = watchdog
        handler.postDelayed(watchdog, PROBE_TIMEOUT_MS)
    }

    @Synchronized
    private fun onRegistrationWatchdog(
        serviceLabel: String,
        listener: NsdManager.RegistrationListener,
        attemptName: String,
        attempt: Int,
        retry: () -> Unit
    ) {
        registrationWatchdogs.remove(serviceLabel)
        val current = if (serviceLabel == SERVICE_TYPE_AIRPLAY) airPlayListener else raopListener
        if (!isStarted || current !== listener) return
        Logger.w("mDNS $serviceLabel '$attemptName' got no registration callback in " +
                 "${PROBE_TIMEOUT_MS}ms — probing stuck, treating as name conflict")
        runCatching { nsdManager.unregisterService(listener) }
        retryOrFail(serviceLabel, errorCode = -1, attempt = attempt, retry = retry)
    }

    @Synchronized
    private fun cancelRegistrationWatchdog(serviceLabel: String) {
        registrationWatchdogs.remove(serviceLabel)?.let { handler.removeCallbacks(it) }
    }

    @Synchronized
    private fun cancelAllRegistrationWatchdogs() {
        registrationWatchdogs.values.forEach { handler.removeCallbacks(it) }
        registrationWatchdogs.clear()
    }

    /**
     * Retry policy for failed registrations.
     *
     * Newer Android versions do NOT auto-rename on an mDNS name conflict (despite what
     * NsdManager's docs suggest): NsdService throws a NameConflictException, which surfaces
     * here as a plain registration failure. Seen in the wild on Google TV, where the
     * system device name ("Nokia Streaming Box 8010") is already taken by the device's own
     * Google Cast registration. So we resolve conflicts ourselves: retry with a numbered
     * suffix — "Name (2)", "Name (3)" — up to [MAX_NAME_ATTEMPTS], then give up with ERROR.
     */
    @Synchronized
    private fun retryOrFail(serviceLabel: String, errorCode: Int, attempt: Int, retry: () -> Unit) {
        if (attempt < MAX_NAME_ATTEMPTS && isStarted) {
            Logger.w("mDNS $serviceLabel registration failed (errorCode=$errorCode) — " +
                     "retrying with suffixed name (attempt ${attempt + 1}/$MAX_NAME_ATTEMPTS)")
            handler.postDelayed({ if (isStarted) retry() }, RETRY_DELAY_MS)
        } else {
            Logger.e("mDNS $serviceLabel registration gave up after $attempt attempt(s) " +
                     "(errorCode=$errorCode) — receiver state ERROR")
            isStarted = false
            cancelAdvertiseWatchdog()
            onStateChange(ProtocolState.ERROR)
        }
    }

    /** "Name" for the first attempt, "Name (2)", "Name (3)" for conflict retries. */
    private fun nameForAttempt(base: String, attempt: Int): String =
        if (attempt <= 1) base else "$base ($attempt)"

    /**
     * Emits [ProtocolState.ADVERTISING] only after both services have confirmed registration.
     * This prevents a brief "advertising" state where only one of the two required services
     * is live.
     */
    @Synchronized
    private fun incrementAndCheckBothRegistered() {
        registeredCount++
        if (registeredCount >= 2) {
            advertiseRestarts = 0
            cancelAdvertiseWatchdog()
            onStateChange(ProtocolState.ADVERTISING)
            scheduleSelfProbe()
        }
    }

    // ─── Start-to-ADVERTISING supervision ────────────────────────────────────

    /**
     * Arms the "did this start actually complete?" timer.
     *
     * [stop] emits DISABLED and [start] only emits ADVERTISING once both registrations
     * confirm. Anything that swallows one of the two confirmations (a callback that never
     * comes, a listener wiped by a racing stop, an NsdService hiccup) used to leave the
     * receiver on DISABLED for good — RTSP still listening, toggle still "on", invisible to
     * every sender, and nothing in the app would ever look at it again. Now a start that is
     * still incomplete after [ADVERTISE_TIMEOUT_MS] is restarted, up to
     * [MAX_ADVERTISE_RESTARTS] times, then reported as ERROR (which the service-level health
     * check retries later at a slow cadence).
     */
    @Synchronized
    private fun armAdvertiseWatchdog() {
        cancelAdvertiseWatchdog()
        advertiseWatchdog = Runnable { onAdvertiseWatchdog() }.also {
            handler.postDelayed(it, ADVERTISE_TIMEOUT_MS)
        }
    }

    @Synchronized
    private fun cancelAdvertiseWatchdog() {
        advertiseWatchdog?.let { handler.removeCallbacks(it) }
        advertiseWatchdog = null
    }

    @Synchronized
    private fun onAdvertiseWatchdog() {
        advertiseWatchdog = null
        if (!isStarted || registeredCount >= 2) return
        if (advertiseRestarts < MAX_ADVERTISE_RESTARTS) {
            advertiseRestarts++
            Logger.w("mDNS advertisement still incomplete ${ADVERTISE_TIMEOUT_MS / 1000}s after start " +
                     "(registered $registeredCount/2) — restarting " +
                     "(attempt $advertiseRestarts/$MAX_ADVERTISE_RESTARTS)")
            restart(currentDisplayNameOverride)
        } else {
            Logger.e("mDNS advertisement still incomplete after $MAX_ADVERTISE_RESTARTS restarts " +
                     "(registered $registeredCount/2) — giving up, receiver state ERROR")
            isStarted = false
            onStateChange(ProtocolState.ERROR)
        }
    }

    // ─── Self-probe: is our own advertisement visible on the LAN? ────────────

    /**
     * Schedules [startSelfProbe] shortly after both registrations confirmed, giving the
     * advertiser time to send its announcements first.
     */
    @Synchronized
    private fun scheduleSelfProbe() {
        stopSelfProbe()
        val name = expectedAirPlayName ?: return
        probeStart = Runnable { startSelfProbe(name) }.also {
            handler.postDelayed(it, SELF_PROBE_DELAY_MS)
        }
    }

    /**
     * Browses `_airplay._tcp` for [SELF_PROBE_TIMEOUT_MS] and checks that our own service name
     * is among the answers. A registration confirmed by NsdService only means the advertiser
     * finished probing without a conflict; whether the records actually make it onto the wire
     * (and back) is a separate question — and the one senders care about.
     *
     * Not visible → re-advertise (a fresh unregister/register cycle), at most
     * [MAX_SELF_PROBE_RESTARTS] times per process. After that the probe stops acting and only
     * logs, so a platform on which own services never show up in discovery cannot make us
     * flap the name forever. A successful probe resets the counter.
     */
    @Synchronized
    private fun startSelfProbe(expectName: String) {
        probeStart = null
        if (!isStarted || probeListener != null) return

        val found = AtomicBoolean(false)
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Logger.d("mDNS self-probe: browsing $serviceType for '$expectName'")
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceName == expectName && found.compareAndSet(false, true)) {
                    Logger.i("mDNS self-probe: own advertisement '$expectName' is visible on the LAN")
                    onSelfProbeResult(this, visible = true)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Logger.w("mDNS self-probe could not start (errorCode=$errorCode) — skipped")
                clearSelfProbe(this)
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }
        probeListener = listener
        try {
            nsdManager.discoverServices(SERVICE_TYPE_AIRPLAY, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            Logger.w("mDNS self-probe could not start: ${e.message} — skipped")
            probeListener = null
            return
        }
        probeTimeout = Runnable { onSelfProbeResult(listener, visible = false) }.also {
            handler.postDelayed(it, SELF_PROBE_TIMEOUT_MS)
        }
    }

    @Synchronized
    private fun onSelfProbeResult(listener: NsdManager.DiscoveryListener, visible: Boolean) {
        if (probeListener !== listener) return   // already stopped or superseded
        val name = expectedAirPlayName
        stopSelfProbe()
        if (visible) {
            probeFailures = 0
            return
        }
        if (!isStarted) return
        if (probeFailures < MAX_SELF_PROBE_RESTARTS) {
            probeFailures++
            Logger.w("mDNS self-probe: '$name' NOT seen on the LAN within " +
                     "${SELF_PROBE_TIMEOUT_MS / 1000}s although Android reports it registered — " +
                     "re-advertising (attempt $probeFailures/$MAX_SELF_PROBE_RESTARTS)")
            restart(currentDisplayNameOverride)
        } else {
            Logger.w("mDNS self-probe: '$name' still not visible after $MAX_SELF_PROBE_RESTARTS " +
                     "re-advertisements — leaving the registration as is (Android reports it " +
                     "registered; check the multicast path between this device and the sender)")
        }
    }

    @Synchronized
    private fun clearSelfProbe(listener: NsdManager.DiscoveryListener) {
        if (probeListener === listener) {
            probeTimeout?.let { handler.removeCallbacks(it) }
            probeTimeout = null
            probeListener = null
        }
    }

    @Synchronized
    private fun stopSelfProbe() {
        probeStart?.let { handler.removeCallbacks(it) }
        probeStart = null
        probeTimeout?.let { handler.removeCallbacks(it) }
        probeTimeout = null
        probeListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        probeListener = null
    }

    /**
     * Creates an [NsdManager.RegistrationListener] with logging and callbacks.
     *
     * @param serviceLabel     Human-readable service type for log messages.
     * @param onRegisteredName Called with the actual registered service name (may differ from
     *   requested due to collision resolution). Pass null if the name is not user-visible.
     * @param onSuccess        Called on [onServiceRegistered].
     * @param onFailure        Called on [onRegistrationFailed].
     */
    private fun createRegistrationListener(
        serviceLabel: String,
        onRegisteredName: ((String) -> Unit)?,
        onSuccess: () -> Unit,
        onFailure: (errorCode: Int) -> Unit
    ): NsdManager.RegistrationListener {
        return object : NsdManager.RegistrationListener {

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                // NsdManager may append " (2)" to resolve name conflicts.
                // Log the actual name so we can debug picker-visibility issues.
                Logger.i("mDNS registered: $serviceLabel as '${serviceInfo.serviceName}'")
                onRegisteredName?.invoke(serviceInfo.serviceName)
                onSuccess()
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Error codes from NsdManager:
                //   FAILURE_ALREADY_ACTIVE (3) — already registered; treat as success
                //   FAILURE_MAX_LIMIT (4)      — too many services (should not happen)
                //   FAILURE_INTERNAL_ERROR (0) — system mDNS daemon issue, including
                //     NameConflictException on newer Android (see [retryOrFail])
                if (errorCode == NsdManager.FAILURE_ALREADY_ACTIVE) {
                    Logger.w("mDNS $serviceLabel already active — treating as success")
                    onSuccess()
                } else {
                    Logger.e("mDNS registration FAILED for $serviceLabel, errorCode=$errorCode")
                    onFailure(errorCode)
                }
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                Logger.d("mDNS unregistered: $serviceLabel")
                onUnregistrationDone()
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // Non-fatal: the service will expire via mDNS TTL (~4500ms by default)
                Logger.w("mDNS unregistration failed for $serviceLabel, errorCode=$errorCode (non-fatal)")
                onUnregistrationDone()
            }
        }
    }

    companion object {
        /** Standard mDNS service type for AirPlay receivers. */
        private const val SERVICE_TYPE_AIRPLAY = "_airplay._tcp"

        /** Standard mDNS service type for RAOP (audio). Required alongside AirPlay. */
        private const val SERVICE_TYPE_RAOP = "_raop._tcp"

        /** AirPlay RTSP port — [RtspHandler] must listen on this port. */
        const val AIRPLAY_PORT = 7000

        /** Total registration attempts per service before giving up (1 + 2 suffix retries). */
        private const val MAX_NAME_ATTEMPTS = 3

        /** Delay before a conflict-retry registration attempt. */
        private const val RETRY_DELAY_MS = 300L

        /** How long [restart] waits for unregistration callbacks before proceeding anyway. */
        private const val RESTART_TIMEOUT_MS = 2000L

        /**
         * How long a registration may stay silent (no success/failure callback) before the
         * watchdog treats it as a stuck probe. mDNS probing normally completes in <1s
         * (3 probes × 250ms); 5s leaves ample margin on a busy network.
         */
        private const val PROBE_TIMEOUT_MS = 5000L

        /**
         * How long a [start] may take to reach "both registered" before it is restarted.
         * Must exceed the worst-case conflict-retry chain of one service
         * (MAX_NAME_ATTEMPTS × (PROBE_TIMEOUT_MS + RETRY_DELAY_MS) ≈ 16s).
         */
        private const val ADVERTISE_TIMEOUT_MS = 20_000L

        /** Consecutive incomplete starts tolerated before reporting ERROR. */
        private const val MAX_ADVERTISE_RESTARTS = 3

        /** Pause between "both registered" and the self-probe, so announcements go out first. */
        private const val SELF_PROBE_DELAY_MS = 1500L

        /** How long the self-probe browses before concluding we are not visible. */
        private const val SELF_PROBE_TIMEOUT_MS = 10_000L

        /** Re-advertisements the self-probe may trigger per process before it goes passive. */
        private const val MAX_SELF_PROBE_RESTARTS = 2

        /**
         * AirPlay feature bitmask: advertise screen mirroring, video, and audio support.
         * See TECHNICAL_SPEC.md §8 for the full bit-level breakdown.
         */
        private const val AIRPLAY_FEATURES = "0x5A7FFFF7,0x1E"

        /** Pretend to be an Apple TV so macOS uses the screen mirroring protocol. */
        private const val AIRPLAY_MODEL = "AppleTV5,3"

        /** AirPlay server version — matches a real Apple TV for maximum compatibility. */
        private const val AIRPLAY_SERVER_VERSION = "220.68"
    }
}
