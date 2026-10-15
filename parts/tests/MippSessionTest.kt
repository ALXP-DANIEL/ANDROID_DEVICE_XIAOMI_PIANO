/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import java.util.ArrayDeque

private data class Exchange(val bytes: ByteArray, val opcode: Int, val reply: (ByteArray) -> Unit)
private fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 255) }

fun main() {
    val address = byteArrayOf(0x12, 0x34, 0x56, 0x78, 0x11, 0x22)
    // Golden vectors from .44 piano overlay, not generated from the implementation.
    check(MippProtocol.parameters(address, 0x42, false).hex() ==
        "301e1234567811220342a01e3e171a041a0300000d680000052800000d68f401")
    check(MippProtocol.parameters(address, 0x43, true)[17] == 1.toByte())
    check(MippProtocol.frequency(0x42, 0).hex() == "310842039b6fef432800")
    check(MippProtocol.frequency(0x43, 3).hex() == "31084303204ef7213c00")
    check(MippProtocol.voltage(0x42).hex() == "3203422800")
    check(MippProtocol.timestamp(0x12345678).hex() == "330412345678")
    check(!MippProtocol.validReply(byteArrayOf(0x84.toByte(), 1), 0x84, 1))

    val queue = ArrayDeque<Exchange>()
    val modes = mutableListOf<Pair<Int, Int>>()
    var failures = 0
    val session = MippSession(address, { bytes, opcode, callback ->
        queue.add(Exchange(bytes, opcode, callback))
    }, { mode, value -> modes.add(mode to value); true }, { failures++ })
    session.start(true, false)
    val opcodes = listOf(0, 1, 2, 3, 4, 0x30, 0x31, 0x32, 4, 0x30, 0x31, 0x32, 0x33, 0x51)
    for ((index, opcode) in opcodes.withIndex()) {
        check(!session.initialized)
        check(queue.size == 1)
        val request = queue.removeFirst()
        check((request.bytes[0].toInt() and 255) == opcode)
        val payload = when (opcode) {
            0 -> byteArrayOf(0)
            1, 2 -> byteArrayOf(0, 0)
            3 -> byteArrayOf(1, 0, 0)
            4 -> byteArrayOf(if (index == 4) 0x42 else 0x43)
            else -> byteArrayOf()
        }
        if (opcode == 0x51) {
            check(request.bytes[2] == 0.toByte())
            session.setDocked(false) // Race: undocked while old dock command awaits ACK.
        }
        request.reply(byteArrayOf(request.opcode.toByte(), payload.size.toByte()) + payload)
    }
    check(session.initialized && failures == 0)
    check(modes.contains(22 to 0x4201) && modes.contains(22 to 0x4303))
    check(modes.last() == 37 to 1)
    val correctedHall = queue.removeFirst()
    check(correctedHall.bytes.hex() == "510101")
    correctedHall.reply(byteArrayOf(0xd1.toByte(), 0))
    session.onPanelState(0x314202)
    val frequency = queue.removeFirst()
    check(frequency.bytes.hex() == "31084203409cdd2c3c00")
    frequency.reply(byteArrayOf(0xb1.toByte(), 0))
    check(modes.last() == 22 to 49)
    session.onPanelState(0x354201)
    check(queue.removeFirst().bytes.hex() == "620101")
    session.sleep(true)
    val late = queue.removeFirst()
    check(late.bytes.hex() == "560101")
    session.close()
    late.reply(byteArrayOf(0xd6.toByte(), 0))
    check(!session.initialized && queue.isEmpty() && modes.last() == 37 to 0)

    val malformed = MippSession(address, { bytes, opcode, callback ->
        queue.add(Exchange(bytes, opcode, callback))
    }, { mode, value -> check(mode == 37 && value == 0); true }, { failures++ })
    malformed.start(false, false)
    queue.removeFirst().reply(byteArrayOf(0x80.toByte(), 1))
    check(failures == 1 && !malformed.initialized && queue.isEmpty())
    malformed.close()
    var rejected = 0
    val unavailablePanel = MippSession(address, { bytes, opcode, callback ->
        queue.add(Exchange(bytes, opcode, callback))
    }, { _, _ -> false }, { rejected++ })
    unavailablePanel.start(false, false)
    for (length in listOf(1, 2, 2, 3, 1)) {
        val request = queue.removeFirst()
        request.reply(byteArrayOf(request.opcode.toByte(), length.toByte()) + ByteArray(length))
    }
    check(rejected == 1 && queue.isEmpty() && !unavailablePanel.initialized)
    val active = MippSession(address, { bytes, opcode, callback ->
        queue.add(Exchange(bytes, opcode, callback))
    }, { mode, value -> modes.add(mode to value); true }, { error("Unexpected failure") })
    active.start(false, true)
    val activeRead = queue.removeFirst()
    check(activeRead.bytes.hex() == "5200")
    activeRead.reply(byteArrayOf(0xd2.toByte(), 1, 2))
    check(modes.last() == 29 to 2 && queue.first.bytes.hex() == "0000")
    val abandoned = queue.removeFirst()
    active.close()
    abandoned.reply(byteArrayOf(0x80.toByte(), 1, 0))
    check(queue.isEmpty())
    println("PASS: stock vectors, two-ID handshake, ACK gating, hall race, panel events, disconnect, malformed reply, panel failure, active-state initialization")
}
