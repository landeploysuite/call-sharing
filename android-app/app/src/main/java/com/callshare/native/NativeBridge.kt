package com.callshare.native

import android.util.Log

object NativeBridge {
    private const val TAG = "CallShareNativeBridge"

    init {
        try {
            System.loadLibrary("callshare_core")
            Log.i(TAG, "libcallshare_core.so loaded successfully")
        } catch (e: UnsatisfiedLinkError) {
            Log.e(TAG, "Failed to load libcallshare_core.so native library: ${e.message}")
        }
    }

    interface CallEventListener {
        fun onRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int)
        fun onCallConnected(sessionId: String)
        fun onCallTerminated(sessionId: String, reason: String)
        fun onPeersUpdated()
    }

    private var eventListener: CallEventListener? = null

    fun setCallEventListener(listener: CallEventListener?) {
        this.eventListener = listener
    }

    // Callbacks triggered from JNI
    @JvmStatic
    fun onNativeRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int) {
        eventListener?.onRemoteCallRinging(sessionId, callerNumber, callerName, simSlot)
    }

    @JvmStatic
    fun onNativeCallConnected(sessionId: String) {
        eventListener?.onCallConnected(sessionId)
    }

    @JvmStatic
    fun onNativeCallTerminated(sessionId: String, reason: String) {
        eventListener?.onCallTerminated(sessionId, reason)
    }

    // Native JNI Declarations
    external fun initNativeCore(
        deviceId: String,
        deviceName: String,
        localIp: String,
        carrierName: String,
        simSlot: Int
    ): Boolean

    external fun startDiscovery()
    external fun stopDiscovery()
    external fun getDiscoveredPeersJson(): String
    external fun connectToPeer(peerIp: String, signalingPort: Int, mediaPort: Int): Boolean

    external fun notifyLocalCallRinging(
        sessionId: String,
        callerNumber: String,
        callerName: String,
        simSlot: Int
    ): Boolean

    external fun answerRelayedCall(sessionId: String)
    external fun rejectRelayedCall(sessionId: String, reason: String)
    external fun hangupCall(sessionId: String, reason: String)
    external fun setMicMute(muted: Boolean)

    external fun onSensorStateChanged(isNear: Boolean)
    external fun setDeviceOrientation(orientation: Int)
    external fun getAodShiftOffset(): IntArray

    external fun processMicCapture(pcmBytes: ByteArray)
    external fun pullPlaybackPcm(samplesCount: Int): ShortArray
}
