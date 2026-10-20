package org.lineageos.pianoparts.stylus
fun main() {
    val defaults=listOf("assistant","notes","screenshot","settings")
    check(PenPalette.parseSelection("[]")==defaults)
    check(PenPalette.parseSelection("broken")==defaults)
    check(PenPalette.parseSelection("[\"notes\",\"notes\"]")==defaults)
    check(PenPalette.parseSelection("[\"arbitrary-intent\"]")==defaults)
    check(PenPalette.parseSelection("x".repeat(8193))==defaults)
    check(PenPalette.parseSelection("[\"notes\",\"app:com.example/.Main\",\"laser\"]")==listOf("notes","app:com.example/.Main","laser"))
    check(PenPalette.parseSelection("[\"notes\",\"settings\",\"assistant\",\"screenshot\",\"laser\",\"app:a/.B\",\"app:b/.C\"]")==defaults)
    println("Palette malformed/empty/duplicate/oversize/order/component guards passed")
}
