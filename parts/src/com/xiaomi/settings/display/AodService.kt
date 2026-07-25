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
import android.os.UserHandle
import android.util.Log
import android.view.Display
import com.xiaomi.settings.touch.TouchFeatureWrapper
import java.io.File

/**
 * Owns the hardware state that only matters while the display is dozing.
 *
 * On stock both of these are driven by MIUI's displayfeature and touchfeature
 * orchestration; on LineageOS nobody owns either, which is why AOD could come
 * up black and why the fingerprint reader went dead a few seconds into AOD.
 *
 * 1. DDIC AOD brightness. The framework contributes nothing here - the backlight
 *    is pinned at 0 while dozing (config_screenBrightnessDozeFloat) and all of
 *    the luminance comes from the panel's own AOD mode, selected through
 *    mi_display's doze_brightness node.
 *
 *    The node cannot be trusted as a cache of the DDIC's state: the panel driver
 *    sets its own value inside doze_enable, so a fresh doze entry already reads 1
 *    while the screen is dark, and writing 1 on top of that is dropped ("skip
 *    same doze_brightness set:1") without emitting a DSI command. Force the
 *    change by stepping through LBM first; the intermediate value is on screen
 *    for a few dozen milliseconds, at a point where the panel is coming up anyway.
 *
 * 2. UDFPS arming. With Touch_Fod_Enable clear the touch controller does not run
 *    FOD detection at all while dozing - verified on-device, the kernel logs zero
 *    goodix_set_fod_downup events for a finger that is physically on the sensor,
 *    so xiaomi.sensor.fod never fires and SystemUI never gets its AOD interrupt.
 *    UdfpsHandler cannot do this: it only ever sees onFingerDown, which is
 *    downstream of the detection we are trying to enable.
 */
class AodService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var wasDozing = false

    private val displayListener =
        object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}

            override fun onDisplayRemoved(displayId: Int) {}

            override fun onDisplayChanged(displayId: Int) {
                if (displayId != Display.DEFAULT_DISPLAY) return
                val state = getDisplayState()
                val isDozing = state == Display.STATE_DOZE || state == Display.STATE_DOZE_SUSPEND
                if (isDozing == wasDozing) return
                wasDozing = isDozing
                if (DEBUG) Log.d(TAG, "display state=$state dozing=$isDozing")
                handler.removeCallbacksAndMessages(null)
                setFodDetectionEnabled(isDozing)
                if (isDozing) {
                    // Let the panel finish entering AOD before overriding it.
                    handler.postDelayed({ applyDozeBrightness() }, ENTER_DELAY_MS)
                }
            }
        }

    override fun onCreate() {
        super.onCreate()
        if (DEBUG) Log.d(TAG, "onCreate")
        getSystemService(DisplayManager::class.java)
            .registerDisplayListener(displayListener, handler)
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

    private fun setFodDetectionEnabled(enabled: Boolean) {
        TouchFeatureWrapper.setModeValue(
            TouchFeatureWrapper.TOUCH_ID_PRIMARY,
            TouchFeatureWrapper.MODE_TOUCH_FOD_ENABLE,
            if (enabled) 1 else 0,
        )
    }

    private fun applyDozeBrightness() {
        write(DOZE_BRIGHTNESS_LBM)
        handler.postDelayed({ write(DOZE_BRIGHTNESS_HBM) }, STEP_DELAY_MS)
    }

    private fun write(value: Int) {
        runCatching { File(DOZE_BRIGHTNESS_NODE).writeText(value.toString()) }
            .onFailure { e -> Log.e(TAG, "failed to write doze_brightness=$value", e) }
    }

    companion object {
        private const val TAG = "AodService"
        private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        private const val DOZE_BRIGHTNESS_NODE =
            "/sys/class/mi_display/disp-DSI-0/doze_brightness"

        /** 0 = normal, 1 = HBM (60 nit), 2 = LBM (5 nit). */
        private const val DOZE_BRIGHTNESS_HBM = 1
        private const val DOZE_BRIGHTNESS_LBM = 2

        private const val ENTER_DELAY_MS = 250L
        private const val STEP_DELAY_MS = 50L

        fun startService(context: Context) {
            context.startServiceAsUser(Intent(context, AodService::class.java), UserHandle.CURRENT)
        }
    }
}
