package com.callshare.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.callshare.R
import com.callshare.native.NativeBridge
import com.callshare.telecom.CallShareInCallService

class CallActivity : AppCompatActivity() {

    private lateinit var textCallerName: TextView
    private lateinit var textCallerNumber: TextView
    private lateinit var textCallDuration: TextView
    private lateinit var textCallQuality: TextView
    private lateinit var btnMute: ImageButton
    private lateinit var btnSpeaker: ImageButton
    private lateinit var btnHangup: ImageButton

    private var sessionId: String = ""
    private var isMuted = false
    private var isSpeakerOn = false
    private var callDurationSeconds = 0

    private val mainHandler = Handler(Looper.getMainLooper())
    private val durationRunnable = object : Runnable {
        override fun run() {
            callDurationSeconds++
            val minutes = callDurationSeconds / 60
            val seconds = callDurationSeconds % 60
            textCallDuration.text = String.format("%02d:%02d", minutes, seconds)
            mainHandler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_call)

        sessionId = intent.getStringExtra("EXTRA_SESSION_ID") ?: ""
        val callerName = intent.getStringExtra("EXTRA_CALLER_NAME") ?: "Caller"
        val callerNumber = intent.getStringExtra("EXTRA_CALLER_NUMBER") ?: ""

        bindViews()

        textCallerName.text = callerName
        textCallerNumber.text = callerNumber
        textCallQuality.text = "● P2P Latency: <35ms | Opus 16kHz"

        mainHandler.postDelayed(durationRunnable, 1000)
    }

    private fun bindViews() {
        textCallerName = findViewById(R.id.text_call_name)
        textCallerNumber = findViewById(R.id.text_call_number)
        textCallDuration = findViewById(R.id.text_call_duration)
        textCallQuality = findViewById(R.id.text_call_quality)
        btnMute = findViewById(R.id.btn_call_mute)
        btnSpeaker = findViewById(R.id.btn_call_speaker)
        btnHangup = findViewById(R.id.btn_call_hangup)

        btnMute.setOnClickListener {
            isMuted = !isMuted
            NativeBridge.setMicMute(isMuted)
            btnMute.isSelected = isMuted
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            btnSpeaker.isSelected = isSpeakerOn
        }

        btnHangup.setOnClickListener {
            if (CallShareInCallService.currentCall != null) {
                CallShareInCallService.rejectCurrentCall()
            } else {
                NativeBridge.hangupCall(sessionId, "user-hangup")
            }
            finish()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(durationRunnable)
        super.onDestroy()
    }
}
