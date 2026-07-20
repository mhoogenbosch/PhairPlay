package com.phairplay.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.phairplay.settings.AppSettings
import com.phairplay.settings.SettingsRepository
import com.phairplay.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * DisplayNameReceiver — sets the advertised display name headlessly, for a fully scripted rollout.
 *
 * WHY: Naming each TV through the on-screen Settings UI (D-pad) is slow and error-prone across a
 * fleet. This exported receiver lets the name be set over adb during install:
 *
 *   adb shell am broadcast \
 *     -n <pkg>/com.phairplay.service.DisplayNameReceiver \
 *     -a com.phairplay.action.SET_DISPLAY_NAME --es name "Woonkamer-TV"
 *
 * It only writes the [SettingsRepository]; the running [PhairPlayService] observes the change and
 * re-registers mDNS live (see PhairPlayService.observeDisplayNameChanges). If the service isn't
 * running the value still persists and is applied on the next start. A blank/absent name clears the
 * override (falls back to the Android device name).
 *
 * The receiver is exported so `adb`/`am` (shell uid) can reach it. Its only effect is renaming the
 * receiver device — a benign operation on an appliance — so the exposure is acceptable and it is not
 * guarded by a custom permission (which shell could not hold anyway).
 */
class DisplayNameReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != PhairPlayService.ACTION_SET_DISPLAY_NAME) return

        val name = intent.getStringExtra(PhairPlayService.EXTRA_DISPLAY_NAME)
            ?.trim()
            .orEmpty()
            .take(AppSettings.DISPLAY_NAME_MAX_LENGTH)

        // DataStore writes are asynchronous; keep the process alive until the write completes.
        val pending = goAsync()
        val repo = SettingsRepository(context.applicationContext)
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            try {
                repo.update { it.copy(displayName = name) }
                Logger.i("DisplayNameReceiver: display name set to " +
                         "'${name.ifEmpty { "<cleared → system name>" }}'")
            } catch (e: Exception) {
                Logger.e("DisplayNameReceiver: failed to set display name", e)
            } finally {
                pending.finish()
            }
        }
    }
}
