/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.view.InputDevice

/**
 * Xiaomi stylus generations as identified by the stock framework.
 */
object XiaomiStylusDevice {
    fun typeOf(device: InputDevice?): Int {
        if (device == null || device.isVirtual) return TYPE_NONE

        return when (device.vendorId) {
            VENDOR_XIAOMI -> when (device.productId) {
                60138 -> 1
                19841 -> 2
                else -> TYPE_NONE
            }

            VENDOR_PRIMAX -> when (device.productId) {
                19840, 12929, 12928 -> 3
                20099 -> 4
                12675 -> 5
                12931 -> 6
                20356 -> 7
                else -> TYPE_NONE
            }

            else -> TYPE_NONE
        }
    }

    fun isStylus(device: InputDevice?): Boolean = typeOf(device) != TYPE_NONE

    private const val TYPE_NONE = 0
    private const val VENDOR_PRIMAX = 34
    private const val VENDOR_XIAOMI = 6421
}
