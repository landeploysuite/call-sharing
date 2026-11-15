package com.callshare.ui

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.callshare.R
import com.callshare.audio.LiveAudioStreamEngine
import com.callshare.native.NativeBridge
import com.callshare.telecom.CallShareInCallService

class CallActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "CallActivity"
    }

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
        textCallQuality.text = "● Real-Time P2P PCM 16kHz | Hardware Echo Cancellation Active"

        // Start hardware AudioRecord capture and AudioTrack playback engine
        try {
            LiveAudioStreamEngine.getInstance().startStreaming(this)
            Log.i(TAG, "LiveAudioStreamEngine started for session: $sessionId")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start LiveAudioStreamEngine: ${t.message}", t)
        }

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
            NativeBridge.safeSetMicMute(isMuted)
            LiveAudioStreamEngine.getInstance().setMuted(isMuted)
            btnMute.isSelected = isMuted
        }

        btnSpeaker.setOnClickListener {
            isSpeakerOn = !isSpeakerOn
            btnSpeaker.isSelected = isSpeakerOn
            try {
                val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                audioManager?.isSpeakerphoneOn = isSpeakerOn
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to toggle speakerphone: ${t.message}")
            }
        }

        btnHangup.setOnClickListener {
            try {
                LiveAudioStreamEngine.getInstance().stopStreaming()
            } catch (t: Throwable) {
                Log.w(TAG, "Error stopping audio engine on hangup: ${t.message}")
            }

            if (CallShareInCallService.currentCall != null) {
                CallShareInCallService.rejectCurrentCall()
            } else {
                NativeBridge.safeHangupCall(sessionId, "user-hangup")
            }
            finish()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(durationRunnable)
        try {
            LiveAudioStreamEngine.getInstance().stopStreaming()
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.isSpeakerphoneOn = false
        } catch (t: Throwable) {
            Log.w(TAG, "Error cleaning up audio state in onDestroy: ${t.message}")
        }
        super.onDestroy()
    }
}
