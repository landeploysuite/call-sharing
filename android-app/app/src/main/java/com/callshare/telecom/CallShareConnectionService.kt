package com.callshare.telecom

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.DisconnectCause
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.util.Log
import com.callshare.native.NativeBridge
import com.callshare.ui.CallActivity

/**
 * CallShareConnectionService
 *
 * Full production-ready Telecom ConnectionService for CallShare P2P.
 * Enables the Android OS to treat relayed Wi-Fi P2P calls as native system calls,
 * supporting Bluetooth hands-free routing, car kit integration, and native telecom controls.
 */
class CallShareConnectionService : ConnectionService() {

    companion object {
        private const val TAG = "CallShareConnectionSvc"
        const val EXTRA_SESSION_ID = "com.callshare.EXTRA_SESSION_ID"
        const val EXTRA_CALLER_NAME = "com.callshare.EXTRA_CALLER_NAME"
        const val EXTRA_CALLER_NUMBER = "com.callshare.EXTRA_CALLER_NUMBER"
        const val EXTRA_SIM_SLOT = "com.callshare.EXTRA_SIM_SLOT"

        var activeConnection: CallShareConnection? = null
            private set
    }

    override fun onCreateIncomingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateIncomingConnection requested: ${request?.address}")
        val extras = request?.extras ?: Bundle()
        val sessionId = extras.getString(EXTRA_SESSION_ID) ?: "incoming-${System.currentTimeMillis()}"
        val callerName = extras.getString(EXTRA_CALLER_NAME) ?: "Relayed Caller"
        val callerNumber = request?.address?.schemeSpecificPart ?: extras.getString(EXTRA_CALLER_NUMBER) ?: "Unknown"

        val connection = CallShareConnection(this, sessionId, callerName, callerNumber, isIncoming = true)
        connection.setCallerDisplayName(callerName, TelecomManager.PRESENTATION_ALLOWED)
        connection.setAddress(Uri.fromParts("tel", callerNumber, null), TelecomManager.PRESENTATION_ALLOWED)
        connection.connectionCapabilities = (
            Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_MUTE or
            Connection.CAPABILITY_RESPOND_VIA_TEXT
        )
        connection.setRinging()

        activeConnection = connection
        return connection
    }

    override fun onCreateIncomingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateIncomingConnectionFailed for ${request?.address}")
        super.onCreateIncomingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    override fun onCreateOutgoingConnection(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ): Connection {
        Log.i(TAG, "onCreateOutgoingConnection requested to: ${request?.address}")
        val extras = request?.extras ?: Bundle()
        val sessionId = extras.getString(EXTRA_SESSION_ID) ?: "outgoing-${System.currentTimeMillis()}"
        val targetNumber = request?.address?.schemeSpecificPart ?: "Unknown"

        val connection = CallShareConnection(this, sessionId, "P2P Peer", targetNumber, isIncoming = false)
        connection.setAddress(request?.address, TelecomManager.PRESENTATION_ALLOWED)
        connection.connectionCapabilities = (
            Connection.CAPABILITY_SUPPORT_HOLD or
            Connection.CAPABILITY_MUTE
        )
        connection.setDialing()

        activeConnection = connection
        return connection
    }

    override fun onCreateOutgoingConnectionFailed(
        connectionManagerPhoneAccount: PhoneAccountHandle?,
        request: ConnectionRequest?
    ) {
        Log.e(TAG, "onCreateOutgoingConnectionFailed for ${request?.address}")
        super.onCreateOutgoingConnectionFailed(connectionManagerPhoneAccount, request)
    }

    /**
     * Inner Connection representation managing active call state transitions.
     */
    class CallShareConnection(
        private val service: CallShareConnectionService,
        val sessionId: String,
        val displayName: String,
        val phoneNumber: String,
        val isIncoming: Boolean
    ) : Connection() {

        init {
            audioModeIsVoip = true
        }

        override fun onAnswer(videoState: Int) {
            super.onAnswer(videoState)
            Log.i(TAG, "CallShareConnection onAnswer: $sessionId")
            setActive()

            NativeBridge.answerRelayedCall(sessionId)

            // Launch In-Call UI
            val intent = Intent(service, CallActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("EXTRA_SESSION_ID", sessionId)
                putExtra("EXTRA_CALLER_NAME", displayName)
                putExtra("EXTRA_CALLER_NUMBER", phoneNumber)
            }
            service.startActivity(intent)
        }

        override fun onReject() {
            super.onReject()
            Log.i(TAG, "CallShareConnection onReject: $sessionId")
            NativeBridge.rejectRelayedCall(sessionId, "user-rejected-native")
            setDisconnected(DisconnectCause(DisconnectCause.REJECTED))
            destroy()
            if (activeConnection == this) {
                activeConnection = null
            }
        }

        override fun onDisconnect() {
            super.onDisconnect()
            Log.i(TAG, "CallShareConnection onDisconnect: $sessionId")
            NativeBridge.hangupCall(sessionId, "user-hangup-native")
            setDisconnected(DisconnectCause(DisconnectCause.LOCAL))
            destroy()
            if (activeConnection == this) {
                activeConnection = null
            }
        }

        override fun onHold() {
            super.onHold()
            Log.i(TAG, "CallShareConnection onHold: $sessionId")
            setOnHold()
        }

        override fun onUnhold() {
            super.onUnhold()
            Log.i(TAG, "CallShareConnection onUnhold: $sessionId")
            setActive()
        }

        override fun onSilence() {
            super.onSilence()
            Log.i(TAG, "CallShareConnection onSilence: $sessionId")
        }
    }
}
