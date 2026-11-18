package com.phairplay.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.TextView
import com.phairplay.airplay.StreamStats
import com.phairplay.util.Logger

/**
 * Uses a native SurfaceView for hardware decoder output. Older MediaTek TV firmware routes video
 * through a dedicated display plane and can leave TextureView-backed decoder output black.
 * The SurfaceView stays full-screen; view scaling letterboxes streams without resizing its surface.
 */
class StreamingScreen @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {
    private val videoView = SurfaceView(context)
    @Volatile private var surface: Surface? = null
    private val debugView = TextView(context).apply {
        setTextColor(Color.parseColor("#FF00FF66"))
        setBackgroundColor(Color.parseColor("#A6000000"))
        textSize = 13f
        typeface = Typeface.MONOSPACE
        setPadding(24, 16, 24, 16)
        visibility = GONE
    }
    private var lastDimensions: List<Int> = emptyList()
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applyAspectFit()
            if (StreamStats.overlayEnabled) {
                debugView.visibility = VISIBLE
                debugView.text = StreamStats.summary()
            } else {
                debugView.visibility = GONE
            }
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        addView(videoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(debugView, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP or Gravity.START; topMargin = 48; leftMargin = 48
        })
        videoView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surface = holder.surface
                lastDimensions = emptyList()
                applyAspectFit()
                Logger.d("StreamingScreen: native surface created ${width}x${height}")
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                lastDimensions = emptyList()
                applyAspectFit()
            }
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surface = null
                Logger.d("StreamingScreen: native surface destroyed")
            }
        })
    }

    fun getSurface(): Surface? = surface

    private fun applyAspectFit() {
        StreamStats.displayRefreshHz = display?.refreshRate ?: 0f
        if (width <= 0 || height <= 0) return
        val dimensions = listOf(StreamStats.videoWidth, StreamStats.videoHeight, width, height)
        if (dimensions == lastDimensions) return
        lastDimensions = dimensions
        val (sx, sy) = VideoAspectFit.scale(dimensions[0], dimensions[1], width, height)
        videoView.pivotX = width / 2f
        videoView.pivotY = height / 2f
        videoView.scaleX = sx
        videoView.scaleY = sy
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(tick)
    }
    override fun onDetachedFromWindow() {
        handler.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }
    companion object { private const val REFRESH_MS = 200L }
}
