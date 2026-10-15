package org.lineageos.pianoparts.stylus
fun main() {
    check(!PenLaserController.isReadyForBoot(-1, -1))
    check(!PenLaserController.isReadyForBoot(7, -1))
    check(!PenLaserController.isReadyForBoot(7, 1))
    check(!PenLaserController.isReadyForBoot(8, 7))
    check(PenLaserController.isReadyForBoot(7, 7))
    check(PenLaserController.isReadyForBoot(0, 0))
    println("Laser readiness absent/legacy/stale/current boot markers passed")
}
