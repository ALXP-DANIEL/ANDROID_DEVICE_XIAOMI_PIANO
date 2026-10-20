/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.content.Context
import android.provider.Settings

/** The Apple Bluetooth identity published by the optional ROM Bluetooth patch. */
object NativeIntegration {
    private lateinit var context: Context

    fun init(value: Context) { context = value.applicationContext }

    /** The patch marks each boot it ran on, so a ROM without it never looks ready. */
    fun isAvailable(): Boolean {
        if (!::context.isInitialized) return false
        val resolver = context.contentResolver
        val boot = Settings.Global.getInt(resolver, Settings.Global.BOOT_COUNT, -1)
        return boot >= 0 &&
            Settings.Global.getInt(resolver, "alxp_airpods_backend_boot", -2) == boot &&
            Settings.Global.getInt(resolver, "alxp_airpods_backend_version", 0) == 1
    }

    fun getIdentity(): Boolean = isAvailable() &&
        Settings.Global.getInt(context.contentResolver, "alxp_airpods_identity_enabled", 0) == 1

    fun setIdentity(enabled: Boolean) {
        if (!isAvailable()) return
        Settings.Global.putInt(context.contentResolver,
            "alxp_airpods_identity_enabled", if (enabled) 1 else 0)
    }

    /** Bluetooth applies the identity when it starts, so this is what AirPods see now. */
    fun identityActive(): Boolean = getIdentity()
}
