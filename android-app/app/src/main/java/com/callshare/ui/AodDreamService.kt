package com.callshare.ui

import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.View
import android.widget.TextView
import com.callshare.R
import com.callshare.native.NativeBridge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AodDreamService
 *
 * Native Android DreamService (Daydream / Screensaver) integration for CallShare P2P.
 * Automatically activates the pitch-black AMOLED Always-On Display when docked or charging,
 * applying the Rust-calculated Lissajous anti-burn-in displacement every 60 seconds.
 */
class AodDreamService : DreamService() {

    companion object {
        private const val TAG = "AodDreamService"
    }

    private var aodContainer: View? = null
    private var textClock: TextView? = null
    private var textDate: TextView? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateClock()
            applyBurnInPixelShift()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        isScreenBright = false

        setContentView(R.layout.activity_aod)

        aodContainer = findViewById(R.id.aod_content_container)
        textClock = findViewById(R.id.text_aod_clock)
        textDate = findViewById(R.id.text_aod_date)

        Log.i(TAG, "AodDreamService attached to window")
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        Log.i(TAG, "AodDreamService dreaming started")
        mainHandler.post(clockTickRunnable)
    }

    override fun onDreamingStopped() {
        mainHandler.removeCallbacks(clockTickRunnable)
        super.onDreamingStopped()
        Log.i(TAG, "AodDreamService dreaming stopped")
    }

    private fun updateClock() {
        val now = Date()
        textClock?.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        textDate?.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
    }

    private fun applyBurnInPixelShift() {
        val offsets = NativeBridge.safeGetAodShiftOffset()
        if (offsets.size >= 2) {
            aodContainer?.translationX = offsets[0].toFloat()
            aodContainer?.translationY = offsets[1].toFloat()
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(clockTickRunnable)
        super.onDetachedFromWindow()
        Log.i(TAG, "AodDreamService detached from window")
    }
}
