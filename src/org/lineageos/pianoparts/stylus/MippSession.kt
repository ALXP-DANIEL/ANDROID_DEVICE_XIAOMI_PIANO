/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.stylus

import android.util.Log

/** All exchanges run on the controller handler, through its serialized GATT queue. */
internal class MippSession(
    private val localAddress: ByteArray,
    private val exchange: (ByteArray, Int, (ByteArray) -> Unit) -> Unit,
    private val setTouchMode: (Int, Int) -> Boolean,
    private val failed: () -> Unit,
    private val setFirmware: () -> Boolean = { true },
    private val onFeatures: (Int) -> Unit = {},
    private val onReady: () -> Unit = {},
    private val onDocked: (Boolean) -> Unit = {},
    private val onError: (Int) -> Unit = {},
    private val onFpsApplied: (Int) -> Unit = {},
) {
    var initialized = false
        private set
    private var closed = false
    private var second = false
    private var hall = 1
    private var id = 0
    private var pendingPanelState: Int? = null
    private var fps = 0
    private var pendingFps = false

    fun start(docked: Boolean, readActive: Boolean) {
        hall = if (docked) 0 else 1
        if (readActive) {
            command(byteArrayOf(0x52, 0), 1) {
                if (touch(29, it[2].toInt() and 255)) readInformation(0)
            }
        } else readInformation(0)
    }

    private fun readInformation(opcode: Int) {
        val length = intArrayOf(1, 2, 2, 3)[opcode]
        command(byteArrayOf(opcode.toByte(), 0), length) {
            if (opcode == 1) onFeatures((it[2].toInt() and 255) or ((it[3].toInt() and 255) shl 8))
            if (opcode < 3) readInformation(opcode + 1) else assignId()
        }
    }

    private fun assignId() {
        command(byteArrayOf(4, 0), 1) {
            id = it[2].toInt() and 255
            if (touch(22, (id shl 8) or if (second) 3 else 1)) {
                command(MippProtocol.parameters(localAddress, id, second), 0) {
                    command(MippProtocol.frequency(id, 0), 0) {
                        command(MippProtocol.voltage(id), 0) {
                            if (!second) {
                                second = true
                                assignId()
                            } else {
                                command(MippProtocol.timestamp(System.currentTimeMillis() / 1000), 0) {
                                    val sentHall = hall
                                    command(byteArrayOf(0x51, 1, sentHall.toByte()), 0) {
                                        if (!setFirmware()) {
                                            failed()
                                            return@command
                                        }
                                        if (touch(37, 1)) {
                                            initialized = true
                                            Log.i(TAG, "Pen MIPP initialization complete")
                                            onReady()
                                            if (hall != sentHall) {
                                                command(byteArrayOf(0x51, 1, hall.toByte()), 0) {}
                                            }
                                            pendingPanelState?.let(::onPanelState)
                                            pendingPanelState = null
                                            if (pendingFps) sendFpsParameters()
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    fun setDocked(docked: Boolean) {
        val value = if (docked) 0 else 1
        if (hall == value) return
        hall = value
        if (initialized) command(byteArrayOf(0x51, 1, hall.toByte()), 0) {}
    }

    fun onPanelState(value: Int) {
        if (!initialized) {
            pendingPanelState = value
            return
        }
        val opcode = (value ushr 16) and 255
        val panelId = (value ushr 8) and 255
        val operation = value and 255
        when (opcode) {
            0x31 -> if (operation in 0..3) {
                id = panelId
                command(MippProtocol.frequency(panelId, operation), 0) { touch(22, 49) }
            }
            0x35 -> if (operation in 0..1) {
                id = panelId
                command(byteArrayOf(0x62, 1, operation.toByte()), 0) {}
            }
            0x34 -> if (operation in 0..1) {
                id = panelId
                setting(byteArrayOf(0x5b, 1, operation.toByte()), 1, true)
            }
        }
    }

    fun sleep(sleeping: Boolean) {
        if (initialized) command(byteArrayOf(0x56, 1, if (sleeping) 1 else 0), 0) {}
    }

    fun onFps(value: Int) {
        if (closed || value < 0) return
        val normalized = if (value < 90) 60 else 90
        if (fps == normalized) return
        fps = normalized
        pendingFps = true
        if (initialized) sendFpsParameters()
    }

    private fun sendFpsParameters() {
        pendingFps = false
        val sentFps = fps
        // Stock worker 2021 resends B4 after the dual-ID stage resets phase to 1.
        // Piano's default profile is unchanged by 60/90; never select another
        // tablet's 34-byte timing profile based on Android version or refresh.
        command(MippProtocol.parameters(localAddress, id, false), 0) {
            onFpsApplied(sentFps)
        }
    }

    fun laser(on: Boolean) {
        if (initialized) command(byteArrayOf(0x50, 1, if (on) 1 else 0), 0) {}
    }

    fun screen(on: Boolean) {
        if (initialized) command(byteArrayOf(0x61, 1, if (on) 1 else 0), 0) {}
    }

    /** Nonuniform stock setting ACKs: status-bearing reports can reject without losing writing. */
    fun setting(bytes: ByteArray, replyLength: Int, successStatus: Boolean) {
        if (!initialized || closed) return
        command(bytes, replyLength) {
            if (successStatus && it[2] != 1.toByte()) {
                Log.w(TAG, "Pen rejected setting 0x${(bytes[0].toInt() and 255).toString(16)}")
                onError(4)
            }
        }
    }

    fun onNotification(value: ByteArray) {
        if (closed || value.size < 3 || value[1] != 1.toByte()) return
        when (value[0].toInt() and 255) {
            0x53 -> touch(29, value[2].toInt() and 255)
            0x55, 0xd4 -> if (value[2].toInt() in 0..1) onDocked(value[2] == 1.toByte())
            0x5d -> {
                onError(value[2].toInt() and 255)
                // Stock acknowledges error reports with dd00, which has no protocol reply.
                exchange(byteArrayOf(0xdd.toByte(), 0), -1) {}
            }
        }
    }

    fun close() {
        closed = true
        initialized = false
        setTouchMode(37, 0)
    }

    private fun command(bytes: ByteArray, length: Int, next: (ByteArray) -> Unit) {
        if (closed) return
        val opcode = (bytes[0].toInt() and 255) or 0x80
        Log.d(TAG, "Pen MIPP stage 0x${(bytes[0].toInt() and 255).toString(16)}")
        exchange(bytes, opcode) {
            if (!closed) {
                if (MippProtocol.validReply(it, opcode, length)) next(it) else {
                    Log.w(TAG, "Malformed pen reply for 0x${opcode.toString(16)}")
                    failed()
                }
            }
        }
    }

    private fun touch(mode: Int, value: Int): Boolean {
        if (setTouchMode(mode, value)) return true
        Log.w(TAG, "Could not configure pen touch mode $mode")
        failed()
        return false
    }

    companion object { private const val TAG = "PianoStylus" }
}
