/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.content.Context
import android.content.SharedPreferences

enum class StylusConnectionState {
    DISCONNECTED,
    CONNECTING,
    PAIRING,
    CONNECTED,
    BLUETOOTH_OFF,
    CONNECTION_FAILED,
}

data class StylusStatus(
    val state: StylusConnectionState = StylusConnectionState.DISCONNECTED,
    val name: String? = null,
    val address: String? = null,
    val battery: Int? = null,
    val docked: Boolean = false,
    val firmwareRevision: String? = null,
    val vendorId: Int? = null,
    val productId: Int? = null,
)

object StylusStatusStore {
    fun preferences(context: Context): SharedPreferences =
        context.createDeviceProtectedStorageContext()
            .getSharedPreferences("stylus_status", Context.MODE_PRIVATE)

    fun snapshot(context: Context): StylusStatus {
        val prefs = preferences(context)
        val state = StylusConnectionState.entries.firstOrNull {
            it.name == prefs.getString("state", null)
        } ?: StylusConnectionState.DISCONNECTED
        return StylusStatus(
            state = state,
            name = prefs.getString("name", null),
            address = prefs.getString("address", null),
            battery = prefs.getInt("battery", -1).takeIf { it in 0..100 },
            docked = prefs.getBoolean("docked", false),
            firmwareRevision = prefs.getString("firmware", null),
            vendorId = prefs.getInt("vendor", -1).takeIf { it >= 0 },
            productId = prefs.getInt("product", -1).takeIf { it >= 0 },
        )
    }

    internal fun publish(context: Context, status: StylusStatus) {
        preferences(context).edit()
            .putString("state", status.state.name)
            .putString("name", status.name)
            .putString("address", status.address)
            .putInt("battery", status.battery ?: -1)
            .putBoolean("docked", status.docked)
            .putString("firmware", status.firmwareRevision)
            .putInt("vendor", status.vendorId ?: -1)
            .putInt("product", status.productId ?: -1)
            .apply()
    }
}
