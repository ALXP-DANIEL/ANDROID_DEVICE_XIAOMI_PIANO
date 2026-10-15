/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.os.UEventObserver

internal data class PenChargingEvent(
    val chargeState: Int?,
    val address: String?,
    val battery: Int?,
    val docked: Boolean?,
    val panelState: Int? = null,
    val hibernation: Boolean? = null,
)

internal class PenUEventObserver(
    private val onEvent: (PenChargingEvent) -> Unit,
) : UEventObserver(), AutoCloseable {
    @Volatile private var started = false

    fun start() {
        if (started) return
        started = true
        startObserving("SUBSYSTEM=power_supply")
        startObserving("MIPP_PEN_STATE=")
        startObserving("PEN_HIBERNATION_STATE=")
    }

    override fun onUEvent(event: UEvent) {
        if (!started) return
        val chargeState = event["POWER_SUPPLY_REVERSE_PEN_CHG_STATE"]?.toIntOrNull()
        val address = normalizeAddress(event["POWER_SUPPLY_PEN_MAC"])
        val battery = event["POWER_SUPPLY_REVERSE_PEN_SOC"]?.toIntOrNull()
            ?.takeIf { it in 0..100 }
        val hall3 = event["POWER_SUPPLY_PEN_HALL3"]?.toIntOrNull()?.takeIf { it in 0..1 }
        val hall4 = event["POWER_SUPPLY_PEN_HALL4"]?.toIntOrNull()?.takeIf { it in 0..1 }
        val docked = if (hall3 != null && hall4 != null) hall3 == 0 || hall4 == 0 else null
        val panelState = event["MIPP_PEN_STATE"]?.toIntOrNull()
        val hibernation = event["PEN_HIBERNATION_STATE"]?.toIntOrNull()
            ?.takeIf { it in 0..1 }?.let { it == 1 }
        if (chargeState != null || address != null || battery != null || docked != null ||
            panelState != null || hibernation != null
        ) {
            onEvent(PenChargingEvent(chargeState, address, battery, docked, panelState, hibernation))
        }
    }

    override fun close() {
        started = false
        stopObserving()
    }

    companion object {
        private val RAW_ADDRESS = Regex("^[0-9a-fA-F]{1,12}$")
        private val ADDRESS = Regex("^(?:[0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}$")

        fun normalizeAddress(value: String?): String? {
            val result = when {
                value == null -> return null
                ADDRESS.matches(value) -> value.uppercase()
                // The charger driver prints the MAC with %llx, without leading zeros.
                RAW_ADDRESS.matches(value) ->
                    value.uppercase().padStart(12, '0').chunked(2).joinToString(":")
                else -> return null
            }
            return result.takeUnless { it == "00:00:00:00:00:00" || it == "FF:FF:FF:FF:FF:FF" }
        }
    }
}
