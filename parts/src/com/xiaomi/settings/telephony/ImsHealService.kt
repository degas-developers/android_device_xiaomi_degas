/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.telephony

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.os.UserHandle
import android.telephony.AccessNetworkConstants
import android.telephony.NetworkRegistrationInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.telephony.ims.ImsMmTelManager
import android.util.Log

/**
 * Brings the IMS PDN back after the modem has dropped it and forgotten to ask
 * for it again, which leaves the phone on LTE with no VoLTE until the radio is
 * reset by hand (airplane mode).
 *
 * **The IMS PDN is not set up by the framework - it is set up on demand from the
 * modem.** `IMS_BEARER_STATE_NOTIFY{action=1}` reaches `com.mediatek.ims`, whose
 * `DC-ims` state machine calls `requestNetwork`, and only then does
 * DataNetworkController bring the `ims` APN up. Nothing on the AP side holds a
 * standing request for it.
 *
 * Captured on a tester's device (Kyivstar, poor coverage), timestamps from his
 * log - the whole failure is 15 ms wide and happens while still on EDGE:
 * ```
 * 16:01:07.423 handleImsBearerNotify action=0     PDN torn down on the 2G drop
 * 16:01:56.886 handleImsBearerNotify action=1     modem asks for it back
 * 16:01:56.889 handleImsBearerNotify action=0     ... and cancels 3 ms later
 * 16:01:56.893 DC-ims InactiveState  EVENT_CONNECT -> requestNetwork
 * 16:01:56.896 DC-ims ActivatingState EVENT_DISCONNECT -> back to Inactive
 * 16:01:56.898 DC-ims releaseNetwork
 * 16:01:56.914 RmmDcEvent "ims pdn is already deactivated at MD"
 * 16:01:56.925 DNC-0 "All requests have been released. Will not evaluate retry."
 * 16:03:10     LTE is back - and the modem never asks again, ever.
 * ```
 * Everything above behaves as designed; the modem simply stops asking. Its
 * firmware is a stock prebuilt, so the cause is out of reach and only the
 * consequence can be treated: notice "data is on LTE/NR but there is no IMS
 * network" and poke the IMS stack so the modem re-requests the bearer.
 *
 * The poke is a radio power cycle, which is what airplane mode does to the
 * modem minus Wi-Fi and Bluetooth. Nothing gentler works: the tester tried
 * toggling the VoLTE setting by hand while the IMS PDN was missing and it did
 * not bring it back, which fits the diagnosis - the modem's bearer state is
 * what is stuck, and re-initialising MMTEL on the AP side does not touch it.
 * The cost is a few seconds without signal, so it is rationed: at most
 * [MAX_ATTEMPTS] cycles per outage, [COOLDOWN_MS] apart, never during a call.
 */
class ImsHealService : Service() {

    private lateinit var handlerThread: HandlerThread
    private lateinit var handler: Handler

    /** Consecutive polls that saw LTE/NR without an IMS network. */
    private var strikes = 0

    /** Pokes already spent on this outage; reset as soon as IMS comes back. */
    private var attempts = 0

    /** Uptime after which a new poke may be issued. */
    private var quietUntil = 0L

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                try {
                    check()
                } catch (e: Exception) {
                    // Never take the persistent app down over telephony state.
                    Log.e(TAG, "check failed", e)
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        handlerThread = HandlerThread(TAG)
        handlerThread.start()
        handler = Handler(handlerThread.looper)
        handler.postDelayed(pollRunnable, FIRST_POLL_DELAY_MS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        handlerThread.quitSafely()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun check() {
        val subId = SubscriptionManager.getDefaultVoiceSubscriptionId()
        if (!SubscriptionManager.isValidSubscriptionId(subId)) {
            reset()
            return
        }

        // An IMS network on any transport is a healthy state - over IWLAN it
        // means VoWiFi carries the registration and no cellular PDN is due.
        if (hasImsNetwork()) {
            reset()
            return
        }

        val telephonyManager =
            getSystemService(TelephonyManager::class.java).createForSubscriptionId(subId)

        if (!isOnLteOrNr(telephonyManager)) {
            // On 2G/3G, or out of service, the PDN is *supposed* to be gone.
            reset()
            return
        }

        // Toggling the setting under an active call would tear the call down.
        if (telephonyManager.callState != TelephonyManager.CALL_STATE_IDLE) return

        // If the user turned VoLTE off there is nothing to heal.
        val volteEnabled =
            try {
                ImsMmTelManager.createForSubscriptionId(subId).isAdvancedCallingSettingEnabled
            } catch (e: Exception) {
                if (DEBUG) Log.d(TAG, "cannot read VoLTE setting for sub $subId", e)
                return
            }
        if (!volteEnabled) {
            reset()
            return
        }

        strikes++
        if (DEBUG) Log.d(TAG, "no IMS network on LTE/NR, strike $strikes/$STRIKES_BEFORE_POKE")
        if (strikes < STRIKES_BEFORE_POKE) return

        val now = SystemClock.uptimeMillis()
        if (now < quietUntil) return
        if (attempts >= MAX_ATTEMPTS) {
            // The network itself may simply have no IMS here - stop nagging the
            // modem and wait for the RAT to change, which resets the counters.
            if (DEBUG) Log.d(TAG, "giving up after $attempts pokes")
            return
        }

        attempts++
        quietUntil = now + COOLDOWN_MS
        poke(telephonyManager)
    }

    /**
     * Cycles radio power, so the modem rebuilds its IMS bearer state and asks
     * for the PDN again. This is the airplane-mode cure minus Wi-Fi and
     * Bluetooth; the AP-side alternatives do not reach the stuck state.
     */
    private fun poke(telephonyManager: TelephonyManager) {
        Log.i(TAG, "IMS missing on LTE/NR, cycling radio power (attempt $attempts)")
        try {
            telephonyManager.setRadioPower(false)
        } catch (e: Exception) {
            Log.e(TAG, "cannot power the radio down", e)
            return
        }
        handler.postDelayed(
            {
                try {
                    telephonyManager.setRadioPower(true)
                } catch (e: Exception) {
                    // Leaving the radio down would be far worse than the bug.
                    Log.e(TAG, "cannot power the radio back up", e)
                }
            },
            POKE_GAP_MS,
        )
    }

    private fun reset() {
        if (strikes != 0 || attempts != 0) {
            if (DEBUG) Log.d(TAG, "healthy again, clearing counters")
        }
        strikes = 0
        attempts = 0
        quietUntil = 0L
    }

    private fun hasImsNetwork(): Boolean {
        val connectivityManager = getSystemService(ConnectivityManager::class.java)
        return connectivityManager.allNetworks.any {
            connectivityManager
                .getNetworkCapabilities(it)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS) == true
        }
    }

    private fun isOnLteOrNr(telephonyManager: TelephonyManager): Boolean {
        val registrationInfo =
            telephonyManager.serviceState?.getNetworkRegistrationInfo(
                NetworkRegistrationInfo.DOMAIN_PS,
                AccessNetworkConstants.TRANSPORT_TYPE_WWAN,
            ) ?: return false
        if (!registrationInfo.isRegistered) return false
        return when (registrationInfo.accessNetworkTechnology) {
            TelephonyManager.NETWORK_TYPE_LTE,
            TelephonyManager.NETWORK_TYPE_LTE_CA,
            TelephonyManager.NETWORK_TYPE_NR -> true
            else -> false
        }
    }

    companion object {
        private const val TAG = "ImsHeal"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        /*
         * The tester's outage lasted until he reached for airplane mode, so
         * there is no measured upper bound on how long the modem stays quiet -
         * but a healthy re-attach puts the PDN back within ~6 s (measured on
         * the developer's device, LTE returning after a 7 minute 2G soak).
         * Three strikes 15 s apart means a poke lands ~45 s into an outage,
         * comfortably clear of a slow-but-working recovery.
         */
        private const val POLL_INTERVAL_MS = 15_000L
        private const val STRIKES_BEFORE_POKE = 3

        /** Let the stack settle after boot before judging it. */
        private const val FIRST_POLL_DELAY_MS = 60_000L

        /** The radio has to stay down long enough for the modem to let go. */
        private const val POKE_GAP_MS = 3_000L

        /** A radio cycle plus a full re-attach and IMS registration fits here. */
        private const val COOLDOWN_MS = 180_000L

        /** Beyond this the network, not the modem, is the one without IMS. */
        private const val MAX_ATTEMPTS = 3

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, ImsHealService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
