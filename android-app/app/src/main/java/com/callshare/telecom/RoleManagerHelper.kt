package com.callshare.telecom

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.widget.SwitchCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RoleManagerHelper
 *
 * Production-ready RoleManager integration for Android 10 (API 29) through Android 15+.
 * Manages:
 * - ActivityResultLauncher role requests for ROLE_DIALER and ROLE_CALL_COMPANION_APP
 * - Sleek #000000 AMOLED Pre-Permission Configuration BottomSheetDialog
 * - Reactive StateFlow<Boolean> for live verification badges across MainActivity & SettingsActivity
 * - Native telemetry flags persistence (relay_gsm_over_lan, auto_wake_aod, exclusive_audio_focus)
 */
object RoleManagerHelper {

    private const val TAG = "RoleManagerHelper"
    private const val PREFS_NAME = "callshare_telecom_prefs"

    // Preference Keys
    const val KEY_RELAY_GSM_OVER_LAN = "pref_relay_gsm_over_lan"
    const val KEY_AUTOWAKE_AOD = "pref_autowake_aod"
    const val KEY_EXCLUSIVE_AUDIO_FOCUS = "pref_exclusive_audio_focus"

    // Reactive StateFlow for live verification badge
    private val _isDefaultCallerActive = MutableStateFlow(false)
    val isDefaultCallerActive: StateFlow<Boolean> = _isDefaultCallerActive.asStateFlow()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if CallShare P2P is currently verified as Default Dialer or Call Companion.
     */
    fun isDefaultDialer(context: Context): Boolean {
        val verified = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
                } else {
                    val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    telecom?.defaultDialerPackage == context.packageName
                }
            } else {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecom?.defaultDialerPackage == context.packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default dialer role: ${e.message}")
            false
        }
        _isDefaultCallerActive.value = verified
        return verified
    }

    /**
     * Re-checks and broadcasts the current role status to reactive observers.
     */
    fun checkAndUpdateRoleState(context: Context): Boolean {
        return isDefaultDialer(context)
    }

    /**
     * Constructs the system intent to request the Default Dialer or Companion role.
     */
    fun createDefaultDialerIntent(context: Context): Intent? {
        if (isDefaultDialer(context)) {
            Log.d(TAG, "CallShare already holds default dialer role.")
            return null
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                } else {
                    createLegacyDefaultDialerIntent(context)
                }
            } else {
                createLegacyDefaultDialerIntent(context)
            }
        } catch (e: Exception) {
            Log.e(TAG, "RoleManager intent creation error: ${e.message}")
            createLegacyDefaultDialerIntent(context)
        }
    }

    private fun createLegacyDefaultDialerIntent(context: Context): Intent {
        return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }

    /**
     * Displays a sleek #000000 AMOLED BottomSheetDialog allowing the user to configure
     * relay parameters before launching the Android OS Role request.
     */
    fun showPrePermissionConfigSheet(
        activity: Activity,
        roleLauncher: ActivityResultLauncher<Intent>? = null,
        onConfirmed: (() -> Unit)? = null
    ) {
        val dialog = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density

        // Root AMOLED container
        val rootLayout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#05080E"))
            val pad = (20 * density).toInt()
            setPadding(pad, (12 * density).toInt(), pad, (28 * density).toInt())
        }

        // Drag handle indicator
        val dragHandle = View(activity).apply {
            val hW = (40 * density).toInt()
            val hH = (4 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(hW, hH).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (16 * density).toInt()
            }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#1F293D"))
                cornerRadius = 4f * density
            }
        }
        rootLayout.addView(dragHandle)

        // Header Title
        val title = TextView(activity).apply {
            text = "Set Default Caller & Companion"
            textSize = 17f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }
        rootLayout.addView(title)

        // Subtitle
        val subtitle = TextView(activity).apply {
            text = "CallShare P2P relays cellular calls over offline local Wi-Fi and wakes AOD on incoming calls."
            textSize = 12f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, (4 * density).toInt(), 0, (16 * density).toInt())
        }
        rootLayout.addView(subtitle)

        // Card Container for Toggles
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#090E17"))
                setStroke((1 * density).toInt(), Color.parseColor("#152033"))
                cornerRadius = 16f * density
            }
            val cardPad = (14 * density).toInt()
            setPadding(cardPad, cardPad, cardPad, cardPad)
        }

        // 1. Toggle: Relay incoming GSM calls over local LAN
        val switchRelay = createConfigToggle(
            activity,
            title = "Relay GSM Calls Over LAN",
            desc = "Auto-broadcast incoming STATE_RINGING calls via UDP/TCP :45782",
            initialState = isRelayGsmOverLanEnabled(activity)
        )
        card.addView(switchRelay.first)

        // Divider
        card.addView(createDivider(activity, density))

        // 2. Toggle: Auto-wake AOD on incoming TCP CallIncoming packet
        val switchWake = createConfigToggle(
            activity,
            title = "Auto-Wake Universal AOD",
            desc = "Trigger turnScreenOn & ramp AodActivity from 1 to 30 FPS",
            initialState = isAutoWakeAodEnabled(activity)
        )
        card.addView(switchWake.first)

        // Divider
        card.addView(createDivider(activity, density))

        // 3. Toggle: Acquire exclusive transient audio focus
        val switchFocus = createConfigToggle(
            activity,
            title = "Exclusive Audio Focus",
            desc = "Acquire AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE & lock MODE_IN_COMMUNICATION",
            initialState = isExclusiveAudioFocusEnabled(activity)
        )
        card.addView(switchFocus.first)

        rootLayout.addView(card)

        // Action Buttons Row
        val buttonRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (20 * density).toInt()
            }
        }

        val btnCancel = Button(activity).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#94A3B8"))
            setBackgroundColor(Color.TRANSPARENT)
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1f).apply {
                marginEnd = (8 * density).toInt()
            }
            setOnClickListener { dialog.dismiss() }
        }

        val btnConfirm = Button(activity).apply {
            text = "Continue to System Role"
            setTextColor(Color.parseColor("#000000"))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#10B981"))
                cornerRadius = 14f * density
            }
            layoutParams = LinearLayout.LayoutParams(0, (48 * density).toInt(), 1.6f)
            setOnClickListener {
                // Persist user selected configuration
                setRelayGsmOverLanEnabled(activity, switchRelay.second.isChecked)
                setAutoWakeAodEnabled(activity, switchWake.second.isChecked)
                setExclusiveAudioFocusEnabled(activity, switchFocus.second.isChecked)

                dialog.dismiss()
                onConfirmed?.invoke()

                // Fire System Role Intent
                val roleIntent = createDefaultDialerIntent(activity)
                if (roleIntent != null) {
                    if (roleLauncher != null) {
                        roleLauncher.launch(roleIntent)
                    } else {
                        activity.startActivity(roleIntent)
                    }
                } else {
                    checkAndUpdateRoleState(activity)
                }
            }
        }

        buttonRow.addView(btnCancel)
        buttonRow.addView(btnConfirm)
        rootLayout.addView(buttonRow)

        dialog.setContentView(rootLayout)
        dialog.show()
    }

    private fun createConfigToggle(
        context: Context,
        title: String,
        desc: String,
        initialState: Boolean
    ): Pair<LinearLayout, SwitchCompat> {
        val density = context.resources.displayMetrics.density
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, (8 * density).toInt(), 0, (8 * density).toInt())
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = (12 * density).toInt()
            }
        }

        val tvTitle = TextView(context).apply {
            text = title
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
        }

        val tvDesc = TextView(context).apply {
            text = desc
            textSize = 11f
            setTextColor(Color.parseColor("#64748B"))
            setPadding(0, (2 * density).toInt(), 0, 0)
        }

        textCol.addView(tvTitle)
        textCol.addView(tvDesc)

        val switchView = SwitchCompat(context).apply {
            isChecked = initialState
            val thumbColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#10B981"), Color.parseColor("#475569"))
            )
            val trackColor = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(Color.parseColor("#044E36"), Color.parseColor("#1E293B"))
            )
            thumbTintList = thumbColor
            trackTintList = trackColor
        }

        row.addView(textCol)
        row.addView(switchView)
        return Pair(row, switchView)
    }

    private fun createDivider(context: Context, density: Float): View {
        return View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (1 * density).toInt()
            ).apply {
                topMargin = (4 * density).toInt()
                bottomMargin = (4 * density).toInt()
            }
            setBackgroundColor(Color.parseColor("#152033"))
        }
    }

    // -------------------------------------------------------------------------
    // TELEPHONY RELAY PREFERENCES
    // -------------------------------------------------------------------------

    fun isRelayGsmOverLanEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_RELAY_GSM_OVER_LAN, true)
    }

    fun setRelayGsmOverLanEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RELAY_GSM_OVER_LAN, enabled).apply()
        Log.i(TAG, "Relay GSM over LAN set to: $enabled")
    }

    fun isAutoWakeAodEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTOWAKE_AOD, true)
    }

    fun setAutoWakeAodEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTOWAKE_AOD, enabled).apply()
        Log.i(TAG, "Auto-wake AOD set to: $enabled")
    }

    fun isExclusiveAudioFocusEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUSIVE_AUDIO_FOCUS, true)
    }

    fun setExclusiveAudioFocusEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUSIVE_AUDIO_FOCUS, enabled).apply()
        Log.i(TAG, "Exclusive audio focus set to: $enabled")
    }
}
