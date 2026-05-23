package com.phairplay.cast

import android.content.Context
import com.phairplay.BuildConfig
import com.phairplay.service.ProtocolState
import com.phairplay.util.Logger

/**
 * CastReceiver — Google Cast receiver stub.
 *
 * WHY: Google Cast (Chromecast protocol) allows Chrome browser, Android, and iOS
 * devices to cast their screen or content to PhairPlay. It complements AirPlay
 * (macOS only) and Miracast (Windows/Android without setup) for a universal receiver.
 *
 * HOW: Implementation proceeds in phases:
 * - Phase 1 (this stub): Architecture defined, configuration checks, graceful fallback
 * - Phase 2 (M7): Full Cast Connect SDK integration
 *
 * IMPORTANT PRE-CONDITIONS for Cast support:
 * 1. Register PhairPlay as a Cast application at:
 *    https://cast.google.com/publish → "ADD NEW APPLICATION" → "Custom Receiver"
 * 2. Note the Application ID assigned by Google
 * 3. Build with `-Pphairplay.castAppId=<APP_ID>` or `PHAIRPLAY_CAST_APP_ID=<APP_ID>`
 * 4. Add Cast Connect SDK dependencies and receiver metadata when full Cast is enabled
 *
 * FIRE TV LIMITATION:
 * Amazon Fire TV does NOT include Google Play Services, which is required by the
 * Cast SDK. The `firetv` flavor automatically disables Cast. The [isAvailable]
 * check handles this gracefully.
 *
 * Cast protocol stack (simplified):
 *   Cast app ID association → sender launch request → Cast Connect session →
 *   media/session messages → content display
 *
 * Example (future usage):
 *   val receiver = CastReceiver(context) { state -> updateUI(state) }
 *   if (CastReceiver.isAvailable(context)) {
 *       receiver.start()
 *   }
 */
class CastReceiver(
    private val context: Context,
    private val onStateChanged: (ProtocolState) -> Unit
) {

    /**
     * Starts the Cast receiver.
     *
     * First checks if Cast is available on this device (requires Google Play Services).
     * On Fire TV and other GMS-less devices, emits DISABLED state and returns.
     *
     * TODO Phase 7: Initialize CastReceiverContext from Cast SDK.
     */
    fun start() {
        if (!isConfigured()) {
            Logger.w("Google Cast is not configured: missing Cast application ID")
            onStateChanged(ProtocolState.ERROR)
            return
        }

        if (!isAvailable(context)) {
            Logger.w("Google Cast not available on this device (missing Google Play Services)")
            onStateChanged(ProtocolState.DISABLED)
            return
        }

        Logger.i("CastReceiver starting")
        // TODO Phase 7: Initialize CastReceiverContext with CAST_APP_ID.
        // TODO Phase 7: Register CastReceiverContext.SessionManagerListener.
        onStateChanged(ProtocolState.ADVERTISING)
    }

    /**
     * Stops the Cast receiver and releases SDK resources.
     *
     * TODO Phase 7: CastReceiverContext.getInstance().stop()
     */
    fun stop() {
        Logger.i("CastReceiver stopping")
        // TODO Phase 7: CastReceiverContext.getInstance().stop()
        onStateChanged(ProtocolState.DISABLED)
    }

    companion object {

        /**
         * Returns true if Google Cast is available on this device.
         *
         * Cast requires Google Play Services (GMS). On Amazon Fire TV and
         * other AOSP-based devices without GMS, Cast is not available.
         *
         * WHY check here rather than in the firetv flavor: even on Google TV,
         * devices without GMS exist (some Android TV boxes). This runtime check
         * is more robust than a build-time check.
         *
         * @param context Any Android context.
         * @return true if GMS is available and Cast can be initialized.
         */
        fun isAvailable(context: Context): Boolean {
            return try {
                // Check if Google Play Services is available
                // We use the package manager rather than importing GoogleApiAvailability
                // to avoid a compile-time dependency on GMS in the firetv flavor
                context.packageManager.getPackageInfo("com.google.android.gms", 0)
                true
            } catch (e: Exception) {
                false
            }
        }

        /**
         * Returns true when this build contains a registered Cast Application ID.
         *
         * The ID is intentionally supplied at build time so local debug builds can
         * remain open-source and shareable without pretending Cast is testable
         * before console registration is complete.
         */
        fun isConfigured(appId: String = CAST_APP_ID): Boolean {
            val normalized = appId.trim()
            return normalized.isNotEmpty() &&
                normalized != "TODO_REGISTER_YOUR_CAST_APP_ID" &&
                normalized != "00000000"
        }

        /**
         * The Cast Application ID registered on the Google Cast Developer Console.
         *
         * Register at: https://cast.google.com/publish and build with either:
         * `./gradlew assembleGoogletvDebug -Pphairplay.castAppId=<APP_ID>` or
         * `PHAIRPLAY_CAST_APP_ID=<APP_ID> ./gradlew assembleGoogletvDebug`.
         *
         * The App ID tells senders (Chrome, Android) which Cast receiver app to use.
         * Without a valid App ID, Cast connections will be rejected.
         */
        const val CAST_APP_ID = BuildConfig.CAST_APP_ID
    }
}
