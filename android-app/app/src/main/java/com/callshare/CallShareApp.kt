package com.callshare

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.TelephonyManager
import android.util.Log
import com.callshare.native.NativeBridge
import com.callshare.ui.CrashActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.UUID

class CallShareApp : Application() {

    private var multicastLock: WifiManager.MulticastLock? = null

    companion object {
        const val TAG = "CallShareApp"
        const val PREFS_CRASH = "callshare_crash_prefs"
        const val KEY_CRASH_LOG = "crash_log"
        const val KEY_CRASH_TIMESTAMP = "crash_timestamp"

        lateinit var instance: CallShareApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 1. Install On-Screen Crash Catcher at the very top of onCreate()
        installCrashCatcher()

        // 2. Safe notification channel creation for background services
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
                val serviceChannel = android.app.NotificationChannel(
                    "callshare_mesh_channel",
                    "CallShare Mesh Relay Service",
                    android.app.NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Persistent low-latency P2P mesh relay notification"
                }
                val callChannel = android.app.NotificationChannel(
                    "callshare_call_channel",
                    "CallShare Relayed Calls",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Incoming and active relayed P2P call alerts"
                }
                notificationManager.createNotificationChannel(serviceChannel)
                notificationManager.createNotificationChannel(callChannel)
                Log.i(TAG, "Notification channels registered successfully")
            } catch (e: Throwable) {
                Log.w(TAG, "Notification channel creation bypassed: ${e.message}")
            }
        }
    }

    private fun installCrashCatcher() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val stackTrace = Log.getStackTraceString(throwable)
            Log.e(TAG, "FATAL CRASH INTERCEPTED BY CallShareApp CRASH CATCHER:\n$stackTrace")

            try {
                val prefs = getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
                prefs.edit()
                    .putString(KEY_CRASH_LOG, stackTrace)
                    .putLong(KEY_CRASH_TIMESTAMP, System.currentTimeMillis())
                    .commit()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to persist crash log to SharedPreferences: ${t.message}")
            }

            try {
                val crashIntent = Intent(this, CrashActivity::class.java).apply {
                    putExtra(CrashActivity.EXTRA_STACK_TRACE, stackTrace)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                }
                startActivity(crashIntent)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to launch CrashActivity directly: ${t.message}")
            }

            // Terminate the crashing process cleanly so CrashActivity (in :crash_reporter process) displays
            android.os.Process.killProcess(android.os.Process.myPid())
            System.exit(10)
        }
    }

    fun getLastCrashLog(): String? {
        val prefs = getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
        return prefs.getString(KEY_CRASH_LOG, null)
    }

    fun clearLastCrashLog() {
        val prefs = getSharedPreferences(PREFS_CRASH, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY_CRASH_LOG).remove(KEY_CRASH_TIMESTAMP).apply()
    }

    fun acquireMulticastLockSafely() {
        try {
            acquireMulticastLock()
        } catch (e: Throwable) {
            Log.e(TAG, "MulticastLock acquisition bypassed: ${e.message}")
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
        try {
            if (!NativeBridge.ensureLoaded()) {
                Log.w(TAG, "Native library not ready yet: ${NativeBridge.nativeLoadError}")
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
        } catch (t: Throwable) {
            Log.e(TAG, "Safely caught error inside initRustNativeCore: ${t.message}")
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
