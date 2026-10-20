/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

/** Fail-closed shade snapshot handshake; no notification/shade disable flags. */
internal class PenShadeGate {
    private var pending = 0L
    private var shadeOpen = false

    fun begin(request: Long): Boolean {
        if (request <= 0L || pending != 0L) return false
        pending = request
        return true
    }

    fun changed(open: Boolean): Boolean {
        shadeOpen = open
        // Stock palette closes on *every* shade or Control Center expansion
        // callback, including collapse. A pending query cannot outlive one.
        pending = 0L
        return true
    }

    fun answer(request: Long, open: Boolean): Boolean {
        if (request <= 0L || request != pending) return false
        pending = 0L
        if (open) shadeOpen = true
        return !open && !shadeOpen
    }

    fun cancel() { pending = 0L }
}
