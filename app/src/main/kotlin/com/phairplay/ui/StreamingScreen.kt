package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.Surface
import android.widget.FrameLayout
import android.widget.TextView
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger

/**
 * StreamingScreen — Full-screen view that displays the AirPlay video stream.
 *
 * WHY: The decoded video from MediaCodec must be rendered to a [Surface].
 * A [SurfaceView] provides a dedicated, hardware-accelerated drawing surface
 * that can receive MediaCodec output directly — no intermediate bitmap copies.
 * This is the lowest-latency way to display video on Android.
 *
 * HOW: Add this view to the streaming_container in activity_main.xml.
 * Call [getSurface] to get the Surface to pass to [VideoDecoder.initialize].
 * The Surface is valid as long as this view is attached to the window.
 *
 * IMPORTANT: The Surface becomes available asynchronously after the view is
 * laid out. [getSurface] returns null if called before the Surface is ready.
 * [VideoDecoder.initialize] must not be called until [getSurface] returns non-null.
 *
 * Example:
 *   val streamingScreen = StreamingScreen(context)
 *   container.addView(streamingScreen)
 *   // Later, when surface is ready:
 *   val surface = streamingScreen.getSurface()
 *   videoDecoder.initialize(spsBytes, ppsBytes, width, height)
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    // The SurfaceView that provides the hardware-accelerated rendering surface
    private val surfaceView: SurfaceView = SurfaceView(context)

    // The Surface is created asynchronously by SurfaceView — stored here when ready
    private var surface: Surface? = null

    // Optional debug HUD (Settings → "Debug overlay"), drawn on top of the video.
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    private val handler = Handler(Looper.getMainLooper())
    private val debugTick = object : Runnable {
        override fun run() {
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else if (debugView.visibility != GONE) {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, DEBUG_REFRESH_MS)
        }
    }

    init {
        // Add the SurfaceView to fill this FrameLayout completely
        addView(surfaceView, LayoutParams(
            LayoutParams.MATCH_PARENT,
            LayoutParams.MATCH_PARENT
        ))

        // Debug HUD overlay, top-left, above the video surface.
        addView(debugView, LayoutParams(
            LayoutParams.WRAP_CONTENT,
            LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48 })

        // Register a callback to track when the Surface is created/destroyed
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                // Surface is now ready — store reference for VideoDecoder
                surface = holder.surface
                Logger.d("StreamingScreen: Surface created")
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                // Called when the surface size changes (e.g., resolution change)
                // MediaCodec handles this automatically — no action needed here
                Logger.d("StreamingScreen: Surface changed ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                // Surface is gone (e.g., screen turned off) — VideoDecoder must stop
                surface = null
                Logger.d("StreamingScreen: Surface destroyed")
            }
        })
    }

    /**
     * Returns the [Surface] where decoded video frames will be rendered.
     *
     * Returns null if the Surface has not been created yet (the view hasn't
     * been laid out) or if it has been destroyed. The caller must check for
     * null before passing this to [VideoDecoder.initialize].
     *
     * @return The rendering Surface, or null if not yet available.
     */
    fun getSurface(): Surface? = surface

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(debugTick)            // start polling the debug HUD
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(debugTick)
        super.onDetachedFromWindow()
    }

    companion object {
        private const val DEBUG_REFRESH_MS = 500L
    }
}
