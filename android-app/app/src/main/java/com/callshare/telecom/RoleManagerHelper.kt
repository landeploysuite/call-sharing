package com.callshare.telecom

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.telecom.TelecomManager
import android.util.Log

/**
 * RoleManagerHelper
 *
 * Production helper managing Android RoleManager.ROLE_DIALER intent launches,
 * role verification status, and telecom bridging preferences across Android versions.
 *
 * Handles:
 * - Default dialer verification on Android Q+ (RoleManager) and pre-Q (TelecomManager)
 * - System role request intent creation
 * - Telecom relay feature toggles:
 *     1. Relay incoming GSM calls over local LAN
 *     2. Auto-wake AOD on incoming TCP CallIncoming packet
 *     3. Acquire exclusive transient audio focus
 */
object RoleManagerHelper {

    private const val TAG = "RoleManagerHelper"
    private const val PREFS_NAME = "callshare_telecom_prefs"

    // Preference Keys
    const val KEY_RELAY_GSM_OVER_LAN = "pref_relay_gsm_over_lan"
    const val KEY_AUTOWAKE_AOD = "pref_autowake_aod"
    const val KEY_EXCLUSIVE_AUDIO_FOCUS = "pref_exclusive_audio_focus"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Checks if CallShare P2P is currently set as the default dialer / caller app.
     */
    fun isDefaultDialer(context: Context): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val roleManager = context.getSystemService(Context.ROLE_SERVICE) as? RoleManager
                if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
                } else {
                    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                    telecomManager?.defaultDialerPackage == context.packageName
                }
            } else {
                val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                telecomManager?.defaultDialerPackage == context.packageName
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking default dialer role status: ${e.message}", e)
            false
        }
    }

    /**
     * Constructs the appropriate system intent to request the Default Dialer role.
     * Returns null if the role is already held or unavailable.
     */
    fun createDefaultDialerIntent(context: Context): Intent? {
        if (isDefaultDialer(context)) {
            Log.d(TAG, "CallShare is already the default dialer; no request intent needed.")
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
            Log.e(TAG, "Failed to create RoleManager intent, falling back to legacy: ${e.message}", e)
            createLegacyDefaultDialerIntent(context)
        }
    }

    private fun createLegacyDefaultDialerIntent(context: Context): Intent {
        return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
        }
    }

    // -------------------------------------------------------------------------
    // TELECOM RELAY PREFERENCES
    // -------------------------------------------------------------------------

    /**
     * Feature 1: Relay incoming GSM calls over local LAN mesh
     * Default: true
     */
    fun isRelayGsmOverLanEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_RELAY_GSM_OVER_LAN, true)
    }

    fun setRelayGsmOverLanEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_RELAY_GSM_OVER_LAN, enabled).apply()
        Log.i(TAG, "Relay GSM over LAN set to: $enabled")
    }

    /**
     * Feature 2: Auto-wake AOD on incoming TCP CallIncoming packet
     * Default: true
     */
    fun isAutoWakeAodEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_AUTOWAKE_AOD, true)
    }

    fun setAutoWakeAodEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_AUTOWAKE_AOD, enabled).apply()
        Log.i(TAG, "Auto-wake AOD on incoming call set to: $enabled")
    }

    /**
     * Feature 3: Acquire exclusive transient audio focus
     * Default: true
     */
    fun isExclusiveAudioFocusEnabled(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_EXCLUSIVE_AUDIO_FOCUS, true)
    }

    fun setExclusiveAudioFocusEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_EXCLUSIVE_AUDIO_FOCUS, enabled).apply()
        Log.i(TAG, "Exclusive audio focus set to: $enabled")
    }
}
