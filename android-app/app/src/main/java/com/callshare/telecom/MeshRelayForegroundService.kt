package com.callshare.telecom

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.callshare.R
import com.callshare.ui.CallActivity
import com.callshare.ui.MainActivity

/**
 * MeshRelayForegroundService
 *
 * Dedicated Foreground Service maintaining low-latency socket connectivity,
 * CPU partial wakelocks, and high-performance Wi-Fi locks during live relayed calls.
 * Declared with foregroundServiceType="phoneCall|microphone|connectedDevice" (Android 14+ / API 34).
 */
class MeshRelayForegroundService : Service() {

    companion object {
        private const val TAG = "MeshRelayFgService"
        private const val NOTIFICATION_CHANNEL_ID = "callshare_relay_channel"
        private const val NOTIFICATION_ID = 45781

        const val ACTION_START_RELAY = "com.callshare.action.START_RELAY"
        const val ACTION_STOP_RELAY = "com.callshare.action.STOP_RELAY"

        const val EXTRA_CALLER_NAME = "extra_caller_name"
        const val EXTRA_CALLER_NUMBER = "extra_caller_number"
        const val EXTRA_SESSION_ID = "extra_session_id"

        fun startRelay(context: Context, sessionId: String, callerName: String, callerNumber: String) {
            val intent = Intent(context, MeshRelayForegroundService::class.java).apply {
                action = ACTION_START_RELAY
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_CALLER_NAME, callerName)
                putExtra(EXTRA_CALLER_NUMBER, callerNumber)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRelay(context: Context) {
            val intent = Intent(context, MeshRelayForegroundService::class.java).apply {
                action = ACTION_STOP_RELAY
            }
            context.startService(intent)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        acquireLocks()
        Log.i(TAG, "MeshRelayForegroundService created and power locks acquired")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: ACTION_START_RELAY
        if (action == ACTION_STOP_RELAY) {
            Log.i(TAG, "Stopping MeshRelayForegroundService")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val callerName = intent?.getStringExtra(EXTRA_CALLER_NAME) ?: "Relayed Contact"
        val callerNumber = intent?.getStringExtra(EXTRA_CALLER_NUMBER) ?: "Offline Wi-Fi Call"
        val sessionId = intent?.getStringExtra(EXTRA_SESSION_ID) ?: ""

        val notification = buildRelayNotification(callerName, callerNumber, sessionId)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var fgType = 0
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    // Android 14+ (API 34) dynamic permission checks
                    val hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    val hasManageCalls = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.MANAGE_OWN_CALLS
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                    if (hasManageCalls) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                    if (hasRecordAudio) fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11–13 (API 30–33)
                    fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                    val hasRecordAudio = androidx.core.content.ContextCompat.checkSelfPermission(
                        this, android.Manifest.permission.RECORD_AUDIO
                    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (hasRecordAudio) {
                        fgType = fgType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                    }
                } else {
                    // Android 10 (API 29)
                    fgType = ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
                }

                if (fgType != 0) {
                    startForeground(NOTIFICATION_ID, notification, fgType)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
            } else {
                // Android 8.0–9.0 (API 26–28): 2-arg version ONLY!
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}", e)
        }

        return START_STICKY
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = powerManager?.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "CallShare:MeshRelayWakeLock"
            )?.apply {
                setReferenceCounted(false)
                acquire(120 * 60 * 1000L) // 2-hour safety ceiling
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lockMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiManager?.createWifiLock(lockMode, "CallShare:MeshRelayWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error acquiring locks: ${e.message}")
        }
    }

    private fun releaseLocks() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wifiLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing locks: ${e.message}")
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "CallShare P2P Live Call Relay",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Maintains offline Wi-Fi audio bridge and prevents OS process suspension during calls"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildRelayNotification(callerName: String, callerNumber: String, sessionId: String): Notification {
        val tapIntent = Intent(this, CallActivity::class.java).apply {
            putExtra("EXTRA_SESSION_ID", sessionId)
            putExtra("EXTRA_CALLER_NAME", callerName)
            putExtra("EXTRA_CALLER_NUMBER", callerNumber)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Active P2P Call: $callerName")
            .setContentText("Relaying offline audio via LAN mesh • $callerNumber")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        releaseLocks()
        super.onDestroy()
        Log.i(TAG, "MeshRelayForegroundService destroyed")
    }
}
