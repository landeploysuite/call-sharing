package com.callshare.net

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import java.net.Inet4Address
import java.net.NetworkInterface

data class SimCardInfo(
    val slotIndex: Int,
    val subscriptionId: Int,
    val carrierName: String,
    val displayName: String,
    val countryIso: String,
    val isNetworkRoaming: Boolean
)

object NetworkUtils {
    private const val TAG = "CallShareNetworkUtils"

    /**
     * Resolves the real active site-local IPv4 address on the device (wlan0, ap0, or p2p0).
     * Never returns hardcoded loopback or mock IPs when an active Wi-Fi/Hotspot interface exists.
     */
    fun getLocalIpv4Address(context: Context): String {
        try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (connectivityManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val activeNetwork = connectivityManager.activeNetwork
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (linkAddr in linkProperties.linkAddresses) {
                        val addr = linkAddr.address
                        if (addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress) {
                            val ip = addr.hostAddress
                            if (!ip.isNullOrBlank()) {
                                Log.i(TAG, "Resolved active link IPv4: $ip via ${linkProperties.interfaceName}")
                                return ip
                            }
                        }
                    }
                }
            }

            // Fallback: Scan network interfaces with priority for Wi-Fi / Hotspot interfaces
            val interfaces = NetworkInterface.getNetworkInterfaces()?.toList() ?: emptyList()
            val preferredNames = listOf("wlan0", "ap0", "p2p0", "wlan1", "eth0")

            // First pass: look for preferred wireless interface
            for (name in preferredNames) {
                val iface = interfaces.firstOrNull { it.name.equals(name, ignoreCase = true) && it.isUp && !it.isLoopback }
                if (iface != null) {
                    for (addr in iface.inetAddresses) {
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val host = addr.hostAddress
                            if (!host.isNullOrBlank()) {
                                Log.i(TAG, "Resolved wireless IPv4 on ${iface.name}: $host")
                                return host
                            }
                        }
                    }
                }
            }

            // Second pass: any up, non-loopback site-local IPv4
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (addr in iface.inetAddresses) {
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress
                        if (!host.isNullOrBlank()) {
                            Log.i(TAG, "Resolved fallback IPv4 on ${iface.name}: $host")
                            return host
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error enumerating real local IPv4 addresses: ${e.message}", e)
        }
        return "127.0.0.1"
    }

    /**
     * Reads real hardware device brand and model.
     */
    fun getDeviceModelName(): String {
        val manufacturer = Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = Build.MODEL
        return if (model.startsWith(manufacturer, ignoreCase = true)) {
            model
        } else {
            "$manufacturer $model"
        }
    }

    /**
     * Detects real physical SIM cards and carrier details using TelephonyManager and SubscriptionManager.
     */
    fun getActiveSimCards(context: Context): List<SimCardInfo> {
        val simList = mutableListOf<SimCardInfo>()
        val hasPhonePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasPhonePermission) {
            Log.w(TAG, "READ_PHONE_STATE permission not granted; SIM detection deferred")
            return emptyList()
        }

        try {
            val subscriptionManager = context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            if (subscriptionManager != null) {
                val activeSubscriptions = subscriptionManager.activeSubscriptionInfoList
                if (!activeSubscriptions.isNullOrEmpty()) {
                    for (subInfo in activeSubscriptions) {
                        val carrier = subInfo.carrierName?.toString() ?: "Cellular"
                        val displayName = subInfo.displayName?.toString() ?: carrier
                        val country = subInfo.countryIso ?: ""
                        simList.add(
                            SimCardInfo(
                                slotIndex = subInfo.simSlotIndex,
                                subscriptionId = subInfo.subscriptionId,
                                carrierName = carrier,
                                displayName = displayName,
                                countryIso = country,
                                isNetworkRoaming = subInfo.dataRoaming == SubscriptionManager.DATA_ROAMING_ENABLE
                            )
                        )
                    }
                    return simList
                }
            }

            // Fallback: TelephonyManager operator name
            val telephonyManager = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            if (telephonyManager != null) {
                val operatorName = telephonyManager.networkOperatorName
                if (!operatorName.isNullOrBlank()) {
                    simList.add(
                        SimCardInfo(
                            slotIndex = 0,
                            subscriptionId = 0,
                            carrierName = operatorName,
                            displayName = operatorName,
                            countryIso = telephonyManager.networkCountryIso ?: "",
                            isNetworkRoaming = telephonyManager.isNetworkRoaming
                        )
                    )
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying active SIM hardware: ${e.message}")
        }
        return simList
    }
}
