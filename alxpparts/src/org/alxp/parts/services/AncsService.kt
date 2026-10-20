/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Log
import org.alxp.parts.R
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Shows iPhone notifications on the Pad through Apple Notification Center
 * Service (ANCS). The Pad advertises an ANCS solicitation; the iPhone connects
 * and pairs, and the Pad then reads notifications as a GATT client over the
 * same link, the way a smartwatch does.
 */
class AncsService : Service() {

    companion object {
        private const val TAG = "AlxpAncs"
        private const val SERVICE_CHANNEL = "iphone_link"
        private const val CHANNEL = "iphone"
        private const val ACTION = "org.alxp.parts.ANCS_ACTION"

        private val ANCS = UUID.fromString("7905F431-B5CE-4E99-A40F-4B1E122D00D0")
        private val NOTIFICATION_SOURCE = UUID.fromString("9FBF120D-6301-42D9-8C58-25E699A21DBD")
        private val CONTROL_POINT = UUID.fromString("69D1D8F3-45E1-49A8-9821-9BBDFDAAD9D9")
        private val DATA_SOURCE = UUID.fromString("22EAC6E9-24D6-4BB5-BE44-B36ACE7C7BFB")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        private const val EVENT_ADDED = 0
        private const val EVENT_MODIFIED = 1
        private const val EVENT_REMOVED = 2
        private const val FLAG_SILENT = 1
        private const val FLAG_PRE_EXISTING = 4
        private const val FLAG_POSITIVE = 8
        private const val FLAG_NEGATIVE = 16

        private const val CMD_NOTIFICATION_ATTRIBUTES = 0
        private const val CMD_APP_ATTRIBUTES = 1
        private const val CMD_PERFORM_ACTION = 2

        private const val ATTR_APP = 0
        private const val ATTR_TITLE = 1
        private const val ATTR_SUBTITLE = 2
        private const val ATTR_MESSAGE = 3
        private const val ATTR_POSITIVE_LABEL = 6
        private const val ATTR_NEGATIVE_LABEL = 7
        private const val MAX_LEN = 500
    }

    private class Pending(val uid: Int, val flags: Int)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var manager: BluetoothManager
    private lateinit var notifications: NotificationManager
    private var server: BluetoothGattServer? = null
    private var gatt: BluetoothGatt? = null
    private var advertising = false

    /** GATT allows one operation at a time; these run in order. */
    private val operations = ArrayDeque<(BluetoothGatt) -> Boolean>()
    private var busy = false

    private val waiting = ArrayDeque<Pending>()
    private val flagsByUid = mutableMapOf<Int, Int>()
    private val appNames = mutableMapOf<String, String>()
    private var response = ByteArrayOutputStream()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(BluetoothManager::class.java)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(SERVICE_CHANNEL,
            getString(R.string.ancs_service_channel), NotificationManager.IMPORTANCE_MIN))
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.ancs_channel), NotificationManager.IMPORTANCE_HIGH))
        startForeground(2, status(getString(R.string.ancs_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        server = manager.openGattServer(this, serverCallback)
        startAdvertising()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION) {
            val uid = intent.getIntExtra("uid", -1)
            val positive = intent.getBooleanExtra("positive", true)
            if (uid >= 0) performAction(uid, positive)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopAdvertising()
        gatt?.close()
        server?.close()
        notifications.cancelAll()
        super.onDestroy()
    }

    // --- Advertising and connection ----------------------------------------

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "Advertising failed: $errorCode")
            advertising = false
        }
    }

    private fun startAdvertising() {
        val advertiser = manager.adapter.bluetoothLeAdvertiser ?: return
        if (advertising) return
        val settings = AdvertiseSettings.Builder()
            .setConnectable(true)
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceSolicitationUuid(ParcelUuid(ANCS))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
        advertising = true
    }

    private fun stopAdvertising() {
        if (!advertising) return
        manager.adapter.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, state: Int) {
            handler.post {
                if (state == BluetoothProfile.STATE_CONNECTED && gatt == null) {
                    Log.i(TAG, "iPhone ${device.address} connected")
                    stopAdvertising()
                    gatt = device.connectGatt(this@AncsService, false, gattCallback,
                        BluetoothDevice.TRANSPORT_LE)
                }
            }
        }
    }

    private fun disconnected() {
        gatt?.close()
        gatt = null
        operations.clear()
        busy = false
        waiting.clear()
        notifications.notify(2, status(getString(R.string.ancs_waiting)))
        startAdvertising()
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
            handler.post {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    g.requestMtu(512)
                } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                    Log.i(TAG, "iPhone disconnected ($status)")
                    disconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            handler.post { g.discoverServices() }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            handler.post {
                val ancs = g.getService(ANCS)
                if (ancs == null) {
                    Log.w(TAG, "No ANCS on ${g.device.address}")
                    return@post
                }
                notifications.notify(2, status(getString(R.string.ancs_connected,
                    g.device.alias ?: g.device.name ?: g.device.address)))
                // Data Source first, so no attribute response is missed.
                subscribe(ancs.getCharacteristic(DATA_SOURCE))
                subscribe(ancs.getCharacteristic(NOTIFICATION_SOURCE))
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            // The iPhone answers an unencrypted write with an auth error and
            // starts pairing; Android retries once the link is bonded.
            handler.post { next() }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            handler.post { next() }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handler.post {
                when (c.uuid) {
                    NOTIFICATION_SOURCE -> onNotificationSource(value)
                    DATA_SOURCE -> onDataSource(value)
                }
            }
        }
    }

    private fun enqueue(operation: (BluetoothGatt) -> Boolean) {
        operations.addLast(operation)
        if (!busy) next()
    }

    private fun next() {
        busy = false
        val g = gatt ?: return
        while (operations.isNotEmpty()) {
            if (operations.removeFirst()(g)) {
                busy = true
                return
            }
        }
    }

    private fun subscribe(c: BluetoothGattCharacteristic?) {
        c ?: return
        enqueue { g ->
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCCD) ?: return@enqueue false
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothGatt.GATT_SUCCESS
        }
    }

    private fun writeControlPoint(command: ByteArray) {
        enqueue { g ->
            val c = g.getService(ANCS)?.getCharacteristic(CONTROL_POINT) ?: return@enqueue false
            g.writeCharacteristic(c, command, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) ==
                BluetoothGatt.GATT_SUCCESS
        }
    }

    // --- ANCS ----------------------------------------------------------------

    private fun onNotificationSource(value: ByteArray) {
        if (value.size < 8) return
        val event = value[0].toInt()
        val flags = value[1].toInt() and 0xFF
        val uid = le32(value, 4)
        when (event) {
            EVENT_ADDED, EVENT_MODIFIED -> {
                // Old notifications already on the iPhone stay there.
                if (event == EVENT_ADDED && flags and FLAG_PRE_EXISTING != 0) return
                flagsByUid[uid] = flags
                waiting.addLast(Pending(uid, flags))
                writeControlPoint(byteArrayOf(CMD_NOTIFICATION_ATTRIBUTES.toByte()) + le32(uid) +
                    byteArrayOf(ATTR_APP.toByte()) +
                    byteArrayOf(ATTR_TITLE.toByte()) + le16(MAX_LEN) +
                    byteArrayOf(ATTR_SUBTITLE.toByte()) + le16(MAX_LEN) +
                    byteArrayOf(ATTR_MESSAGE.toByte()) + le16(MAX_LEN) +
                    byteArrayOf(ATTR_POSITIVE_LABEL.toByte(), ATTR_NEGATIVE_LABEL.toByte()))
            }
            EVENT_REMOVED -> {
                flagsByUid.remove(uid)
                notifications.cancel(uid)
            }
        }
    }

    /** Responses can span several packets; parse once every attribute is in. */
    private fun onDataSource(value: ByteArray) {
        response.write(value)
        val data = response.toByteArray()
        if (data.isEmpty()) return
        when (data[0].toInt()) {
            CMD_NOTIFICATION_ATTRIBUTES -> {
                val attrs = parse(data, 5, 6) ?: return
                response = ByteArrayOutputStream()
                val uid = le32(data, 1)
                waiting.removeAll { it.uid == uid }
                show(uid, attrs)
            }
            CMD_APP_ATTRIBUTES -> {
                val end = (1 until data.size).firstOrNull { data[it] == 0.toByte() } ?: return
                val attrs = parse(data, end + 1, 1) ?: return
                response = ByteArrayOutputStream()
                val app = String(data, 1, end - 1)
                attrs[0]?.let { appNames[app] = it }
            }
            else -> response = ByteArrayOutputStream()
        }
    }

    /** Reads `count` (id, length, value) attributes from `start`, or null until complete. */
    private fun parse(data: ByteArray, start: Int, count: Int): Map<Int, String>? {
        val attrs = mutableMapOf<Int, String>()
        var i = start
        repeat(count) {
            if (i + 3 > data.size) return null
            val id = data[i].toInt()
            val length = le16(data, i + 1)
            if (i + 3 + length > data.size) return null
            attrs[id] = String(data, i + 3, length)
            i += 3 + length
        }
        return attrs
    }

    private fun show(uid: Int, attrs: Map<Int, String>) {
        val flags = flagsByUid[uid] ?: return
        val app = attrs[ATTR_APP].orEmpty()
        val appName = appNames[app] ?: app.substringAfterLast('.')
        if (app.isNotEmpty() && app !in appNames) {
            writeControlPoint(byteArrayOf(CMD_APP_ATTRIBUTES.toByte()) + app.toByteArray() +
                byteArrayOf(0, 0))
        }
        val title = attrs[ATTR_TITLE].orEmpty().ifEmpty { appName }
        val subtitle = attrs[ATTR_SUBTITLE].orEmpty()
        val message = attrs[ATTR_MESSAGE].orEmpty()
        val builder = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_feature_iphone)
            .setSubText(appName)
            .setContentTitle(title)
            .setContentText(if (subtitle.isNotEmpty()) "$subtitle · $message" else message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setOnlyAlertOnce(flags and FLAG_SILENT != 0)
            .setAutoCancel(true)
        if (flags and FLAG_POSITIVE != 0) {
            builder.addAction(action(uid, true, attrs[ATTR_POSITIVE_LABEL].orEmpty().ifEmpty { "OK" }))
        }
        if (flags and FLAG_NEGATIVE != 0) {
            builder.addAction(action(uid, false, attrs[ATTR_NEGATIVE_LABEL].orEmpty().ifEmpty { "Clear" }))
        }
        notifications.notify(uid, builder.build())
    }

    private fun action(uid: Int, positive: Boolean, label: String): Notification.Action {
        val intent = Intent(this, AncsService::class.java).setAction(ACTION)
            .putExtra("uid", uid).putExtra("positive", positive)
        val pending = PendingIntent.getService(this, uid * 2 + if (positive) 0 else 1, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pending).build()
    }

    private fun performAction(uid: Int, positive: Boolean) {
        writeControlPoint(byteArrayOf(CMD_PERFORM_ACTION.toByte()) + le32(uid) +
            byteArrayOf(if (positive) 0 else 1))
        notifications.cancel(uid)
    }

    private fun status(text: String): Notification = Notification.Builder(this, SERVICE_CHANNEL)
        .setSmallIcon(R.drawable.ic_feature_iphone)
        .setContentTitle(getString(R.string.iphone_title))
        .setContentText(text)
        .setOngoing(true)
        .build()

    private fun le32(data: ByteArray, at: Int) = (data[at].toInt() and 0xFF) or
        ((data[at + 1].toInt() and 0xFF) shl 8) or ((data[at + 2].toInt() and 0xFF) shl 16) or
        ((data[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(data: ByteArray, at: Int) =
        (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)

    private fun le32(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte(),
        (value shr 16).toByte(), (value shr 24).toByte())

    private fun le16(value: Int) = byteArrayOf(value.toByte(), (value shr 8).toByte())
}
