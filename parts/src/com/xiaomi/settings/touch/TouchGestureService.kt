/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.app.Service
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Display

/**
 * Arms the touch controller's double-tap-to-wake gesture.
 *
 * The kernel side needs nothing from us: both touch controllers degas ships with
 * already decode the gesture and report it as KEY_WAKEUP (143), which Generic.kl
 * maps to WAKEUP, so the framework wakes the screen on its own. What is missing
 * is that nobody tells the panel to look for the gesture in the first place.
 *
 * Stock drives this through Touch_Doubletap_Mode on the touchfeature HAL, and so
 * do we. The alternative - writing the goodix driver's double_tap_enable sysfs
 * node - is deliberately not used: degas is dual-sourced (its dtbo carries a
 * goodix and a focaltech property set on the same pins, and both firmwares ship
 * in odm/firmware), and the focaltech driver exposes no such node. Going through
 * the HAL works whichever controller probed.
 *
 * config_supportDoubleTapWake in the frameworks overlay is what puts the toggle
 * in Settings and makes PowerManagerService track the secure setting; this
 * service is the half that reaches the hardware.
 */
class TouchGestureService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    private val settingsObserver =
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) = applySetting()
        }

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                // The driver reads the flag when it puts the panel into gesture
                // mode, so make sure it is current by the time the screen goes
                // dark. Re-asserting an unchanged value is a no-op for the HAL.
                if (getDisplayState() != Display.STATE_ON) applySetting()
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        contentResolver.registerContentObserver(
            Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE),
            false,
            settingsObserver,
            UserHandle.USER_ALL,
        )
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
        applySetting()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        contentResolver.unregisterContentObserver(settingsObserver)
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getDisplayState(): Int =
        getSystemService(DisplayManager::class.java)
            .getDisplay(Display.DEFAULT_DISPLAY)
            ?.state ?: Display.STATE_UNKNOWN

    private fun applySetting() {
        val enabled =
            Settings.Secure.getIntForUser(
                contentResolver,
                Settings.Secure.DOUBLE_TAP_TO_WAKE,
                0,
                UserHandle.USER_CURRENT,
            ) != 0
        if (DEBUG) Log.d(TAG, "double tap to wake enabled=$enabled")
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_DOUBLETAP,
            if (enabled) 1 else 0,
        )
    }

    companion object {
        private const val TAG = "TouchGestureService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        fun startService(context: Context) {
            context.startServiceAsUser(
                Intent(context, TouchGestureService::class.java),
                UserHandle.CURRENT,
            )
        }
    }
}
