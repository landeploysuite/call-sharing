package com.callshare.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.callshare.R
import com.callshare.native.NativeBridge
import com.callshare.telecom.CallShareInCallService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AodActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null

    private lateinit var aodContainer: View
    private lateinit var textClock: TextView
    private lateinit var textDate: TextView
    private lateinit var textBattery: TextView
    private lateinit var textMeshStatus: TextView
    private lateinit var layoutIncomingCall: LinearLayout
    private lateinit var textCallerName: TextView
    private lateinit var textCallerNumber: TextView
    private lateinit var btnAnswer: Button
    private lateinit var btnReject: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private var isCallRinging = false
    private var currentSessionId: String? = null
    private var pulseAnimator: ObjectAnimator? = null

    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            applyBurnInPixelShift()

            // Refresh rate throttling: 1 FPS (1000ms) for idle clock, 33ms (30 FPS) for ringing pulse
            val delayMs = if (isCallRinging) 33L else 1000L
            mainHandler.postDelayed(this, delayMs)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupAodWindowFlags()

        setContentView(R.layout.activity_aod)
        bindViews()
        setupSensors()
        updateOrientationState(resources.configuration.orientation)

        handleIntent(intent)
        mainHandler.post(clockTickRunnable)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        currentSessionId = intent.getStringExtra("EXTRA_SESSION_ID")
        val callerNumber = intent.getStringExtra("EXTRA_CALLER_NUMBER")
        val callerName = intent.getStringExtra("EXTRA_CALLER_NAME") ?: callerNumber
        isCallRinging = intent.getBooleanExtra("EXTRA_IS_RINGING", false)

        if (isCallRinging && !callerNumber.isNullOrBlank()) {
            showIncomingCallAlert(callerName ?: "Unknown", callerNumber)
        } else {
            hideIncomingCallAlert()
        }
    }

    private fun setupAodWindowFlags() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )

        // Ultra-low brightness for LCD battery preservation
        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp

        // Full screen immersive mode (hide system bars)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun bindViews() {
        aodContainer = findViewById(R.id.aod_content_container)
        textClock = findViewById(R.id.text_aod_clock)
        textDate = findViewById(R.id.text_aod_date)
        textBattery = findViewById(R.id.text_aod_battery)
        textMeshStatus = findViewById(R.id.text_aod_mesh_status)
        layoutIncomingCall = findViewById(R.id.layout_incoming_call)
        textCallerName = findViewById(R.id.text_caller_name)
        textCallerNumber = findViewById(R.id.text_caller_number)
        btnAnswer = findViewById(R.id.btn_aod_answer)
        btnReject = findViewById(R.id.btn_aod_reject)

        btnAnswer.setOnClickListener {
            onAnswerClicked()
        }

        btnReject.setOnClickListener {
            onRejectClicked()
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    override fun onResume() {
        super.onResume()
        proximitySensor?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationState(newConfig.orientation)
    }

    private fun updateOrientationState(orientation: Int) {
        val nativeOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 0
        NativeBridge.setDeviceOrientation(nativeOrientation)
    }

    // -------------------------------------------------------------------------
    // SENSOR GATING (Face-down / Pocket Power Preservation)
    // -------------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            val isNear = distance < maxRange && distance < 4f

            NativeBridge.onSensorStateChanged(isNear)

            if (isNear) {
                // Screen blanked: phone in pocket or placed face-down
                aodContainer.visibility = View.GONE
                val lp = window.attributes
                lp.screenBrightness = 0.0f
                window.attributes = lp
            } else {
                // Restore screen: phone picked up
                aodContainer.visibility = View.VISIBLE
                val lp = window.attributes
                lp.screenBrightness = if (isCallRinging) 0.8f else 0.01f
                window.attributes = lp
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -------------------------------------------------------------------------
    // ANTI-BURN-IN PIXEL SHIFTING
    // -------------------------------------------------------------------------
    private fun applyBurnInPixelShift() {
        val offsets = NativeBridge.getAodShiftOffset()
        if (offsets.size >= 2) {
            val dx = offsets[0].toFloat()
            val dy = offsets[1].toFloat()
            aodContainer.translationX = dx
            aodContainer.translationY = dy
        }
    }

    private fun updateClockAndBattery() {
        val now = Date()
        textClock.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(now)
        textDate.text = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)

        val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val batteryPct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        textBattery.text = "$batteryPct%"

        textMeshStatus.text = "● P2P LAN Mesh Active"
    }

    private fun showIncomingCallAlert(callerName: String, callerNumber: String) {
        layoutIncomingCall.visibility = View.VISIBLE
        textCallerName.text = callerName
        textCallerNumber.text = callerNumber

        // Boost brightness for incoming call
        val lp = window.attributes
        lp.screenBrightness = 0.8f
        window.attributes = lp

        // Smooth pulse animation (30 FPS)
        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(layoutIncomingCall, "alpha", 0.5f, 1.0f).apply {
                duration = 800
                repeatMode = ValueAnimator.REVERSE
                repeatCount = ValueAnimator.INFINITE
                start()
            }
        }
    }

    private fun hideIncomingCallAlert() {
        isCallRinging = false
        layoutIncomingCall.visibility = View.GONE
        pulseAnimator?.cancel()
        pulseAnimator = null

        val lp = window.attributes
        lp.screenBrightness = 0.01f
        window.attributes = lp
    }

    private fun onAnswerClicked() {
        val sId = currentSessionId ?: "session-${System.currentTimeMillis()}"
        if (CallShareInCallService.currentCall != null) {
            CallShareInCallService.answerCurrentCall()
        } else {
            NativeBridge.answerRelayedCall(sId)
        }

        hideIncomingCallAlert()

        // Transition to in-call activity
        val intent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_SESSION_ID", sId)
            putExtra("EXTRA_CALLER_NAME", textCallerName.text.toString())
            putExtra("EXTRA_CALLER_NUMBER", textCallerNumber.text.toString())
        }
        startActivity(intent)
        finish()
    }

    private fun onRejectClicked() {
        val sId = currentSessionId ?: ""
        if (CallShareInCallService.currentCall != null) {
            CallShareInCallService.rejectCurrentCall()
        } else {
            NativeBridge.rejectRelayedCall(sId, "user-declined")
        }
        hideIncomingCallAlert()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clockTickRunnable)
        pulseAnimator?.cancel()
        super.onDestroy()
    }
}
