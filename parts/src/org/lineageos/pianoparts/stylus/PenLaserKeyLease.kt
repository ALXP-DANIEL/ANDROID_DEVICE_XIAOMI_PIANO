/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

/** Short-lived process-local authorization from the active laser overlay. */
internal class PenLaserKeyLease {
    private data class State(val device: Int, val vendor: Int, val product: Int, val time: Long)

    @Volatile private var state: State? = null

    fun update(active: Boolean, device: Int, vendor: Int, product: Int, now: Long) {
        state = if (active && device >= 0 && vendor == 34 &&
            (product == 20609 || product == 12929))
            State(device, vendor, product, now) else null
    }

    fun accepts(device: Int, vendor: Int, product: Int, now: Long): Boolean {
        val current = state ?: return false
        return device == current.device && vendor == current.vendor && product == current.product &&
            now >= current.time && now - current.time <= 5_000L
    }
}
