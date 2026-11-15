package com.callshare.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
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
    private var lightSensor: Sensor? = null
    private var lastProximityNear: Boolean = false
    private var lastLux: Float = 50f

    private lateinit var aodContainer: View
    private lateinit var draggableCanvas: DraggableAodCanvasView
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

    /// Bug 4.1: Decouple clock/battery IPC Binder calls from rendering; strict 1 FPS (1000ms) interval
    private val clockTickRunnable = object : Runnable {
        override fun run() {
            updateClockAndBattery()
            applyBurnInPixelShift()
            mainHandler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupAodWindowFlags()

        setContentView(R.layout.activity_aod)
        bindViews()
        setupSensors()
        updateOrientationState(resources.configuration.orientation)
        setupCallEventListener()

        handleIntent(intent)
        mainHandler.post(clockTickRunnable)
    }

    private fun setupCallEventListener() {
        NativeBridge.setCallEventListener(object : NativeBridge.CallEventListener {
            override fun onRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int) {
                runOnUiThread {
                    currentSessionId = sessionId
                    showIncomingCallAlert(callerName, callerNumber)
                }
            }

            override fun onCallConnected(sessionId: String) {
                runOnUiThread {
                    hideIncomingCallAlert()
                }
            }

            override fun onCallTerminated(sessionId: String, reason: String) {
                runOnUiThread {
                    hideIncomingCallAlert()
                }
            }

            override fun onCallRevoked(sessionId: String, reason: String) {
                runOnUiThread {
                    // Host answered locally on Bluetooth; dismiss AOD alert
                    hideIncomingCallAlert()
                }
            }

            override fun onPeersUpdated() {}
        })
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

        val isEditMode = intent.getBooleanExtra("EXTRA_START_EDIT_MODE", false)
        val newStickerPath = intent.getStringExtra("EXTRA_NEW_STICKER_PATH")

        if (isEditMode || newStickerPath != null) {
            aodContainer.visibility = View.GONE
            draggableCanvas.visibility = View.VISIBLE
            draggableCanvas.isEditMode = isEditMode
            if (newStickerPath != null) {
                draggableCanvas.addStickerWidget(newStickerPath)
            }
        }

        if (isCallRinging && !callerNumber.isNullOrBlank()) {
            showIncomingCallAlert(callerName ?: "Unknown", callerNumber)
        } else if (!isEditMode && newStickerPath == null) {
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

        val prefs = getSharedPreferences("callshare_settings_prefs", Context.MODE_PRIVATE)
        val dimmingProgress = prefs.getInt("pref_screen_dimming", 1)
        val lp = window.attributes
        lp.screenBrightness = (dimmingProgress / 100f).coerceIn(0.01f, 0.20f)
        window.attributes = lp

        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun bindViews() {
        aodContainer = findViewById(R.id.aod_content_container)
        draggableCanvas = findViewById(R.id.draggable_aod_canvas)
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
        try {
            sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
            proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
            lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT)
        } catch (t: Throwable) {
            android.util.Log.w("AodActivity", "Error accessing hardware sensors: ${t.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        val prefs = getSharedPreferences("callshare_settings_prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("pref_proximity_gate", true)) {
            proximitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
            lightSensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        val orientMode = prefs.getInt("pref_aod_orientation_lock", 0)
        when (orientMode) {
            1 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            2 -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            else -> requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            sensorManager.unregisterListener(this)
        } catch (t: Throwable) {
            android.util.Log.w("AodActivity", "Error unregistering sensors: ${t.message}")
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateOrientationState(newConfig.orientation)
    }

    private fun updateOrientationState(orientation: Int) {
        val nativeOrientation = if (orientation == Configuration.ORIENTATION_LANDSCAPE) 1 else 0
        NativeBridge.safeSetDeviceOrientation(nativeOrientation)
    }

    // -------------------------------------------------------------------------
    // HARDWARE SENSOR GATING (Proximity & Light Sensor Face-down / Pocket Power Preservation)
    // -------------------------------------------------------------------------
    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        if (event.sensor.type == Sensor.TYPE_PROXIMITY) {
            val distance = event.values[0]
            val maxRange = proximitySensor?.maximumRange ?: 5f
            lastProximityNear = (distance < maxRange && distance < 4f)
            applySensorGating()
        } else if (event.sensor.type == Sensor.TYPE_LIGHT) {
            lastLux = event.values[0]
            applySensorGating()
        }
    }

    private fun applySensorGating() {
        // Device is covered, face down, or in a pocket when proximity is near or light is pitch dark with near proximity
        val isCovered = lastProximityNear || (lastLux <= 1f && lastProximityNear)

        NativeBridge.safeOnSensorStateChanged(isCovered)

        if (isCovered) {
            aodContainer.visibility = View.GONE
            val lp = window.attributes
            lp.screenBrightness = 0.0f
            window.attributes = lp
        } else {
            aodContainer.visibility = View.VISIBLE
            val prefs = getSharedPreferences("callshare_settings_prefs", Context.MODE_PRIVATE)
            val dimmingProgress = prefs.getInt("pref_screen_dimming", 1)
            val lp = window.attributes
            lp.screenBrightness = if (isCallRinging) 0.8f else (dimmingProgress / 100f).coerceIn(0.01f, 0.20f)
            window.attributes = lp
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // -------------------------------------------------------------------------
    // ANTI-BURN-IN PIXEL SHIFTING
    // -------------------------------------------------------------------------
    private fun applyBurnInPixelShift() {
        val offsets = NativeBridge.safeGetAodShiftOffset()
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

        try {
            val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val batteryPct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 100
            textBattery.text = "$batteryPct%"
        } catch (e: Exception) {
            textBattery.text = "--%"
        }

        textMeshStatus.text = "● P2P LAN Mesh Active"
    }

    /// Bug 4.1: RenderThread GPU accelerated pulse animation without Main Thread IPC load
    private fun showIncomingCallAlert(callerName: String, callerNumber: String) {
        isCallRinging = true
        layoutIncomingCall.visibility = View.VISIBLE
        textCallerName.text = callerName
        textCallerNumber.text = callerNumber

        val lp = window.attributes
        lp.screenBrightness = 0.8f
        window.attributes = lp

        if (pulseAnimator == null) {
            pulseAnimator = ObjectAnimator.ofFloat(layoutIncomingCall, "alpha", 0.45f, 1.0f).apply {
                duration = 750
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

        val prefs = getSharedPreferences("callshare_settings_prefs", Context.MODE_PRIVATE)
        val dimmingProgress = prefs.getInt("pref_screen_dimming", 1)
        val lp = window.attributes
        lp.screenBrightness = (dimmingProgress / 100f).coerceIn(0.01f, 0.20f)
        window.attributes = lp
    }

    private fun onAnswerClicked() {
        val sId = currentSessionId ?: "session-${System.currentTimeMillis()}"
        if (CallShareInCallService.currentCall != null) {
            CallShareInCallService.answerCurrentCall()
        } else {
            NativeBridge.safeAnswerRelayedCall(sId)
        }

        hideIncomingCallAlert()

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
            NativeBridge.safeRejectRelayedCall(sId, "user-declined")
        }
        hideIncomingCallAlert()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(clockTickRunnable)
        pulseAnimator?.cancel()
        super.onDestroy()
    }
}
