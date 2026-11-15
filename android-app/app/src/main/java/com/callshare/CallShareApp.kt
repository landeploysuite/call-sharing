package com.callshare

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.callshare.native.NativeBridge
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class CallShareApp : Application() {

    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        const val TAG = "CallShareApp"
        lateinit var instance: CallShareApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        try {
            acquireMulticastLock()
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock acquisition bypassed: ${e.message}")
        }

        try {
            initRustNativeCore()
        } catch (e: Throwable) {
            Log.e(TAG, "Native core initialization deferred: ${e.message}")
        }
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("CallShareMulticastLock")?.apply {
            setReferenceCounted(true)
            acquire()
            Log.i(TAG, "Wi-Fi MulticastLock acquired for offline UDP discovery")
        }
    }

    fun initRustNativeCore() {
        if (!NativeBridge.isLoaded) {
            Log.w(TAG, "Native library not ready yet")
            return
        }

        val sharedPrefs = getSharedPreferences("callshare_prefs", Context.MODE_PRIVATE)
        var deviceId = sharedPrefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", deviceId).apply()
        }

        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val localIp = getLocalWifiIpAddress() ?: "127.0.0.1"

        // Determine SIM carrier name & slot safely without crashing on missing permissions
        var carrierName = "Cellular SIM"
        var simSlot = 0
        try {
            val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                val opName = telephonyManager.networkOperatorName
                if (!opName.isNullOrBlank()) {
                    carrierName = opName
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Carrier info read deferred until permission granted: ${e.message}")
        }

        Log.i(TAG, "Initializing Native Core: ID=$deviceId, Name=$deviceName, IP=$localIp, Carrier=$carrierName")
        val success = NativeBridge.safeInitNativeCore(deviceId, deviceName, localIp, carrierName, simSlot)
        if (success) {
            NativeBridge.safeStartDiscovery()
            Log.i(TAG, "Native Rust Core initialized and discovery running")
        } else {
            Log.w(TAG, "Native Core will re-initialize after permissions granted")
        }
    }

    fun getLocalWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return null
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        return addr.hostAddress
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error obtaining local IP: ${e.message}")
        }
        return null
    }

    override fun onTerminate() {
        try {
            NativeBridge.safeStopDiscovery()
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error during termination: ${e.message}")
        }
        super.onTerminate()
    }
}
