/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package com.xiaomi.settings.touch

import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.ServiceManager
import android.util.Log

/**
 * Minimal client for Xiaomi's touchfeature HAL.
 *
 * There is no AIDL definition for vendor.xiaomi.hw.touchfeature in the tree and
 * the interface is not published anywhere, so the two calls we need are issued
 * as raw binder transactions. The transaction codes below were read out of
 * /vendor/lib64/vendor.xiaomi.hw.touchfeature-V1-ndk.so (AIDL assigns them in
 * declaration order, starting at 1):
 *
 *   1 getModeCurValue    5 getModeValue          9 setModeValue
 *   2 getModeDefaultValue 6 getTouchEvent       10 getModeCurValueString
 *   3 getModeMaxValue    7 modeReset            11 getModeWhiteList
 *   4 getModeMinValue    8 setModeLongValue     12 registerCallback
 *                                               13 unregisterCallback
 *                                               14 setModePackageName
 *
 * Note this is deliberately not the /dev/xiaomi-touch ioctl path that
 * UdfpsHandler uses: degas ships a newer xiaomi_touch driver whose ioctl ABI no
 * longer matches the old {disp_id, mode, value} int array, and it rejects those
 * calls outright ("error get hw param size 0 214"). Going through the HAL keeps
 * us on whatever ABI the blob and the kernel agree on.
 */
object TouchFeatureWrapper {

    private const val TAG = "TouchFeatureWrapper"
    private val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

    private const val DESCRIPTOR = "vendor.xiaomi.hw.touchfeature.ITouchFeature"
    private const val SERVICE = "$DESCRIPTOR/default"

    private const val TRANSACTION_GET_MODE_CUR_VALUE = 1
    private const val TRANSACTION_SET_MODE_VALUE = 9

    /** Primary touch panel. */
    const val TOUCH_ID_PRIMARY = 0

    /** MODE_TYPE values from the vendor's xiaomi_touch.h. */
    const val MODE_TOUCH_FOD_ENABLE = 10
    const val MODE_TOUCH_DOUBLETAP = 14

    @Volatile private var service: IBinder? = null

    private val deathRecipient =
        IBinder.DeathRecipient {
            if (DEBUG) Log.d(TAG, "serviceDied")
            service = null
        }

    @Synchronized
    private fun getService(): IBinder? =
        service
            ?: runCatching {
                Binder.allowBlocking(ServiceManager.waitForDeclaredService(SERVICE)).apply {
                    linkToDeath(deathRecipient, 0)
                }
            }
                .onSuccess { service = it }
                .onFailure { e -> Log.e(TAG, "getService failed!", e) }
                .getOrNull()

    fun setModeValue(touchId: Int, mode: Int, value: Int): Boolean =
        transact(TRANSACTION_SET_MODE_VALUE, touchId, mode, value) != null

    fun getModeCurValue(touchId: Int, mode: Int): Int? =
        transact(TRANSACTION_GET_MODE_CUR_VALUE, touchId, mode)

    private fun transact(code: Int, vararg args: Int): Int? {
        val binder =
            getService()
                ?: run {
                    Log.e(TAG, "touchfeature service is null!")
                    return null
                }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESCRIPTOR)
            args.forEach { data.writeInt(it) }
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (e: Exception) {
            Log.e(TAG, "transact $code failed!", e)
            null
        } finally {
            reply.recycle()
            data.recycle()
        }
    }
}
