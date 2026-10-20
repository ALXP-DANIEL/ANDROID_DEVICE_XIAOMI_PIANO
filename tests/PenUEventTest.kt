/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.os.UEventObserver.UEvent

internal fun checkPenUEvents() {
    val received = mutableListOf<PenChargingEvent>()
    val observer = PenUEventObserver { received.add(it) }
    observer.onUEvent(UEvent(mapOf("PEN_FPS" to "120")))
    check(received.isEmpty())
    observer.start()
    observer.onUEvent(UEvent(mapOf("PEN_FPS" to "120")))
    check(received.single().fps == 120 && received.single().placementError == null)
    observer.onUEvent(UEvent(mapOf("POWER_SUPPLY_PEN_PLACE_ERR" to "2")))
    check(received.last().placementError == 2 && received.last().fps == null)
    observer.onUEvent(UEvent(mapOf("POWER_SUPPLY_PEN_PLACE_ERR" to "0")))
    check(received.last().placementError == 0)
    observer.onUEvent(UEvent(mapOf("PEN_FPS" to "bad",
        "POWER_SUPPLY_PEN_PLACE_ERR" to "-1")))
    check(received.size == 3)
    observer.close()
    observer.onUEvent(UEvent(mapOf("PEN_FPS" to "60")))
    check(received.size == 3)
}
