package com.phairplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phairplay.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BootReceiver — starts [PhairPlayService] on device boot, and again after the app was updated,
 * if "Start on boot" is enabled.
 *
 * MY_PACKAGE_REPLACED: an update (adb `install -r`, a sideloader) kills the process and nothing
 * restarted the service, so the TV stopped being a receiver until someone opened the app — which
 * meant an update had to bring the app to the front and interrupt whatever was playing. The system
 * delivers this broadcast to the new version only, and it is one of the documented exemptions
 * from the Android 12+ background foreground-service start restriction, like BOOT_COMPLETED.
 *
 * WHY: Android kills all services on reboot. For a receiver app to work without
 * the user manually reopening the app, we register for BOOT_COMPLETED.
 * The setting is checked asynchronously before starting the service to avoid
 * unnecessary foreground service starts that would show an unexpected notification.
 *
 * HOW:
 * 1. System fires BOOT_COMPLETED ~30–60 seconds after boot.
 * 2. We read [AppSettings.startOnBoot] from DataStore.
 * 3. If enabled, call [ServiceController.start] — that fires a foreground service
 *    intent which is safe to call from a BroadcastReceiver.
 *
 * Declared in AndroidManifest.xml with `exported="false"` — only the system
 * can fire BOOT_COMPLETED, so no external app can trigger this receiver.
 *
 * NOTE: On Android 10+ a BroadcastReceiver has a strict 10-second execution window.
 * We use a [goAsync] + coroutine pattern to read DataStore without blocking the
 * main thread, then release the wake lock via [PendingResult.finish].
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        Timber.d("BootReceiver: $action received")

        // goAsync() extends the execution window beyond the 10-second BroadcastReceiver limit.
        val pendingResult: PendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = SettingsRepository(context).settingsFlow.first()
                if (settings.startOnBoot) {
                    Timber.i("BootReceiver: startOnBoot=true → starting PhairPlayService")
                    ServiceController.start(context)
                } else {
                    Timber.d("BootReceiver: startOnBoot=false → not starting service")
                }
            } catch (e: Exception) {
                Timber.e(e, "BootReceiver: failed to read settings or start service")
            } finally {
                pendingResult.finish()
            }
        }
    }
}
