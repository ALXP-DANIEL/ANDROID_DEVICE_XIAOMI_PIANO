/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/
// Modified for ALXPParts: only the parts of LibrePods' AACPManager that
// switching needs (handshake, ownership, audio source, smart routing).

package org.alxp.parts.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.os.ParcelUuid
import android.util.Log
import java.nio.ByteBuffer

/** Apple Accessory Communication Protocol, switching subset. */
class Aacp(private val listener: Listener) {

    companion object {
        private const val TAG = "AlxpAacp"

        val UUID: ParcelUuid = ParcelUuid.fromString("74ec2172-0bad-4d01-8f77-997b2be0722a")
        const val PSM = 0x1001

        private const val SET_FEATURE_FLAGS: Byte = 0x4D
        private const val REQUEST_NOTIFICATIONS: Byte = 0x0F
        private const val CONTROL_COMMAND: Byte = 0x09
        private const val CONNECTED_DEVICES: Byte = 0x2E
        private const val AUDIO_SOURCE: Byte = 0x0E
        private const val SMART_ROUTING: Byte = 0x10
        private const val SMART_ROUTING_RESP: Byte = 0x11

        private const val OWNS_CONNECTION: Byte = 0x06

        private val HEADER = byteArrayOf(0x04, 0x00, 0x04, 0x00)
        private val MAC = Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}")

        /** Opens the AACP L2CAP channel through the hidden BluetoothSocket constructors. */
        fun createSocket(adapter: BluetoothAdapter, device: BluetoothDevice): BluetoothSocket {
            val type = 3 // L2CAP
            val specs = listOf(
                arrayOf(adapter, device, type, true, true, PSM, UUID), // A16 QPR3
                arrayOf(device, type, true, true, PSM, UUID),
                arrayOf(device, type, 1, true, true, PSM, UUID),
                arrayOf(type, 1, true, true, device, PSM, UUID),
                arrayOf(type, true, true, device, PSM, UUID)
            )
            var last: Exception? = null
            for (params in specs) {
                try {
                    val types = params.map { it::class.javaPrimitiveType ?: it::class.java }
                    val constructor = BluetoothSocket::class.java
                        .getDeclaredConstructor(*types.toTypedArray())
                    constructor.isAccessible = true
                    return constructor.newInstance(*params) as BluetoothSocket
                } catch (e: Exception) {
                    last = e
                }
            }
            throw IllegalStateException("No usable BluetoothSocket constructor", last)
        }

        private fun macBytes(mac: String) =
            mac.split(":").map { it.toInt(16).toByte() }.toByteArray().reversedArray()

        private fun hex(bytes: ByteArray) = bytes.joinToString(" ") { "%02X".format(it) }
    }

    enum class SourceType { NONE, CALL, MEDIA }

    data class AudioSource(val mac: String, val type: SourceType)
    data class ConnectedDevice(val mac: String)

    interface Listener {
        fun onOwnershipChanged(owns: Boolean)
        fun onOwnershipTaken(sender: String, reverseTapped: Boolean)
        fun onAudioSourceChanged(source: AudioSource)
        fun onConnectedDevicesChanged(added: List<ConnectedDevice>)
    }

    var socket: BluetoothSocket? = null

    /** Last OWNS_CONNECTION value, null until the AirPods report one. */
    var ownsState: Int? = null
        private set

    var connectedDevices: List<ConnectedDevice> = emptyList()
        private set

    var audioSource: AudioSource? = null
        private set

    val owns get() = ownsState == 1

    fun startSession() {
        send(byteArrayOf(0x00, 0x00, 0x04, 0x00, 0x01, 0x00, 0x02, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00))
        sendData(byteArrayOf(SET_FEATURE_FLAGS, 0x00, 0xD7.toByte(), 0, 0, 0, 0, 0, 0, 0))
        sendData(byteArrayOf(REQUEST_NOTIFICATIONS, 0x00,
            0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
    }

    fun disconnected() {
        ownsState = null
        connectedDevices = emptyList()
        audioSource = null
    }

    fun setOwns(owns: Boolean) {
        ownsState = if (owns) 1 else 0
        sendData(byteArrayOf(CONTROL_COMMAND, 0x00, OWNS_CONNECTION,
            if (owns) 0x01 else 0x00, 0x00, 0x00, 0x00))
    }

    fun receive(packet: ByteArray) {
        if (packet.size < 6 || !packet.copyOfRange(0, 4).contentEquals(HEADER)) return
        when (packet[4]) {
            CONTROL_COMMAND -> {
                if (packet.size < 11 || packet[6] != OWNS_CONNECTION) return
                ownsState = packet[7].toInt()
                listener.onOwnershipChanged(owns)
            }
            AUDIO_SOURCE -> {
                if (packet.size < 13) return
                val mac = packet.sliceArray(6..11).reversedArray()
                    .joinToString(":") { "%02X".format(it) }
                val type = SourceType.entries.getOrNull(packet[12].toInt()) ?: return
                AudioSource(mac, type).also {
                    audioSource = it
                    listener.onAudioSourceChanged(it)
                }
            }
            CONNECTED_DEVICES -> {
                if (packet.size < 9) return
                val old = connectedDevices
                val devices = mutableListOf<ConnectedDevice>()
                var offset = 9
                repeat(packet[8].toInt()) {
                    if (offset + 8 > packet.size) return@repeat
                    devices += ConnectedDevice(packet.sliceArray(offset until offset + 6)
                        .joinToString(":") { "%02X".format(it) })
                    offset += 8
                }
                connectedDevices = devices
                listener.onConnectedDevicesChanged(devices.filter { d -> old.none { it.mac == d.mac } })
            }
            SMART_ROUTING_RESP -> {
                if (packet.size < 12) return
                val text = packet.decodeToString()
                if ("SetOwnershipToFalse" in text) {
                    val sender = packet.sliceArray(6..11).reversedArray()
                        .joinToString(":") { "%02X".format(it) }
                    listener.onOwnershipTaken(sender, "ReverseBannerTapped" in text)
                }
            }
        }
    }

    /** Tells the other connected devices what the Pad is playing. */
    fun sendMediaInformation(self: String, streaming: Boolean) {
        val target = otherDevice(self) ?: return
        val buffer = ByteBuffer.allocate(138)
        buffer.put(macBytes(target))
        buffer.put(byteArrayOf(0x82.toByte(), 0x00))
        buffer.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        buffer.put("PlayingApp".toByteArray())
        buffer.put(0x56)
        buffer.put("com.google.ios.youtube".toByteArray())
        buffer.put(0x52)
        buffer.put("HostStreamingState".toByteArray())
        buffer.put(0x42)
        buffer.put((if (streaming) "YES" else "NO").toByteArray())
        buffer.put(0x49)
        buffer.put("btAddress".toByteArray())
        buffer.put(0x51)
        buffer.put(self.toByteArray())
        buffer.put("btName".toByteArray())
        buffer.put(0x47)
        buffer.put("Android".toByteArray())
        buffer.put(0x58)
        buffer.put("otherDevice".toByteArray())
        buffer.put("AudioCategory".toByteArray())
        buffer.put(byteArrayOf(0x31, 0x2D, 0x01))
        sendData(byteArrayOf(SMART_ROUTING, 0x00) + buffer.array())
    }

    /** Introduces the Pad to a device that just connected to the AirPods. */
    fun sendNewDevice(self: String, target: String) {
        if (!valid(self) || !valid(target)) return
        val info = ByteBuffer.allocate(116)
        info.put(macBytes(target))
        info.put(byteArrayOf(0x6C, 0x00))
        info.put(byteArrayOf(0x01, 0xE5.toByte(), 0x4A))
        info.put("playingApp".toByteArray())
        info.put(0x42)
        info.put("NA".toByteArray())
        info.put(0x52)
        info.put("hostStreamingState".toByteArray())
        info.put(0x42)
        info.put("NO".toByteArray())
        info.put(0x49)
        info.put("btAddress".toByteArray())
        info.put(0x51)
        info.put(self.toByteArray())
        info.put(0x46)
        info.put("btName".toByteArray())
        info.put(0x47)
        info.put("Android".toByteArray())
        info.put(0x58)
        info.put("otherDevice".toByteArray())
        info.put("AudioCategory".toByteArray())
        info.put(byteArrayOf(0x30, 0x64))
        sendData(byteArrayOf(SMART_ROUTING, 0x00) + info.array())

        val tipi = ByteBuffer.allocate(90)
        tipi.put(macBytes(target))
        tipi.put(byteArrayOf(0x52, 0x00))
        tipi.put(byteArrayOf(0x01, 0xE5.toByte()))
        tipi.put(0x48)
        tipi.put("idleTime".toByteArray())
        tipi.put(byteArrayOf(0x08, 0x47))
        tipi.put("newTipi".toByteArray())
        tipi.put(byteArrayOf(0x01, 0x49))
        tipi.put("btAddress".toByteArray())
        tipi.put(0x51)
        tipi.put(self.toByteArray())
        tipi.put(0x46)
        tipi.put("btName".toByteArray())
        tipi.put(0x47)
        tipi.put("Android".toByteArray())
        tipi.put(0x50)
        tipi.put("nearbyAudioScore".toByteArray())
        tipi.put(byteArrayOf(0x0E))
        sendData(byteArrayOf(SMART_ROUTING, 0x00) + tipi.array())
    }

    /** Asks the other devices to give up the AirPods (shows "Moved to ..." there). */
    fun sendHijack(self: String) {
        if (!valid(self)) return
        otherDevice(self)?.let { target ->
            val ui = ByteBuffer.allocate(134)
            ui.put(macBytes(target))
            ui.put(byteArrayOf(0x7E, 0x00))
            ui.put(byteArrayOf(0x01, 0xE6.toByte(), 0x5B))
            ui.put("SmartRoutingKeyShowNearbyUI".toByteArray())
            ui.put(0x01)
            ui.put(0x4A)
            ui.put("localscore".toByteArray())
            ui.put(0x31)
            ui.put(0x2D)
            ui.put(0x01)
            ui.put(0x46)
            ui.put("reasonHhijackv2".toByteArray())
            ui.put(0x51)
            ui.put("audioRoutingScore".toByteArray())
            ui.put(0xA2.toByte())
            ui.put(0x5F)
            ui.put("audioRoutingSetOwnershipToFalse".toByteArray())
            ui.put(0x01)
            ui.put(0x4B)
            ui.put("remotescore".toByteArray())
            ui.put(0xA2.toByte())
            sendData(byteArrayOf(SMART_ROUTING, 0x00) + ui.array())
        }
        for (device in connectedDevices) {
            if (device.mac == self) continue
            val buffer = ByteBuffer.allocate(106)
            buffer.put(macBytes(device.mac))
            buffer.put(byteArrayOf(0x62, 0x00))
            buffer.put(byteArrayOf(0x01, 0xE5.toByte()))
            buffer.put(0x4A)
            buffer.put("localscore".toByteArray())
            buffer.put(byteArrayOf(0x30, 0x64))
            buffer.put(0x46)
            buffer.put("reason".toByteArray())
            buffer.put(0x48)
            buffer.put("Hijackv2".toByteArray())
            buffer.put(0x51)
            buffer.put("audioRoutingScore".toByteArray())
            buffer.put(byteArrayOf(0x31, 0x2D, 0x01, 0x5F))
            buffer.put("audioRoutingSetOwnershipToFalse".toByteArray())
            buffer.put(0x01)
            buffer.put(0x4B)
            buffer.put("remotescore".toByteArray())
            buffer.put(0xA5.toByte())
            sendData(byteArrayOf(SMART_ROUTING, 0x00) + buffer.array())
        }
    }

    private fun otherDevice(self: String) =
        if (valid(self)) connectedDevices.firstOrNull { it.mac != self }?.mac else null

    private fun valid(mac: String) = mac.length == 17 && mac.matches(MAC)

    private fun sendData(data: ByteArray) = send(HEADER + data)

    @Synchronized
    private fun send(packet: ByteArray): Boolean {
        val s = socket ?: return false
        return try {
            if (!s.isConnected) return false
            s.outputStream.write(packet)
            s.outputStream.flush()
            true
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: ${hex(packet)}", e)
            false
        }
    }
}
