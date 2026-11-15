package com.callshare.ui

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import com.callshare.R
import com.callshare.native.NativeBridge
import com.callshare.telecom.RoleManagerHelper
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * SettingsActivity
 *
 * Comprehensive Settings & AOD Customization Studio for CallShare P2P.
 * Features:
 * - Reactive StateFlow observation of Default Dialer status (RoleManagerHelper)
 * - 3-Way AOD Orientation Lock (Portrait Lock, Landscape Dock, Auto Sensor) with instant Rust JNI synchronization
 * - 4 Selectable Clock Theme Presets (Cyber-Minimal, Nothing Dot-Matrix, Analog Chrono, Split-Flap)
 * - Backlight dimming slider (0.01f to 0.20f) for OLED and IPS/LCD fallbacks
 * - Proximity power gate & 20x Lissajous orbit fast-forward preview
 * - Photo Picker for importing custom transparent PNG/SVG/WebP stickers
 */
class SettingsActivity : AppCompatActivity() {

    companion object {
        private const val PREFS_SETTINGS = "callshare_settings_prefs"
        const val PREF_AOD_ORIENTATION_LOCK = "pref_aod_orientation_lock"
        const val PREF_AOD_CLOCK_STYLE = "pref_aod_clock_style"
        const val PREF_SCREEN_DIMMING = "pref_screen_dimming"
        const val PREF_PROXIMITY_GATE = "pref_proximity_gate"
        const val PREF_ORBIT_PREVIEW = "pref_orbit_preview"

        const val ORIENTATION_PORTRAIT_LOCK = 0
        const val ORIENTATION_LANDSCAPE_DOCK = 1
        const val ORIENTATION_AUTO_SENSOR = 2
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var layoutRepository: AodLayoutRepository

    private lateinit var tvRoleBadge: TextView
    private lateinit var btnConfigureRole: Button
    private lateinit var btnLaunchAodStudio: Button

    private lateinit var rgOrientationLock: RadioGroup
    private lateinit var rbPortraitLock: RadioButton
    private lateinit var rbLandscapeDock: RadioButton
    private lateinit var rbAutoSensor: RadioButton

    private lateinit var rgClockStyle: RadioGroup
    private lateinit var rbCyberMinimal: RadioButton
    private lateinit var rbNothingMatrix: RadioButton
    private lateinit var rbAnalogChrono: RadioButton
    private lateinit var rbSplitFlap: RadioButton

    private lateinit var tvDimmingValue: TextView
    private lateinit var seekbarDimming: SeekBar
    private lateinit var switchProximityGate: SwitchCompat
    private lateinit var switchOrbitPreview: SwitchCompat

    private lateinit var btnImportSticker: Button
    private lateinit var btnResetAodLayout: Button

    // Photo Picker Contract for Custom Sticker Injection
    private val pickStickerLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val localPath = layoutRepository.saveStickerToInternalStorage(uri)
            if (localPath != null) {
                Toast.makeText(this, "Sticker imported! Launching Canvas Studio...", Toast.LENGTH_SHORT).show()
                val intent = Intent(this, AodActivity::class.java).apply {
                    putExtra("EXTRA_START_EDIT_MODE", true)
                    putExtra("EXTRA_NEW_STICKER_PATH", localPath)
                }
                startActivity(intent)
            } else {
                Toast.makeText(this, "Failed to cache sticker locally.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        prefs = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        layoutRepository = AodLayoutRepository(applicationContext)

        bindViews()
        loadSavedPreferences()
        setupListeners()
        observeRoleState()
    }

    override fun onResume() {
        super.onResume()
        RoleManagerHelper.checkAndUpdateRoleState(this)
    }

    private fun bindViews() {
        tvRoleBadge = findViewById(R.id.tv_role_badge)
        btnConfigureRole = findViewById(R.id.btn_configure_role)
        btnLaunchAodStudio = findViewById(R.id.btn_launch_aod_studio)

        rgOrientationLock = findViewById(R.id.rg_orientation_lock)
        rbPortraitLock = findViewById(R.id.rb_portrait_lock)
        rbLandscapeDock = findViewById(R.id.rb_landscape_dock)
        rbAutoSensor = findViewById(R.id.rb_auto_sensor)

        rgClockStyle = findViewById(R.id.rg_clock_style)
        rbCyberMinimal = findViewById(R.id.rb_cyber_minimal)
        rbNothingMatrix = findViewById(R.id.rb_nothing_matrix)
        rbAnalogChrono = findViewById(R.id.rb_analog_chrono)
        rbSplitFlap = findViewById(R.id.rb_split_flap)

        tvDimmingValue = findViewById(R.id.tv_dimming_value)
        seekbarDimming = findViewById(R.id.seekbar_dimming)
        switchProximityGate = findViewById(R.id.switch_proximity_gate)
        switchOrbitPreview = findViewById(R.id.switch_orbit_preview)

        btnImportSticker = findViewById(R.id.btn_import_sticker)
        btnResetAodLayout = findViewById(R.id.btn_reset_aod_layout)
    }

    private fun loadSavedPreferences() {
        // 1. Orientation Mode
        val orientMode = prefs.getInt(PREF_AOD_ORIENTATION_LOCK, ORIENTATION_PORTRAIT_LOCK)
        when (orientMode) {
            ORIENTATION_LANDSCAPE_DOCK -> rbLandscapeDock.isChecked = true
            ORIENTATION_AUTO_SENSOR -> rbAutoSensor.isChecked = true
            else -> rbPortraitLock.isChecked = true
        }

        // 2. Clock Theme Style
        val clockStyle = prefs.getInt(PREF_AOD_CLOCK_STYLE, DraggableAodCanvasView.CLOCK_STYLE_CYBER_MINIMAL)
        when (clockStyle) {
            DraggableAodCanvasView.CLOCK_STYLE_NOTHING_DOT_MATRIX -> rbNothingMatrix.isChecked = true
            DraggableAodCanvasView.CLOCK_STYLE_ANALOG_CHRONO -> rbAnalogChrono.isChecked = true
            DraggableAodCanvasView.CLOCK_STYLE_SPLIT_FLAP -> rbSplitFlap.isChecked = true
            else -> rbCyberMinimal.isChecked = true
        }

        // 3. Screen Dimming
        val dimmingProgress = prefs.getInt(PREF_SCREEN_DIMMING, 1)
        seekbarDimming.progress = dimmingProgress
        val brightnessF = (dimmingProgress / 100f).coerceIn(0.01f, 0.20f)
        tvDimmingValue.text = String.format("%.2ff", brightnessF)

        // 4. Proximity & Orbit preview
        switchProximityGate.isChecked = prefs.getBoolean(PREF_PROXIMITY_GATE, true)
        val orbitPreview = prefs.getBoolean(PREF_ORBIT_PREVIEW, false)
        switchOrbitPreview.isChecked = orbitPreview
        NativeBridge.safeSetOrbitSpeedMultiplier(if (orbitPreview) 20.0f else 1.0f)
    }

    private fun setupListeners() {
        // Configure Sheet Button
        btnConfigureRole.setOnClickListener {
            RoleManagerHelper.showPrePermissionConfigSheet(this) {
                RoleManagerHelper.checkAndUpdateRoleState(this)
            }
        }

        // Launch Canvas Studio Button
        btnLaunchAodStudio.setOnClickListener {
            val intent = Intent(this, AodActivity::class.java).apply {
                putExtra("EXTRA_START_EDIT_MODE", true)
            }
            startActivity(intent)
        }

        // 3-Way AOD Orientation Lock
        rgOrientationLock.setOnCheckedChangeListener { _, checkedId ->
            val mode = when (checkedId) {
                R.id.rb_landscape_dock -> ORIENTATION_LANDSCAPE_DOCK
                R.id.rb_auto_sensor -> ORIENTATION_AUTO_SENSOR
                else -> ORIENTATION_PORTRAIT_LOCK
            }
            prefs.edit().putInt(PREF_AOD_ORIENTATION_LOCK, mode).apply()
            NativeBridge.safeSetAodOrientation(mode)

            val msg = when (mode) {
                ORIENTATION_PORTRAIT_LOCK -> "AOD locked to Portrait (±6px X / ±8px Y bounds)"
                ORIENTATION_LANDSCAPE_DOCK -> "AOD locked to Landscape Dock (±10px X / ±5px Y bounds)"
                else -> "AOD set to Auto Sensor dynamic clamping"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        // 4 Selectable Clock Styles
        rgClockStyle.setOnCheckedChangeListener { _, checkedId ->
            val style = when (checkedId) {
                R.id.rb_nothing_matrix -> DraggableAodCanvasView.CLOCK_STYLE_NOTHING_DOT_MATRIX
                R.id.rb_analog_chrono -> DraggableAodCanvasView.CLOCK_STYLE_ANALOG_CHRONO
                R.id.rb_split_flap -> DraggableAodCanvasView.CLOCK_STYLE_SPLIT_FLAP
                else -> DraggableAodCanvasView.CLOCK_STYLE_CYBER_MINIMAL
            }
            prefs.edit().putInt(PREF_AOD_CLOCK_STYLE, style).apply()
        }

        // Dimming Slider
        seekbarDimming.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val clamped = progress.coerceAtLeast(1)
                val brightnessF = (clamped / 100f).coerceIn(0.01f, 0.20f)
                tvDimmingValue.text = String.format("%.2ff", brightnessF)
                if (fromUser) {
                    prefs.edit().putInt(PREF_SCREEN_DIMMING, clamped).apply()
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Proximity Power Gate Toggle
        switchProximityGate.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_PROXIMITY_GATE, isChecked).apply()
        }

        // 20x Lissajous Orbit Fast-Forward Preview
        switchOrbitPreview.setOnCheckedChangeListener { _, isChecked ->
            prefs.edit().putBoolean(PREF_ORBIT_PREVIEW, isChecked).apply()
            NativeBridge.safeSetOrbitSpeedMultiplier(if (isChecked) 20.0f else 1.0f)
            Toast.makeText(this, if (isChecked) "20x Fast-Forward active" else "1x Natural speed", Toast.LENGTH_SHORT).show()
        }

        // Import Custom Sticker Photo Picker
        btnImportSticker.setOnClickListener {
            pickStickerLauncher.launch(
                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
            )
        }

        // Reset Coordinates to Factory Layout
        btnResetAodLayout.setOnClickListener {
            layoutRepository.resetToDefaults()
            Toast.makeText(this, "AOD layout reset to factory coordinates.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeRoleState() {
        lifecycleScope.launch {
            RoleManagerHelper.isDefaultCallerActive.collectLatest { isGranted ->
                if (isGranted) {
                    tvRoleBadge.text = "✓ VERIFIED: DEFAULT CALLER ACTIVE"
                    tvRoleBadge.setTextColor(Color.parseColor("#10B981"))
                } else {
                    tvRoleBadge.text = "⚠ WARNING: DEFAULT CALLER NOT GRANTED"
                    tvRoleBadge.setTextColor(Color.parseColor("#F59E0B"))
                }
            }
        }
    }
}
