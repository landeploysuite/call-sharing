package com.callshare.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.callshare.native.NativeBridge
import com.callshare.ui.AodActivity
import com.callshare.ui.CallActivity

class CallShareInCallService : InCallService() {

    private lateinit var audioRouteManager: AudioRouteManager

    companion object {
        private const val TAG = "CallShareInCallService"
        var currentCall: Call? = null
            private set
        var currentSessionId: String? = null
            private set

        fun answerCurrentCall() {
            currentCall?.let {
                it.answer(VideoProfile.STATE_AUDIO_ONLY)
                Log.i(TAG, "Telephony Call.answer invoked")
            }
        }

        fun rejectCurrentCall() {
            currentCall?.let {
                if (it.state == Call.STATE_RINGING) {
                    it.reject(false, null)
                } else {
                    it.disconnect()
                }
                Log.i(TAG, "Telephony Call.reject/disconnect invoked")
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        audioRouteManager = AudioRouteManager(applicationContext)
        Log.i(TAG, "CallShareInCallService created")
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        currentCall = call
        val sessionId = "sim-call-${System.currentTimeMillis()}"
        currentSessionId = sessionId

        val callerNumber = call.details.handle?.schemeSpecificPart ?: "Unknown Caller"
        val callerName = call.details.callerDisplayName ?: callerNumber

        Log.i(TAG, "onCallAdded: $callerNumber, State: ${call.state}")

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                Log.d(TAG, "Call state changed: $state")
                when (state) {
                    Call.STATE_RINGING -> {
                        // Notify Rust native core -> dispatches CALL_INCOMING over offline Wi-Fi
                        NativeBridge.notifyLocalCallRinging(sessionId, callerNumber, callerName, 0)
                        launchAodCallOverlay(sessionId, callerNumber, callerName)
                    }
                    Call.STATE_ACTIVE -> {
                        Log.i(TAG, "Call active: starting bridged audio session")
                        audioRouteManager.startBridgedAudioSession()
                    }
                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Call disconnected")
                        audioRouteManager.stopBridgedAudioSession()
                        NativeBridge.hangupCall(sessionId, "remote-hangup")
                        currentCall = null
                        currentSessionId = null
                    }
                }
            }
        })

        if (call.state == Call.STATE_RINGING) {
            NativeBridge.notifyLocalCallRinging(sessionId, callerNumber, callerName, 0)
            launchAodCallOverlay(sessionId, callerNumber, callerName)
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        audioRouteManager.stopBridgedAudioSession()
        if (currentCall == call) {
            currentCall = null
            currentSessionId = null
        }
    }

    private fun launchAodCallOverlay(sessionId: String, callerNumber: String, callerName: String) {
        val intent = Intent(this, AodActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_SESSION_ID", sessionId)
            putExtra("EXTRA_CALLER_NUMBER", callerNumber)
            putExtra("EXTRA_CALLER_NAME", callerName)
            putExtra("EXTRA_IS_RINGING", true)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        audioRouteManager.stopBridgedAudioSession()
        super.onDestroy()
    }
}
