package com.callshare.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callshare.CallShareApp
import com.callshare.R
import com.callshare.native.NativeBridge
import com.callshare.telecom.CallShareConnectionService
import com.callshare.telecom.RoleManagerHelper
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var textLocalInfo: TextView
    private lateinit var textPeersList: TextView
    private lateinit var btnRefreshPeers: Button
    private lateinit var btnLaunchAod: Button
    private lateinit var btnSetDefaultDialer: Button
    private lateinit var btnSimulateIncomingCall: Button
    private lateinit var btnOpenSettings: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (recordAudioGranted && phoneStateGranted) {
            Toast.makeText(this, "Permissions granted! Initializing P2P Mesh...", Toast.LENGTH_SHORT).show()
            try {
                CallShareApp.instance.initRustNativeCore()
                refreshDashboard()
            } catch (t: Throwable) {
                Log.e("MainActivity", "Error during mesh startup post-permission: ${t.message}")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Guard setContentView against theme/resource inflation failure
        var isLayoutInflated = false
        try {
            setContentView(R.layout.activity_main)
            isLayoutInflated = true
        } catch (t: Throwable) {
            Log.e("MainActivity", "Failed to inflate activity_main layout: ${t.message}", t)
            renderFallbackErrorScreen("Failed to inflate UI layout: ${t.message}\n${Log.getStackTraceString(t)}")
            return
        }

        // 2. Check and surface previous uncaught crash log if one occurred
        checkAndShowPreviousCrashLog()

        // 3. Defensive View Binding
        try {
            bindViews()
        } catch (t: Throwable) {
            Log.e("MainActivity", "View binding failure: ${t.message}", t)
            Toast.makeText(this, "UI binding warning: ${t.message}", Toast.LENGTH_LONG).show()
        }

        // 4. Register / verify Telecom PhoneAccount defensively
        guardRegisterTelecomPhoneAccount()

        // 5. Request runtime permissions defensively
        try {
            requestAppPermissions()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Permission request failure: ${t.message}", t)
        }

        // 6. Refresh Dashboard UI
        try {
            refreshDashboard()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Dashboard refresh failure: ${t.message}", t)
        }
    }

    private fun checkAndShowPreviousCrashLog() {
        try {
            val crashLog = CallShareApp.instance.getLastCrashLog()
            if (!crashLog.isNullOrBlank()) {
                AlertDialog.Builder(this)
                    .setTitle("Previous Crash Intercepted")
                    .setMessage("A fatal exception occurred during the last run:\n\n${crashLog.take(600)}...\n\nTap 'Copy Log' to copy full trace.")
                    .setPositiveButton("Copy Log") { _, _ ->
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("CallShare Crash Log", crashLog))
                        Toast.makeText(this, "Crash log copied to clipboard!", Toast.LENGTH_SHORT).show()
                        CallShareApp.instance.clearLastCrashLog()
                    }
                    .setNegativeButton("Dismiss") { _, _ ->
                        CallShareApp.instance.clearLastCrashLog()
                    }
                    .setCancelable(true)
                    .show()
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error displaying crash log dialog: ${t.message}")
        }
    }

    private fun guardRegisterTelecomPhoneAccount() {
        try {
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
            val accountHandle = PhoneAccountHandle(
                android.content.ComponentName(this, CallShareConnectionService::class.java),
                "CallShareP2P"
            )
            val phoneAccount = PhoneAccount.builder(accountHandle, "CallShare P2P Relay")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER or PhoneAccount.CAPABILITY_CONNECTION_MANAGER)
                .setIcon(android.graphics.drawable.Icon.createWithResource(this, R.mipmap.ic_launcher))
                .setHighlightColor(Color.parseColor("#10B981"))
                .build()

            telecomManager.registerPhoneAccount(phoneAccount)
            Log.i("MainActivity", "CallShare Telecom PhoneAccount registered successfully")
        } catch (e: SecurityException) {
            Log.w("MainActivity", "PhoneAccount registration requires permission: ${e.message}")
        } catch (t: Throwable) {
            Log.w("MainActivity", "PhoneAccount registration bypassed: ${t.message}")
        }
    }

    private fun renderFallbackErrorScreen(errorText: String) {
        val scrollView = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            setPadding(32, 48, 32, 48)
        }
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val tvTitle = TextView(this).apply {
            text = "⚠ CallShare P2P Startup Diagnostic"
            textSize = 20f
            setTextColor(Color.parseColor("#EF4444"))
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val tvMsg = TextView(this).apply {
            text = errorText
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, 24, 0, 24)
        }
        val btnRetry = Button(this).apply {
            text = "Retry Launch"
            setBackgroundColor(Color.parseColor("#10B981"))
            setTextColor(Color.BLACK)
            setOnClickListener { recreate() }
        }

        layout.addView(tvTitle)
        layout.addView(tvMsg)
        layout.addView(btnRetry)
        scrollView.addView(layout)

        setContentView(scrollView)
    }

    private fun bindViews() {
        textLocalInfo = findViewById(R.id.text_local_info)
        textPeersList = findViewById(R.id.text_peers_list)
        btnRefreshPeers = findViewById(R.id.btn_refresh_peers)
        btnLaunchAod = findViewById(R.id.btn_launch_aod)
        btnSetDefaultDialer = findViewById(R.id.btn_set_default_dialer)
        btnSimulateIncomingCall = findViewById(R.id.btn_simulate_call)
        btnOpenSettings = findViewById(R.id.btn_open_settings)

        btnOpenSettings.setOnClickListener {
            try {
                val intent = Intent(this, SettingsActivity::class.java)
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Failed to launch Settings: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnRefreshPeers.setOnClickListener {
            try {
                CallShareApp.instance.initRustNativeCore()
                refreshDashboard()
            } catch (t: Throwable) {
                Toast.makeText(this, "Refresh error: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnLaunchAod.setOnClickListener {
            try {
                val intent = Intent(this, AodActivity::class.java)
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Failed to launch AOD: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnSetDefaultDialer.setOnClickListener {
            requestInCallCompanionRole()
        }

        btnSimulateIncomingCall.setOnClickListener {
            try {
                val intent = Intent(this, AodActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", "test-sim-call-101")
                    putExtra("EXTRA_CALLER_NUMBER", "+1 (555) 019-2834")
                    putExtra("EXTRA_CALLER_NAME", "Remote SIM Contact")
                    putExtra("EXTRA_IS_RINGING", true)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Simulation launch error: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshDashboard() {
        val localIp = CallShareApp.instance.getLocalWifiIpAddress() ?: "Checking Wi-Fi..."
        textLocalInfo.text = "Device IP: $localIp\nPorts: Discovery 45781 | Signaling 45782 | Media 45783"

        if (!NativeBridge.isLoaded) {
            textPeersList.text = "Native Engine: Initializing...\nTap 'Refresh Peer Mesh' once permissions are granted."
            return
        }

        try {
            val peersJson = NativeBridge.safeGetDiscoveredPeersJson()
            val arr = JSONArray(peersJson)
            if (arr.length() == 0) {
                textPeersList.text = "● Searching for peer Android devices on local offline Wi-Fi..."
            } else {
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val peer = arr.getJSONObject(i)
                    val devName = peer.getString("device_name")
                    val ip = peer.getString("ip")
                    val carrier = peer.getString("carrier_name")
                    val battery = peer.getInt("battery_pct")
                    sb.append("• $devName ($ip)\n  Carrier: $carrier | Battery: $battery%\n\n")

                    if (i == 0) {
                        try {
                            NativeBridge.connectToPeer(ip, 45782, 45783)
                        } catch (e: Throwable) {
                            Log.w("MainActivity", "Peer connection warning: ${e.message}")
                        }
                    }
                }
                textPeersList.text = sb.toString()
            }
        } catch (e: Throwable) {
            textPeersList.text = "Searching for peer devices..."
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        updateRoleButtonState()
    }

    private fun updateRoleButtonState() {
        try {
            if (RoleManagerHelper.isDefaultDialer(this)) {
                btnSetDefaultDialer.text = "✓ DEFAULT CALLER ACTIVE"
                btnSetDefaultDialer.setBackgroundColor(ContextCompat.getColor(this, R.color.emerald_active))
            } else {
                btnSetDefaultDialer.text = "Set Default Caller / Companion"
                btnSetDefaultDialer.setBackgroundColor(ContextCompat.getColor(this, R.color.dark_card))
            }
        } catch (t: Throwable) {
            Log.w("MainActivity", "Error updating role button state: ${t.message}")
        }
    }

    private fun requestInCallCompanionRole() {
        try {
            RoleManagerHelper.showPrePermissionConfigSheet(this) {
                updateRoleButtonState()
            }
        } catch (t: Throwable) {
            Log.e("MainActivity", "Error presenting role config sheet: ${t.message}")
            Toast.makeText(this, "Telecom role prompt warning: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
}
