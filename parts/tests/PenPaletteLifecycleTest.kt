/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

/** Host-only tests: no Binder, SystemUI process, Bluetooth or hardware. */
fun main() {
    val shade = PenShadeGate()
    check(!shade.answer(10, false)) // Unsolicited/stale reply cannot open a menu.
    check(shade.begin(10))
    check(!shade.begin(11))
    check(shade.answer(10, false))
    check(!shade.answer(10, false)) // Reply consumed only once.

    check(shade.begin(12))
    check(shade.changed(true)) // Live expansion dismisses/cancels pending.
    check(!shade.answer(12, false))
    check(shade.begin(13))
    check(!shade.answer(13, false)) // Stale closed reply cannot override open event.
    check(!shade.changed(false))
    check(shade.begin(14))
    check(shade.answer(14, false))

    check(shade.begin(15))
    check(!shade.answer(15, true)) // Already-expanded panel rejects opening.
    check(shade.begin(16))
    shade.cancel() // Missing SystemUI/timeout, hide, rotation or process teardown.
    check(!shade.answer(16, false))

    val lease = PenLaserKeyLease()
    check(!lease.accepts(21, 34, 20609, 1_000))
    lease.update(true, 21, 34, 20609, 1_000)
    check(lease.accepts(21, 34, 20609, 1_000))
    check(lease.accepts(21, 34, 20609, 6_000))
    check(!lease.accepts(21, 34, 20609, 6_001))
    check(!lease.accepts(21, 34, 20609, 999)) // Clock rollback fails closed.
    check(!lease.accepts(22, 34, 20609, 2_000)) // Same-model, different pen denied.
    check(!lease.accepts(21, 34, 12929, 2_000))
    lease.update(true, -1, 34, 20609, 2_000)
    check(!lease.accepts(21, 34, 20609, 2_000))
    lease.update(true, 21, 34, 12929, 3_000)
    check(lease.accepts(21, 34, 12929, 3_000))
    lease.update(false, 21, 34, 12929, 3_010)
    check(!lease.accepts(21, 34, 12929, 3_010)) // Explicit stop revokes immediately.
    lease.update(true, 21, 34, 19840, 4_000)
    check(!lease.accepts(21, 34, 19840, 4_000)) // Generation-3 is not P81C.
    println("PASS: palette nonce/race/dismissal and bounded P81C key lease")
}
