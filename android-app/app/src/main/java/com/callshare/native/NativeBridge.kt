package com.callshare.native

import android.util.Log
import java.nio.ByteBuffer

object NativeBridge {
    private const val TAG = "CallShareNativeBridge"
    var isLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("callshare_core")
            isLoaded = true
            Log.i(TAG, "libcallshare_core.so loaded successfully")
        } catch (e: Throwable) {
            isLoaded = false
            Log.e(TAG, "Failed to load libcallshare_core.so native library: ${e.message}")
        }
    }

    interface CallEventListener {
        fun onRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int)
        fun onCallConnected(sessionId: String)
        fun onCallTerminated(sessionId: String, reason: String)
        fun onCallRevoked(sessionId: String, reason: String)
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

    @JvmStatic
    fun onNativeCallRevoked(sessionId: String, reason: String) {
        eventListener?.onCallRevoked(sessionId, reason)
    }

    // Safe wrappers preventing crash if native core is unavailable
    fun safeInitNativeCore(
        deviceId: String,
        deviceName: String,
        localIp: String,
        carrierName: String,
        simSlot: Int
    ): Boolean {
        if (!isLoaded) return false
        return try {
            initNativeCore(deviceId, deviceName, localIp, carrierName, simSlot)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in initNativeCore: ${e.message}")
            false
        }
    }

    fun safeGetDiscoveredPeersJson(): String {
        if (!isLoaded) return "[]"
        return try {
            getDiscoveredPeersJson()
        } catch (e: Throwable) {
            "[]"
        }
    }

    fun safeStartDiscovery() {
        if (!isLoaded) return
        try {
            startDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in startDiscovery: ${e.message}")
        }
    }

    fun safeStopDiscovery() {
        if (!isLoaded) return
        try {
            stopDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in stopDiscovery: ${e.message}")
        }
    }

    fun safeGetAodShiftOffset(): IntArray {
        if (!isLoaded) return intArrayOf(0, 0)
        return try {
            getAodShiftOffset()
        } catch (e: Throwable) {
            intArrayOf(0, 0)
        }
    }

    fun safeGetBurnInOffset(): IntArray {
        return safeGetAodShiftOffset()
    }

    fun safeSetAodOrientation(mode: Int) {
        if (!isLoaded) return
        try {
            setAodOrientation(mode)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setAodOrientation: ${e.message}")
        }
    }

    fun safeSetOrbitSpeedMultiplier(multiplier: Float) {
        if (!isLoaded) return
        try {
            setOrbitSpeedMultiplier(multiplier)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setOrbitSpeedMultiplier: ${e.message}")
        }
    }

    fun safeIsApIsolated(): Boolean {
        if (!isLoaded) return false
        return try {
            isApIsolated()
        } catch (e: Throwable) {
            false
        }
    }

    // Native JNI Declarations with @JvmStatic
    @JvmStatic external fun initNativeCore(
        deviceId: String,
        deviceName: String,
        localIp: String,
        carrierName: String,
        simSlot: Int
    ): Boolean

    @JvmStatic external fun startDiscovery()
    @JvmStatic external fun stopDiscovery()
    @JvmStatic external fun isApIsolated(): Boolean
    @JvmStatic external fun getDiscoveredPeersJson(): String
    @JvmStatic external fun connectToPeer(peerIp: String, signalingPort: Int, mediaPort: Int): Boolean

    @JvmStatic external fun notifyLocalCallRinging(
        sessionId: String,
        callerNumber: String,
        callerName: String,
        simSlot: Int
    ): Boolean

    @JvmStatic external fun notifyCallAnsweredLocally(sessionId: String)

    @JvmStatic external fun answerRelayedCall(sessionId: String)
    @JvmStatic external fun rejectRelayedCall(sessionId: String, reason: String)
    @JvmStatic external fun hangupCall(sessionId: String, reason: String)
    @JvmStatic external fun setMicMute(muted: Boolean)

    @JvmStatic external fun onSensorStateChanged(isNear: Boolean)
    @JvmStatic external fun setDeviceOrientation(orientation: Int)
    @JvmStatic external fun setAodOrientation(mode: Int)
    @JvmStatic external fun setOrbitSpeedMultiplier(multiplier: Float)
    @JvmStatic external fun getAodShiftOffset(): IntArray
    @JvmStatic external fun getBurnInOffset(): IntArray

    // Bug 2.2: Zero-allocation direct ByteBuffer memory transfers
    @JvmStatic external fun pullPlaybackPcmDirect(directBuffer: ByteBuffer, samplesCount: Int): Int
    @JvmStatic external fun processMicCaptureDirect(directBuffer: ByteBuffer, bytesCount: Int)

    // Fallback array JNI methods
    @JvmStatic external fun processMicCapture(pcmBytes: ByteArray)
    @JvmStatic external fun pullPlaybackPcm(samplesCount: Int): ShortArray
}
