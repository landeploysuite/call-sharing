package com.callshare

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.SubscriptionManager
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

        acquireMulticastLock()
        initRustNativeCore()
    }

    private fun acquireMulticastLock() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        multicastLock = wifiManager?.createMulticastLock("CallShareMulticastLock")?.apply {
            setReferenceCounted(true)
            acquire()
            Log.i(TAG, "Wi-Fi MulticastLock acquired for offline UDP discovery")
        }
    }

    private fun initRustNativeCore() {
        val sharedPrefs = getSharedPreferences("callshare_prefs", Context.MODE_PRIVATE)
        var deviceId = sharedPrefs.getString("device_id", null)
        if (deviceId == null) {
            deviceId = UUID.randomUUID().toString()
            sharedPrefs.edit().putString("device_id", deviceId).apply()
        }

        val deviceName = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
        val localIp = getLocalWifiIpAddress() ?: "127.0.0.1"

        // Determine SIM carrier name & slot
        var carrierName = "No SIM"
        var simSlot = -1
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (telephonyManager != null) {
            val opName = telephonyManager.networkOperatorName
            if (!opName.isNullOrBlank()) {
                carrierName = opName
                simSlot = 0
            }
        }

        Log.i(TAG, "Initializing Native Core: ID=$deviceId, Name=$deviceName, IP=$localIp, Carrier=$carrierName")
        val success = NativeBridge.initNativeCore(deviceId, deviceName, localIp, carrierName, simSlot)
        if (success) {
            NativeBridge.startDiscovery()
            Log.i(TAG, "Native Rust Core initialized and discovery running")
        } else {
            Log.e(TAG, "Failed to initialize Native Rust Core")
        }
    }

    fun getLocalWifiIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
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
        } catch (e: Exception) {
            Log.w(TAG, "Error obtaining local IP: ${e.message}")
        }
        return null
    }

    override fun onTerminate() {
        NativeBridge.stopDiscovery()
        multicastLock?.let {
            if (it.isHeld) it.release()
        }
        super.onTerminate()
    }
}
