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
import android.view.View
import android.widget.Button
import android.widget.EditText
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
import com.callshare.net.NetworkUtils
import com.callshare.telecom.CallShareConnectionService
import com.callshare.telecom.RoleManagerHelper
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    // Persistent On-Screen Breadcrumb Logger & Safe Mode Controller
    object BootDiagnostics {
        private const val PREFS = "boot_diagnostics"
        private const val KEY_CURRENT_STEP = "current_in_progress_step"
        private const val KEY_BOOT_COMPLETE = "boot_complete"

        fun onStepStarting(context: Context, stepName: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CURRENT_STEP, stepName)
                .putBoolean(KEY_BOOT_COMPLETE, false)
                .commit() // Must be synchronous commit() to persist to disk BEFORE native/IPC invocation!
            Log.i("BootDiagnostics", ">>> STARTING $stepName")
        }

        fun onStepCompleted(context: Context, stepName: String) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putString(KEY_CURRENT_STEP, "$stepName [DONE]")
                .commit()
            Log.i("BootDiagnostics", "<<< COMPLETED $stepName")
        }

        fun onBootFinished(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_BOOT_COMPLETE, true)
                .remove(KEY_CURRENT_STEP)
                .commit()
            Log.i("BootDiagnostics", "=== BOOT COMPLETE ===")
        }

        fun checkPreviousCrash(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val wasBootComplete = prefs.getBoolean(KEY_BOOT_COMPLETE, true)
            val inProgress = prefs.getString(KEY_CURRENT_STEP, null)
            if (!wasBootComplete && !inProgress.isNullOrBlank() && !inProgress.endsWith("[DONE]")) {
                return inProgress
            }
            return null
        }

        fun clearPreviousCrash(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean(KEY_BOOT_COMPLETE, true)
                .remove(KEY_CURRENT_STEP)
                .commit()
        }
    }

    private lateinit var textBootCheckpoint: TextView
    private lateinit var textSafeModeWarning: TextView
    private lateinit var btnSafeModeResume: Button

    private lateinit var textLocalInfo: TextView
    private lateinit var textPeersList: TextView
    private lateinit var btnRefreshPeers: Button
    private lateinit var btnLaunchAod: Button
    private lateinit var btnSetDefaultDialer: Button
    private lateinit var editDialTarget: EditText
    private lateinit var btnDirectCall: Button
    private lateinit var btnCallPeerSim: Button
    private lateinit var btnOpenSettings: Button

    private var isSafeModeActive = false

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
            showStartupErrorDialog("View Binding", t)
        }

        // 4. Log STEP 1: UI Inflated
        BootDiagnostics.onStepStarting(this, "STEP 1: UI Inflated")
        textBootCheckpoint.text = "Status: STEP 1: UI Inflated"
        BootDiagnostics.onStepCompleted(this, "STEP 1: UI Inflated")

        // 5. Check for Previous Boot Crash (Safe Mode Gate)
        val crashedStep = BootDiagnostics.checkPreviousCrash(this)
        if (crashedStep != null) {
            isSafeModeActive = true
            Log.w("MainActivity", "Previous launch died at step: $crashedStep. Halting auto-boot in Safe Mode.")
            textSafeModeWarning.visibility = View.VISIBLE
            textSafeModeWarning.text = "⚠️ PREVIOUS LAUNCH DIED AT STEP: $crashedStep\nSafe Mode Active: Auto-startup paused so UI stays open. Tap below to resume."
            btnSafeModeResume.visibility = View.VISIBLE
            btnSafeModeResume.setOnClickListener {
                BootDiagnostics.clearPreviousCrash(this)
                textSafeModeWarning.visibility = View.GONE
                btnSafeModeResume.visibility = View.GONE
                isSafeModeActive = false
                initSubsystemsAsync()
            }
            textBootCheckpoint.text = "Status: Safe Mode Active (Halted at $crashedStep)"
            textBootCheckpoint.setTextColor(ContextCompat.getColor(this, R.color.crimson_drop))
            // Do NOT auto-execute initSubsystemsAsync()! Keep screen open and responsive!
            return
        }

        // 6. Post-Delay Subsystem Startup: Ensure UI is completely drawn and interactive FIRST
        window.decorView.postDelayed({
            if (!isSafeModeActive) {
                initSubsystemsAsync()
            }
        }, 500)
    }

    private fun initSubsystemsAsync() {
        if (isFinishing || isDestroyed) return

        // Checkpoint 2: Loading libcallshare_core.so
        BootDiagnostics.onStepStarting(this, "STEP 2: Loading libcallshare_core.so")
        textBootCheckpoint.text = "Status: STEP 2: Loading libcallshare_core.so..."
        val nativeLoaded = try {
            NativeBridge.ensureLoaded()
        } catch (t: Throwable) {
            Log.e("MainActivity", "NativeBridge.ensureLoaded() failure: ${t.message}")
            false
        }
        BootDiagnostics.onStepCompleted(this, "STEP 2: Loading libcallshare_core.so")

        // Checkpoint 3: Calling NativeBridge.initCore()
        if (nativeLoaded) {
            BootDiagnostics.onStepStarting(this, "STEP 3: Calling NativeBridge.initCore()")
            textBootCheckpoint.text = "Status: STEP 3: Calling NativeBridge.initCore()..."
            try {
                CallShareApp.instance.acquireMulticastLockSafely()
                CallShareApp.instance.initRustNativeCore()
            } catch (t: Throwable) {
                Log.e("MainActivity", "initRustNativeCore failure: ${t.message}")
                showStartupErrorDialog("Rust Core", t)
            }
            BootDiagnostics.onStepCompleted(this, "STEP 3: Calling NativeBridge.initCore()")
        } else {
            val error = NativeBridge.nativeLoadError ?: "Native core not loaded"
            Log.w("MainActivity", "Native library deferred: $error")
        }

        // Checkpoint 4: Starting Telemetry / Event Polling Loop
        BootDiagnostics.onStepStarting(this, "STEP 4: Starting Telemetry / Event Polling Loop")
        textBootCheckpoint.text = "Status: STEP 4: Starting Telemetry / Event Polling Loop..."
        try {
            NativeBridge.startEventPollingLoop()
        } catch (t: Throwable) {
            Log.w("MainActivity", "Event polling loop start warning: ${t.message}")
        }
        BootDiagnostics.onStepCompleted(this, "STEP 4: Starting Telemetry / Event Polling Loop")

        // Checkpoint 5: Registering Telecom PhoneAccount
        BootDiagnostics.onStepStarting(this, "STEP 5: Registering Telecom PhoneAccount")
        textBootCheckpoint.text = "Status: STEP 5: Registering Telecom PhoneAccount..."
        guardRegisterTelecomPhoneAccount()
        BootDiagnostics.onStepCompleted(this, "STEP 5: Registering Telecom PhoneAccount")

        // Checkpoint 6: Boot Complete
        BootDiagnostics.onStepStarting(this, "STEP 6: Boot Complete")
        try {
            requestAppPermissions()
            refreshDashboard()
        } catch (t: Throwable) {
            Log.e("MainActivity", "Permission/Dashboard error: ${t.message}")
        }
        BootDiagnostics.onBootFinished(this)
        textBootCheckpoint.text = "● Boot Complete: P2P Mesh Subsystems Active"
        textBootCheckpoint.setTextColor(ContextCompat.getColor(this, R.color.emerald_active))
    }

    private fun showStartupErrorDialog(step: String, t: Throwable) {
        try {
            val trace = Log.getStackTraceString(t)
            AlertDialog.Builder(this)
                .setTitle("Startup Warning: $step")
                .setMessage("An exception occurred during startup ($step):\n\n${t.message}\n\n${trace.take(500)}...")
                .setPositiveButton("Copy Trace") { _, _ ->
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Startup Error", trace))
                    Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Continue", null)
                .show()
        } catch (dialogEx: Throwable) {
            Log.e("MainActivity", "Failed to show startup dialog: ${dialogEx.message}")
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
            // Do not call setIcon with large bitmaps/mipmaps to prevent TransactionTooLargeException during Binder IPC
            val phoneAccount = PhoneAccount.builder(accountHandle, "CallShare P2P Relay")
                .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER or PhoneAccount.CAPABILITY_CONNECTION_MANAGER)
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
        textBootCheckpoint = findViewById(R.id.text_boot_checkpoint)
        textSafeModeWarning = findViewById(R.id.text_safe_mode_warning)
        btnSafeModeResume = findViewById(R.id.btn_safe_mode_resume)

        textLocalInfo = findViewById(R.id.text_local_info)
        textPeersList = findViewById(R.id.text_peers_list)
        btnRefreshPeers = findViewById(R.id.btn_refresh_peers)
        btnLaunchAod = findViewById(R.id.btn_launch_aod)
        btnSetDefaultDialer = findViewById(R.id.btn_set_default_dialer)
        editDialTarget = findViewById(R.id.edit_dial_target)
        btnDirectCall = findViewById(R.id.btn_direct_call)
        btnCallPeerSim = findViewById(R.id.btn_call_peer_sim)
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

        btnDirectCall.setOnClickListener {
            val target = editDialTarget.text.toString().trim()
            if (target.isEmpty()) {
                Toast.makeText(this, "Please enter a peer IP or phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val sessionId = "p2p-${System.currentTimeMillis()}"
                if (target.contains(".") && !target.startsWith("+")) {
                    NativeBridge.safeConnectToPeer(target, 45782, 45783)
                }
                NativeBridge.safeNotifyLocalCallRinging(sessionId, target, "Direct P2P Peer", -1)
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", sessionId)
                    putExtra("EXTRA_CALLER_NAME", "Direct P2P Call")
                    putExtra("EXTRA_CALLER_NUMBER", target)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Error starting P2P call: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnCallPeerSim.setOnClickListener {
            val target = editDialTarget.text.toString().trim()
            if (target.isEmpty()) {
                Toast.makeText(this, "Please enter a cellular phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            try {
                val sessionId = "sim-relay-${System.currentTimeMillis()}"
                NativeBridge.safeNotifyLocalCallRinging(sessionId, target, "Cellular Relay", 0)
                val intent = Intent(this, CallActivity::class.java).apply {
                    putExtra("EXTRA_SESSION_ID", sessionId)
                    putExtra("EXTRA_CALLER_NAME", "Relayed SIM Call")
                    putExtra("EXTRA_CALLER_NUMBER", target)
                }
                startActivity(intent)
            } catch (t: Throwable) {
                Toast.makeText(this, "Error starting SIM relay: ${t.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun refreshDashboard() {
        val localIp = NetworkUtils.getLocalIpv4Address(this)
        val activeSims = NetworkUtils.getActiveSimCards(this)
        val simSummary = if (activeSims.isNotEmpty()) {
            activeSims.joinToString(", ") { "${it.carrierName} [Slot ${it.slotIndex}]" }
        } else {
            "No active SIM"
        }
        val modelName = NetworkUtils.getDeviceModelName()
        textLocalInfo.text = "Device: $modelName ($localIp)\nCarrier: $simSummary\nPorts: Discovery 45781 | Signaling 45782 | Media 45783"

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
                val discoveredIps = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val peer = arr.getJSONObject(i)
                    val devName = peer.getString("device_name")
                    val ip = peer.getString("ip")
                    val carrier = peer.getString("carrier_name")
                    val battery = peer.getInt("battery_pct")
                    discoveredIps.add(ip)
                    sb.append("• $devName ($ip)\n  Carrier: $carrier | Battery: $battery%\n\n")

                    if (i == 0) {
                        try {
                            NativeBridge.safeConnectToPeer(ip, 45782, 45783)
                        } catch (e: Throwable) {
                            Log.w("MainActivity", "Peer connection warning: ${e.message}")
                        }
                    }
                }
                textPeersList.text = sb.toString()

                // Allow tapping peer list to auto-fill dialer input with discovered peer IP
                textPeersList.setOnClickListener {
                    if (discoveredIps.isNotEmpty()) {
                        editDialTarget.setText(discoveredIps[0])
                        Toast.makeText(this, "Dial target set to ${discoveredIps[0]}", Toast.LENGTH_SHORT).show()
                    }
                }
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
