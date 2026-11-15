package com.callshare.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.callshare.CallShareApp
import com.callshare.R
import com.callshare.native.NativeBridge
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private lateinit var textLocalInfo: TextView
    private lateinit var textPeersList: TextView
    private lateinit var btnRefreshPeers: Button
    private lateinit var btnLaunchAod: Button
    private lateinit var btnSetDefaultDialer: Button
    private lateinit var btnSimulateIncomingCall: Button

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val phoneStateGranted = permissions[Manifest.permission.READ_PHONE_STATE] ?: false
        if (recordAudioGranted && phoneStateGranted) {
            Toast.makeText(this, "All permissions granted", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        requestAppPermissions()
        refreshDashboard()
    }

    private fun bindViews() {
        textLocalInfo = findViewById(R.id.text_local_info)
        textPeersList = findViewById(R.id.text_peers_list)
        btnRefreshPeers = findViewById(R.id.btn_refresh_peers)
        btnLaunchAod = findViewById(R.id.btn_launch_aod)
        btnSetDefaultDialer = findViewById(R.id.btn_set_default_dialer)
        btnSimulateIncomingCall = findViewById(R.id.btn_simulate_call)

        btnRefreshPeers.setOnClickListener {
            refreshDashboard()
        }

        btnLaunchAod.setOnClickListener {
            val intent = Intent(this, AodActivity::class.java)
            startActivity(intent)
        }

        btnSetDefaultDialer.setOnClickListener {
            requestInCallCompanionRole()
        }

        btnSimulateIncomingCall.setOnClickListener {
            val intent = Intent(this, AodActivity::class.java).apply {
                putExtra("EXTRA_SESSION_ID", "test-sim-call-101")
                putExtra("EXTRA_CALLER_NUMBER", "+1 (555) 019-2834")
                putExtra("EXTRA_CALLER_NAME", "Remote SIM Contact")
                putExtra("EXTRA_IS_RINGING", true)
            }
            startActivity(intent)
        }
    }

    private fun refreshDashboard() {
        val localIp = CallShareApp.instance.getLocalWifiIpAddress() ?: "Not connected to Wi-Fi"
        textLocalInfo.text = "Device IP: $localIp\nPorts: Discovery 45781 | Signaling 45782 | Media 45783"

        // Query active LAN peers from Rust Core
        val peersJson = NativeBridge.getDiscoveredPeersJson()
        try {
            val arr = JSONArray(peersJson)
            if (arr.length() == 0) {
                textPeersList.text = "Searching for peer Android devices on local offline Wi-Fi..."
            } else {
                val sb = StringBuilder()
                for (i in 0 until arr.length()) {
                    val peer = arr.getJSONObject(i)
                    val devName = peer.getString("device_name")
                    val ip = peer.getString("ip")
                    val carrier = peer.getString("carrier_name")
                    val battery = peer.getInt("battery_pct")
                    sb.append("• $devName ($ip)\n  Carrier: $carrier | Battery: $battery%\n\n")

                    // Automatically connect signaling and audio to first discovered peer
                    if (i == 0) {
                        NativeBridge.connectToPeer(ip, 45782, 45783)
                    }
                }
                textPeersList.text = sb.toString()
            }
        } catch (e: Exception) {
            textPeersList.text = "Error parsing peers: ${e.message}"
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

        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun requestInCallCompanionRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(Context.ROLE_SERVICE) as? RoleManager
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                startActivity(intent)
                return
            }
        }
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        }
        startActivity(intent)
    }
}
