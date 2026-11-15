package com.callshare.telecom

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import com.callshare.native.NativeBridge
import com.callshare.ui.AodActivity

class CallShareInCallService : InCallService() {

    private lateinit var audioRouteManager: AudioRouteManager
    private var isLocallyAnsweredViaBluetooth = false

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
        isLocallyAnsweredViaBluetooth = false

        val callerNumber = call.details.handle?.schemeSpecificPart ?: "Unknown Caller"
        val callerName = call.details.callerDisplayName ?: callerNumber

        Log.i(TAG, "onCallAdded: $callerNumber, State: ${call.state}")

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(c: Call, state: Int) {
                Log.d(TAG, "Call state changed: $state")
                when (state) {
                    Call.STATE_RINGING -> {
                        NativeBridge.notifyLocalCallRinging(sessionId, callerNumber, callerName, 0)
                        launchAodCallOverlay(sessionId, callerNumber, callerName)
                    }
                    Call.STATE_ACTIVE -> {
                        // Bug 2.1: Detect if answered directly on host via Bluetooth headset
                        if (isLocalBluetoothActive()) {
                            Log.i(TAG, "Call answered locally on host via Bluetooth headset. Revoking remote P2P relay.")
                            isLocallyAnsweredViaBluetooth = true
                            NativeBridge.notifyCallAnsweredLocally(sessionId)
                            return // Retain audio exclusively on local Bluetooth SCO link
                        }

                        Log.i(TAG, "Call active on Wi-Fi relay: starting bridged audio session")
                        audioRouteManager.startBridgedAudioSession()
                    }
                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Call disconnected")
                        if (!isLocallyAnsweredViaBluetooth) {
                            audioRouteManager.stopBridgedAudioSession()
                            NativeBridge.hangupCall(sessionId, "remote-hangup")
                        }
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

    /// Bug 2.1: Ownership lock checking if local Bluetooth headset answered
    private fun isLocalBluetoothActive(): Boolean {
        val audioState = callAudioState ?: return false
        return (audioState.route == CallAudioState.ROUTE_BLUETOOTH)
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
