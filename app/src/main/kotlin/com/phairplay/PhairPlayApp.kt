package com.phairplay

import android.app.Application
import com.phairplay.diagnostic.LogBuffer
import timber.log.Timber

/**
 * PhairPlayApp — The Application class for PhairPlay.
 *
 * WHY: Android requires an Application subclass to run initialization code before
 * any Activity or Service starts. We use this to set up logging (Timber) once
 * at startup.
 *
 * HOW: This class is referenced in AndroidManifest.xml via android:name=".PhairPlayApp".
 * It is instantiated automatically by the Android framework when the app process starts.
 *
 * Example (automatic — no user code needed):
 *   The app starts → Android creates PhairPlayApp → onCreate() runs → Timber is ready.
 */
class PhairPlayApp : Application() {

    override fun onCreate() {
        super.onCreate()
        initLogging()
    }

    /**
     * Sets up Timber logging.
     *
     * In debug builds: logs to Android logcat with full file/line info.
     * In release builds: only logs warnings and errors (no verbose/debug output).
     *
     * WHY: Timber is a tiny wrapper around android.util.Log that adds:
     * - Automatic class name as a log tag (no more TAG constants everywhere)
     * - Easy filtering by log level in release builds
     * - The ability to swap the log backend (useful for crash reporting in future)
     */
    private fun initLogging() {
        LogBuffer.init(filesDir)
        if (BuildConfig.DEBUG) {
            // Debug tree: logs everything, shows file names and line numbers
            Timber.plant(Timber.DebugTree())
        }
        // The LogBuffer tree is planted in ALL builds (debug + release): the buffer stays
        // on-device and is only reachable over the LAN diagnostic ports, so a sideloaded
        // release build stays debuggable without adb/logcat access.
        Timber.plant(LogBuffer.Tree())

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            LogBuffer.add("[CRASH/${thread.name}] UNCAUGHT: ${throwable.javaClass.name}: ${throwable.message}\n${throwable.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }
}
