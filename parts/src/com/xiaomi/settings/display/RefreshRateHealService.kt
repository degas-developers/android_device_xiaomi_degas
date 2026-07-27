/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.display

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemProperties
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * Puts the panel back to its top refresh rate after the fingerprint reader has
 * been used.
 *
 * Lighting LHBM reprograms the DDIC to 120 Hz and extinguishing it does not
 * restore the rate. MTK DRM is never told, so /proc/mtkfb and SurfaceFlinger
 * both keep reporting 144 Hz while the panel actually presents every 8.33 ms -
 * the mismatch, not the 120 Hz, is what makes every app render late (janky
 * frames 1.6% -> 14%, 50th percentile frame 6 ms -> 21 ms, measured through
 * SurfaceFlinger's presentToPresent histogram).
 *
 * Nothing on the panel side undoes it: LHBM off, FP_STATUS AUTH_STOP,
 * AOD_TO_NORMAL and OFF_TO_NORMAL_BACKLIGHT_RESTORE were all tried on hardware
 * and leave the panel at 120 Hz. Only a real DRM mode-set reprograms the DDIC,
 * which is why a screen off/on cycle, launching the camera or toggling Smooth
 * Display "fixed" it by accident. So force one.
 *
 * UdfpsHandler bumps [HEAL_PROP] when the finger lifts. **That has to be polled,
 * not watched:** SystemProperties.addChangeCallback only runs when the *calling
 * process* sets a property, so a change made by the fingerprint HAL never
 * reaches us (verified on hardware - the callback never fired once). Polling a
 * property is a read out of a shared mapping, so twice a second while the screen
 * is on costs nothing, and the display listener stops it entirely once the
 * screen goes off.
 */
class RefreshRateHealService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var lastTick = SystemProperties.get(HEAL_PROP, "")
    private var polling = false
    private var nudging = false

    private val healRunnable = Runnable { heal() }

    private val pollRunnable =
        object : Runnable {
            override fun run() {
                val tick = SystemProperties.get(HEAL_PROP, "")
                if (tick.isNotEmpty() && tick != lastTick) {
                    lastTick = tick
                    // A single press produces two or three onFingerUp calls
                    // (SystemUI's, onAcquired's and sometimes cancel's), so let
                    // them settle into one heal.
                    if (!nudging) {
                        handler.removeCallbacks(healRunnable)
                        handler.postDelayed(healRunnable, SETTLE_DELAY_MS)
                    }
                }
                handler.postDelayed(this, POLL_INTERVAL_MS)
            }
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                setPolling(getDisplayState() == Display.STATE_ON)
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
        setPolling(getDisplayState() == Display.STATE_ON)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.state ?: Display.STATE_UNKNOWN

    private fun setPolling(enabled: Boolean) {
        if (enabled == polling) return
        polling = enabled
        if (DEBUG) Log.d(TAG, "polling=$enabled")
        if (enabled) {
            // Swallow whatever arrived while the screen was off: the panel was
            // re-initialised on the way back up anyway.
            lastTick = SystemProperties.get(HEAL_PROP, "")
            handler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
        } else {
            handler.removeCallbacks(pollRunnable)
        }
    }

    /**
     * Forces one real mode-set, by moving whichever bound the current mode is
     * actually sitting against and putting it straight back.
     *
     * Capping the peak only moves the mode if it currently sits *above* the cap,
     * and raising the floor only moves it if it sits *below* - and the panel
     * drops to 60 Hz by itself whenever nothing is animating, so neither works
     * alone. Pick per call rather than doing both: two settings in flight at
     * once means a second press can read a nudged value as the "original" and
     * restore that, which pins min_refresh_rate to the top mode forever and
     * silently disables the peak half from then on. That regression shipped
     * once - hence [nudging], which drops ticks arriving mid-nudge.
     */
    private fun heal() {
        if (nudging) return
        val display =
            getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
                ?: return
        if (display.state != Display.STATE_ON) return

        val top = display.supportedModes.maxOf { it.refreshRate }
        val atTop = display.mode.refreshRate >= top - 1f
        val key = if (atTop) PEAK_REFRESH_RATE else MIN_REFRESH_RATE
        val original = Settings.System.getFloat(contentResolver, key, if (atTop) top else 0f)
        val nudged = if (atTop) NUDGE_HZ else top

        // Capped at or below the nudge, the framework asked for LHBM's own rate
        // anyway; floored at the top, someone else is already pinning it.
        if (atTop && original <= NUDGE_HZ) return
        if (!atTop && original >= top - 1f) return

        if (DEBUG) Log.d(TAG, "healing via $key: $original -> $nudged -> $original (top=$top)")
        nudging = true
        Settings.System.putFloat(contentResolver, key, nudged)
        handler.postDelayed(
            {
                Settings.System.putFloat(contentResolver, key, original)
                nudging = false
            },
            NUDGE_STEP_MS,
        )
    }

    companion object {
        private const val TAG = "RefreshRateHeal"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        /** Bumped by UdfpsHandler::onFingerUp on every LHBM episode. */
        private const val HEAL_PROP = "vendor.degas.display.heal_refresh_rate"

        /** Settings.System.PEAK_REFRESH_RATE / MIN_REFRESH_RATE, spelled out. */
        private const val PEAK_REFRESH_RATE = "peak_refresh_rate"
        private const val MIN_REFRESH_RATE = "min_refresh_rate"

        /** The panel's other high mode, so the nudge stays visually quiet. */
        private const val NUDGE_HZ = 120f

        /*
         * These three add up to how long the user keeps seeing the stutter
         * after lifting their finger, so they are as tight as the measurements
         * allow: a mode-set commits in 100 ms on this panel, and the two or
         * three ticks one press produces land within ~100 ms of each other.
         * The settle delay also keeps the mode-set clear of the LHBM-off the
         * HAL issues just before the tick - that runs on a panel kthread, and
         * healing underneath it would just get overwritten.
         */
        private const val POLL_INTERVAL_MS = 200L
        private const val SETTLE_DELAY_MS = 200L
        private const val NUDGE_STEP_MS = 200L

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, RefreshRateHealService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
