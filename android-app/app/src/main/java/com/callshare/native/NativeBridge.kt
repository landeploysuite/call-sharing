package com.callshare.native

import android.util.Log
import androidx.annotation.Keep
import java.nio.ByteBuffer

@Keep
object NativeBridge {
    private const val TAG = "CallShareNativeBridge"

    @Volatile
    var isNativeLoaded: Boolean = false
        private set

    // Backward compatibility alias for isNativeLoaded
    val isLoaded: Boolean
        get() = isNativeLoaded

    @Volatile
    var nativeLoadError: String? = null
        private set

    init {
        try {
            System.loadLibrary("callshare_core")
            isNativeLoaded = true
            Log.i(TAG, "libcallshare_core.so loaded successfully")
        } catch (t: Throwable) {
            isNativeLoaded = false
            nativeLoadError = t.stackTraceToString()
            Log.e(TAG, "Failed to load libcallshare_core.so native library:\n$nativeLoadError")
        }
    }

    interface CallEventListener {
        fun onRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int)
        fun onCallConnected(sessionId: String)
        fun onCallTerminated(sessionId: String, reason: String)
        fun onCallRevoked(sessionId: String, reason: String)
        fun onPeersUpdated()
        fun onTelemetryUpdate(rttMs: Float, lossPercent: Float, jitterMs: Float) {}
    }

    private var eventListener: CallEventListener? = null

    fun setCallEventListener(listener: CallEventListener?) {
        this.eventListener = listener
    }

    // -------------------------------------------------------------------------
    // Core Reverse Callbacks Invoked from Rust JNI via call_static_method
    // -------------------------------------------------------------------------

    @Keep
    @JvmStatic
    fun onNativeRemoteCallRinging(sessionId: String, callerNumber: String, callerName: String, simSlot: Int) {
        try {
            eventListener?.onRemoteCallRinging(sessionId, callerNumber, callerName, simSlot)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeRemoteCallRinging callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativeCallConnected(sessionId: String) {
        try {
            eventListener?.onCallConnected(sessionId)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeCallConnected callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativeCallTerminated(sessionId: String, reason: String) {
        try {
            eventListener?.onCallTerminated(sessionId, reason)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeCallTerminated callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativeCallRevoked(sessionId: String, reason: String) {
        try {
            eventListener?.onCallRevoked(sessionId, reason)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeCallRevoked callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativePeerDiscovered(peerId: String, peerName: String, ip: String, port: Int) {
        try {
            eventListener?.onPeersUpdated()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativePeerDiscovered callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativePeerLost(peerId: String) {
        try {
            eventListener?.onPeersUpdated()
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativePeerLost callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativeIncomingCall(sessionId: String, callerNumber: String, callerName: String, simSlot: Int) {
        onNativeRemoteCallRinging(sessionId, callerNumber, callerName, simSlot)
    }

    @Keep
    @JvmStatic
    fun onNativeCallStateChanged(sessionId: String, state: Int, reason: String) {
        try {
            when (state) {
                1 -> onNativeCallConnected(sessionId)
                2 -> onNativeCallTerminated(sessionId, reason)
                3 -> onNativeCallRevoked(sessionId, reason)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeCallStateChanged callback: ${t.message}", t)
        }
    }

    @Keep
    @JvmStatic
    fun onNativeTelemetryUpdate(rttMs: Float, lossPercent: Float, jitterMs: Float) {
        try {
            eventListener?.onTelemetryUpdate(rttMs, lossPercent, jitterMs)
        } catch (t: Throwable) {
            Log.e(TAG, "Exception inside onNativeTelemetryUpdate callback: ${t.message}", t)
        }
    }

    // -------------------------------------------------------------------------
    // Defensive Kotlin Wrappers Guarding Native Invocations
    // -------------------------------------------------------------------------

    fun safeInitNativeCore(
        deviceId: String,
        deviceName: String,
        localIp: String,
        carrierName: String,
        simSlot: Int
    ): Boolean {
        if (!isNativeLoaded) {
            Log.w(TAG, "safeInitNativeCore bypassed: native library not loaded ($nativeLoadError)")
            return false
        }
        return try {
            initNativeCore(deviceId, deviceName, localIp, carrierName, simSlot)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in initNativeCore: ${e.message}", e)
            false
        }
    }

    fun safeGetDiscoveredPeersJson(): String {
        if (!isNativeLoaded) return "[]"
        return try {
            getDiscoveredPeersJson()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in getDiscoveredPeersJson: ${e.message}")
            "[]"
        }
    }

    fun safeStartDiscovery() {
        if (!isNativeLoaded) return
        try {
            startDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in startDiscovery: ${e.message}", e)
        }
    }

    fun safeStopDiscovery() {
        if (!isNativeLoaded) return
        try {
            stopDiscovery()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in stopDiscovery: ${e.message}", e)
        }
    }

    fun safeConnectToPeer(peerIp: String, signalingPort: Int, mediaPort: Int): Boolean {
        if (!isNativeLoaded) return false
        return try {
            connectToPeer(peerIp, signalingPort, mediaPort)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in connectToPeer: ${e.message}", e)
            false
        }
    }

    fun safeNotifyLocalCallRinging(
        sessionId: String,
        callerNumber: String,
        callerName: String,
        simSlot: Int
    ): Boolean {
        if (!isNativeLoaded) return false
        return try {
            notifyLocalCallRinging(sessionId, callerNumber, callerName, simSlot)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in notifyLocalCallRinging: ${e.message}", e)
            false
        }
    }

    fun safeNotifyCallAnsweredLocally(sessionId: String) {
        if (!isNativeLoaded) return
        try {
            notifyCallAnsweredLocally(sessionId)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in notifyCallAnsweredLocally: ${e.message}", e)
        }
    }

    fun safeAnswerRelayedCall(sessionId: String) {
        if (!isNativeLoaded) return
        try {
            answerRelayedCall(sessionId)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in answerRelayedCall: ${e.message}", e)
        }
    }

    fun safeRejectRelayedCall(sessionId: String, reason: String) {
        if (!isNativeLoaded) return
        try {
            rejectRelayedCall(sessionId, reason)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in rejectRelayedCall: ${e.message}", e)
        }
    }

    fun safeHangupCall(sessionId: String, reason: String) {
        if (!isNativeLoaded) return
        try {
            hangupCall(sessionId, reason)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in hangupCall: ${e.message}", e)
        }
    }

    fun safeSetMicMute(muted: Boolean) {
        if (!isNativeLoaded) return
        try {
            setMicMute(muted)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setMicMute: ${e.message}", e)
        }
    }

    fun safeOnSensorStateChanged(isNear: Boolean) {
        if (!isNativeLoaded) return
        try {
            onSensorStateChanged(isNear)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in onSensorStateChanged: ${e.message}", e)
        }
    }

    fun safeSetDeviceOrientation(orientation: Int) {
        if (!isNativeLoaded) return
        try {
            setDeviceOrientation(orientation)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setDeviceOrientation: ${e.message}", e)
        }
    }

    fun safeSetAodOrientation(mode: Int) {
        if (!isNativeLoaded) return
        try {
            setAodOrientation(mode)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setAodOrientation: ${e.message}", e)
        }
    }

    fun safeSetOrbitSpeedMultiplier(multiplier: Float) {
        if (!isNativeLoaded) return
        try {
            setOrbitSpeedMultiplier(multiplier)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in setOrbitSpeedMultiplier: ${e.message}", e)
        }
    }

    fun safeGetAodShiftOffset(): IntArray {
        if (!isNativeLoaded) return intArrayOf(0, 0)
        return try {
            getAodShiftOffset()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in getAodShiftOffset: ${e.message}")
            intArrayOf(0, 0)
        }
    }

    fun safeGetBurnInOffset(): IntArray {
        return safeGetAodShiftOffset()
    }

    fun safeIsApIsolated(): Boolean {
        if (!isNativeLoaded) return false
        return try {
            isApIsolated()
        } catch (e: Throwable) {
            Log.e(TAG, "Error in isApIsolated: ${e.message}")
            false
        }
    }

    fun safePullPlaybackPcmDirect(directBuffer: ByteBuffer, samplesCount: Int): Int {
        if (!isNativeLoaded) return 0
        return try {
            pullPlaybackPcmDirect(directBuffer, samplesCount)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in pullPlaybackPcmDirect: ${e.message}", e)
            0
        }
    }

    fun safeProcessMicCaptureDirect(directBuffer: ByteBuffer, bytesCount: Int) {
        if (!isNativeLoaded) return
        try {
            processMicCaptureDirect(directBuffer, bytesCount)
        } catch (e: Throwable) {
            Log.e(TAG, "Error in processMicCaptureDirect: ${e.message}", e)
        }
    }

    // -------------------------------------------------------------------------
    // Raw JNI Declarations Annotated with @Keep and @JvmStatic
    // -------------------------------------------------------------------------

    @Keep @JvmStatic external fun initNativeCore(
        deviceId: String,
        deviceName: String,
        localIp: String,
        carrierName: String,
        simSlot: Int
    ): Boolean

    @Keep @JvmStatic external fun startDiscovery()
    @Keep @JvmStatic external fun stopDiscovery()
    @Keep @JvmStatic external fun isApIsolated(): Boolean
    @Keep @JvmStatic external fun getDiscoveredPeersJson(): String
    @Keep @JvmStatic external fun connectToPeer(peerIp: String, signalingPort: Int, mediaPort: Int): Boolean

    @Keep @JvmStatic external fun notifyLocalCallRinging(
        sessionId: String,
        callerNumber: String,
        callerName: String,
        simSlot: Int
    ): Boolean

    @Keep @JvmStatic external fun notifyCallAnsweredLocally(sessionId: String)
    @Keep @JvmStatic external fun answerRelayedCall(sessionId: String)
    @Keep @JvmStatic external fun rejectRelayedCall(sessionId: String, reason: String)
    @Keep @JvmStatic external fun hangupCall(sessionId: String, reason: String)
    @Keep @JvmStatic external fun setMicMute(muted: Boolean)

    @Keep @JvmStatic external fun onSensorStateChanged(isNear: Boolean)
    @Keep @JvmStatic external fun setDeviceOrientation(orientation: Int)
    @Keep @JvmStatic external fun setAodOrientation(mode: Int)
    @Keep @JvmStatic external fun setOrbitSpeedMultiplier(multiplier: Float)
    @Keep @JvmStatic external fun getAodShiftOffset(): IntArray
    @Keep @JvmStatic external fun getBurnInOffset(): IntArray

    // Bug 2.2: Zero-allocation direct ByteBuffer memory transfers
    @Keep @JvmStatic external fun pullPlaybackPcmDirect(directBuffer: ByteBuffer, samplesCount: Int): Int
    @Keep @JvmStatic external fun processMicCaptureDirect(directBuffer: ByteBuffer, bytesCount: Int)

    // Fallback array JNI methods
    @Keep @JvmStatic external fun processMicCapture(pcmBytes: ByteArray)
    @Keep @JvmStatic external fun pullPlaybackPcm(samplesCount: Int): ShortArray
}
