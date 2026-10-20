/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID

/** Charger pairing and standard Bluetooth information, hosted by the Parts service. */
class StylusController(context: Context, handler: Handler) : AutoCloseable {
    private val context = context.applicationContext
    private val handler = Handler(handler.looper)
    private val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
    private var started = false
    private var closed = false
    private var receiverRegistered = false
    private var profileRequested = false
    private var hidHost: BluetoothProfile? = null
    private var status = StylusStatus()
    private var persistedAddress: String? = null
    private var chargeState: Int? = null
    private var hallStatus = HALL_STATUS_UNDOCKED
    private var lastRemovedPenAddress: String? = null
    private var lastPenBondRemoval = 0L
    private var gatt: BluetoothGatt? = null
    private var mippCommand: BluetoothGattCharacteristic? = null
    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()

    private val chargingObserver = PenUEventObserver { event -> dispatch { onChargingEvent(event) } }
    private val scanner = adapter?.let {
        StylusPairingScanner(it, this.handler) { address ->
            if (started) {
                if (address == null) {
                    updateState(StylusConnectionState.CONNECTION_FAILED)
                } else {
                    pairScannedPen(address)
                }
            }
        }
    }
    private val connectionTimeout = Runnable {
        if (started && status.state in PENDING_STATES) {
            scanner?.close()
            updateState(StylusConnectionState.CONNECTION_FAILED)
        }
    }
    private val gattTimeout = Runnable {
        Log.w(TAG, "Pen GATT operation timed out")
        closeGatt()
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            handler.post {
                if (!started || profile != BluetoothProfile.HID_HOST) {
                    adapter?.closeProfileProxy(profile, proxy)
                    return@post
                }
                hidHost?.takeUnless { it === proxy }?.let {
                    adapter?.closeProfileProxy(BluetoothProfile.HID_HOST, it)
                }
                hidHost = proxy
                profileRequested = false
                reconnect()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            dispatch {
                if (profile == BluetoothProfile.HID_HOST) {
                    hidHost = null
                    profileRequested = false
                    closeGatt()
                    updateState(StylusConnectionState.DISCONNECTED)
                }
            }
        }
    }

    // From the stylus key handler in system_server, which runs as the same uid.
    private val laserReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dispatch { setLaserMode(intent.getBooleanExtra(StylusSettingsContract.EXTRA_LASER_ON, false)) }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dispatch {
                if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    onAdapterState(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1))
                    return@dispatch
                }
                val device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java,
                ) ?: return@dispatch
                if (!device.address.equals(status.address, ignoreCase = true)) return@dispatch
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                            BluetoothDevice.BOND_BONDED -> connect(device)
                            BluetoothDevice.BOND_BONDING -> updateState(StylusConnectionState.PAIRING)
                            BluetoothDevice.BOND_NONE -> {
                                scanner?.close()
                                closeGatt()
                                updateState(StylusConnectionState.DISCONNECTED)
                            }
                        }
                    }
                    HID_STATE_CHANGED -> {
                        when (intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1)) {
                            BluetoothProfile.STATE_CONNECTED -> onConnected(device)
                            BluetoothProfile.STATE_CONNECTING -> updateState(StylusConnectionState.CONNECTING)
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                closeGatt()
                                updateState(StylusConnectionState.DISCONNECTED)
                            }
                        }
                    }
                    BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED -> {
                        setBattery(intent.getIntExtra(BluetoothDevice.EXTRA_BATTERY_LEVEL, -1))
                    }
                    BluetoothDevice.ACTION_NAME_CHANGED -> {
                        status = status.copy(name = device.name)
                        publish()
                    }
                }
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(client: BluetoothGatt, result: Int, state: Int) {
            dispatch {
                if (client !== gatt) return@dispatch
                if (result == BluetoothGatt.GATT_SUCCESS && state == BluetoothProfile.STATE_CONNECTED) {
                    armGattTimeout()
                    if (!client.discoverServices()) closeGatt()
                } else if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt()
                }
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, result: Int) {
            dispatch {
                if (client !== gatt) return@dispatch
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt()
                    return@dispatch
                }
                mippCommand = client.getService(MIPP_SERVICE)?.getCharacteristic(MIPP_COMMAND)
                val battery = client.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY)
                val info = client.getService(INFORMATION_SERVICE)
                listOfNotNull(battery, info?.getCharacteristic(FIRMWARE), info?.getCharacteristic(PNP_ID))
                    .forEach(pendingReads::add)
                val descriptor = battery?.getDescriptor(CLIENT_CONFIG)
                if (battery != null && descriptor != null &&
                    battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                    client.setCharacteristicNotification(battery, true)
                ) {
                    armGattTimeout()
                    if (client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                        BluetoothStatusCodes.SUCCESS
                    ) {
                        return@dispatch
                    }
                }
                readNextCharacteristic()
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) {
            dispatch {
                if (client === gatt && descriptor.uuid == CLIENT_CONFIG) readNextCharacteristic()
            }
        }

        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            result: Int,
        ) {
            dispatch {
                if (client !== gatt || pendingReads.peek()?.uuid != characteristic.uuid) return@dispatch
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    when (characteristic.uuid) {
                        BATTERY -> value.firstOrNull()?.let { setBattery(it.toInt() and 0xff) }
                        FIRMWARE -> {
                            status = status.copy(
                                firmwareRevision = value.toString(Charsets.UTF_8)
                                    .trimEnd('\u0000').takeIf(String::isNotBlank),
                            )
                        }
                        PNP_ID -> if (value.size >= 7) {
                            status = status.copy(vendorId = value.unsignedShort(1), productId = value.unsignedShort(3))
                        }
                    }
                    publish()
                }
                pendingReads.removeFirst()
                readNextCharacteristic()
            }
        }

        override fun onCharacteristicChanged(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            dispatch {
                if (client === gatt && characteristic.uuid == BATTERY) {
                    value.firstOrNull()?.let { setBattery(it.toInt() and 0xff) }
                }
            }
        }
    }

    fun start() {
        handler.post {
            if (started || closed) return@post
            started = true
            try {
                val saved = StylusStatusStore.snapshot(context)
                val address = PenUEventObserver.normalizeAddress(saved.address)
                    ?: PenUEventObserver.normalizeAddress(
                        Settings.Secure.getString(
                            context.contentResolver, PEN_ADDRESS_SETTING,
                        ),
                    )
                persistedAddress = address
                status = StylusStatus(address = address)
                context.registerReceiver(
                    receiver,
                    IntentFilter().apply {
                        addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                        addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                        addAction(BluetoothDevice.ACTION_BATTERY_LEVEL_CHANGED)
                        addAction(BluetoothDevice.ACTION_NAME_CHANGED)
                        addAction(HID_STATE_CHANGED)
                    },
                    null,
                    handler,
                    Context.RECEIVER_EXPORTED,
                )
                receiverRegistered = true
                context.registerReceiver(
                    laserReceiver,
                    IntentFilter(StylusSettingsContract.ACTION_LASER_MODE),
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                chargingObserver.start()
                if (adapter?.isEnabled == true) {
                    requestProfile()
                    reconnect()
                } else {
                    updateState(StylusConnectionState.BLUETOOTH_OFF)
                }
                publish()
            } catch (exception: RuntimeException) {
                Log.e(TAG, "Could not start pen management", exception)
                close()
            }
        }
    }

    override fun close() {
        handler.post {
            if (closed) return@post
            closed = true
            started = false
            handler.removeCallbacks(connectionTimeout)
            chargingObserver.close()
            if (receiverRegistered) {
                context.unregisterReceiver(receiver)
                context.unregisterReceiver(laserReceiver)
                receiverRegistered = false
            }
            scanner?.close()
            closeGatt()
            closeProfile()
            status = status.copy(state = StylusConnectionState.DISCONNECTED, battery = null, docked = false)
            publish()
        }
    }

    private fun dispatch(action: () -> Unit) {
        handler.post {
            if (started) {
                try {
                    action()
                } catch (exception: RuntimeException) {
                    Log.w(TAG, "Pen Bluetooth operation failed", exception)
                }
            }
        }
    }

    private fun onChargingEvent(event: PenChargingEvent) {
        if (event.chargeState != null && event.chargeState != chargeState) {
            chargeState = event.chargeState
            if (chargeState != PAIRING_CHARGE_STATE) scanner?.close()
        }
        if (event.address != null && chargeState == PAIRING_CHARGE_STATE) {
            onPenAddress(event.address)
        }
        event.docked?.let(::setDocked)
        if (chargeState == PAIRING_CHARGE_STATE) event.battery?.let(::setBattery)
        publish()
    }

    private fun onPenAddress(address: String) {
        removePreviousPenBond(address)
        if (address != status.address) {
            scanner?.close()
            closeGatt()
            status = StylusStatus(address = address, docked = status.docked)
        }
        val device = currentDevice()
        if (adapter?.isEnabled == true && device != null) {
            status = status.copy(name = device.name)
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                updateState(StylusConnectionState.CONNECTING)
                scanner?.start(device.address)
            } else {
                connect(device)
            }
        } else {
            updateState(StylusConnectionState.BLUETOOTH_OFF)
        }
    }

    private fun removePreviousPenBond(address: String) {
        if (adapter?.isEnabled != true) return
        // Only replace a pen this service previously managed. A device name
        // does not identify our accessory and must not remove another bond.
        val previousAddress = status.address ?: return
        if (previousAddress.equals(address, ignoreCase = true)) return
        val device = adapter.getRemoteDevice(previousAddress)
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        val now = SystemClock.elapsedRealtime()
        if (previousAddress.equals(lastRemovedPenAddress, ignoreCase = true) &&
            now - lastPenBondRemoval < PEN_BOND_REMOVE_COOLDOWN_MS
        ) {
            return
        }
        if (device.removeBond()) {
            lastRemovedPenAddress = previousAddress
            lastPenBondRemoval = now
        }
    }

    private fun setDocked(docked: Boolean) {
        status = status.copy(docked = docked)
        val value = if (docked) HALL_STATUS_DOCKED else HALL_STATUS_UNDOCKED
        if (value != hallStatus) {
            hallStatus = value
            Settings.System.putInt(context.contentResolver, HALL_STATUS_SETTING, value)
        }
    }

    private fun onAdapterState(state: Int) {
        if (state == BluetoothAdapter.STATE_ON) {
            requestProfile()
            reconnect()
        } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            scanner?.close()
            closeGatt()
            closeProfile()
            updateState(StylusConnectionState.BLUETOOTH_OFF)
        }
    }

    private fun requestProfile() {
        if (hidHost != null || profileRequested || adapter?.isEnabled != true) return
        profileRequested = adapter.getProfileProxy(context, profileListener, BluetoothProfile.HID_HOST)
    }

    private fun closeProfile() {
        val proxy = hidHost
        hidHost = null
        profileRequested = false
        if (proxy != null) adapter?.closeProfileProxy(BluetoothProfile.HID_HOST, proxy)
    }

    private fun reconnect() {
        val device = currentDevice() ?: return
        if (device.bondState != BluetoothDevice.BOND_NONE) {
            connect(device)
        } else {
            updateState(StylusConnectionState.DISCONNECTED)
        }
    }

    private fun pairScannedPen(address: String) {
        if (address != status.address || chargeState != PAIRING_CHARGE_STATE) return
        val device = currentDevice() ?: return
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            if (device.createBond(BluetoothDevice.TRANSPORT_LE)) {
                updateState(StylusConnectionState.PAIRING)
            } else {
                updateState(StylusConnectionState.CONNECTION_FAILED)
            }
        } else {
            connect(device)
        }
    }

    private fun connect(device: BluetoothDevice) {
        if (adapter?.isEnabled != true) return
        if (device.bondState == BluetoothDevice.BOND_BONDING) {
            updateState(StylusConnectionState.PAIRING)
            return
        }
        if (device.bondState != BluetoothDevice.BOND_BONDED) return
        when (hidHost?.getConnectionState(device)) {
            BluetoothProfile.STATE_CONNECTED -> onConnected(device)
            BluetoothProfile.STATE_CONNECTING -> updateState(StylusConnectionState.CONNECTING)
            BluetoothProfile.STATE_DISCONNECTED -> {
                if (device.connect() == BluetoothStatusCodes.SUCCESS) {
                    updateState(StylusConnectionState.CONNECTING)
                } else {
                    updateState(StylusConnectionState.CONNECTION_FAILED)
                }
            }
            else -> {
                requestProfile()
                updateState(StylusConnectionState.CONNECTING)
            }
        }
    }

    private fun onConnected(device: BluetoothDevice) {
        status = status.copy(name = device.name)
        updateState(StylusConnectionState.CONNECTED)
        if (status.battery == null) setBattery(device.batteryLevel)
        if (gatt == null) {
            gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
            if (gatt != null) armGattTimeout()
        }
    }

    private fun readNextCharacteristic() {
        handler.removeCallbacks(gattTimeout)
        val client = gatt ?: return
        while (pendingReads.isNotEmpty()) {
            if (client.readCharacteristic(pendingReads.first)) {
                armGattTimeout()
                return
            }
            pendingReads.removeFirst()
        }
    }

    private fun armGattTimeout() {
        handler.removeCallbacks(gattTimeout)
        handler.postDelayed(gattTimeout, 15_000L)
    }

    /**
     * Xiaomi pen laser (air mouse) mode, as the stock BluetoothExtension sets
     * it: write 0x50 0x01 <mode> to the "mipp" command characteristic.
     */
    private fun setLaserMode(on: Boolean) {
        val client = gatt ?: return
        val command = mippCommand ?: run {
            Log.w(TAG, "The pen has no laser command characteristic")
            return
        }
        val value = byteArrayOf(0x50, 0x01, if (on) 1 else 0)
        val result = client.writeCharacteristic(command, value, command.writeType)
        if (result != BluetoothStatusCodes.SUCCESS) {
            Log.w(TAG, "Pen laser mode write failed: $result")
        }
    }

    private fun closeGatt() {
        mippCommand = null
        handler.removeCallbacks(gattTimeout)
        pendingReads.clear()
        val client = gatt
        gatt = null
        if (client != null) {
            try {
                client.disconnect()
            } finally {
                client.close()
            }
        }
    }

    private fun currentDevice(): BluetoothDevice? = status.address?.let { adapter?.getRemoteDevice(it) }

    private fun updateState(state: StylusConnectionState) {
        if (status.state != state) {
            handler.removeCallbacks(connectionTimeout)
            if (state in PENDING_STATES) handler.postDelayed(connectionTimeout, 30_000L)
        }
        status = status.copy(
            state = state,
            battery = status.battery.takeIf {
                state == StylusConnectionState.CONNECTED || status.docked
            },
        )
        publish()
    }

    private fun setBattery(level: Int) {
        if (level in 0..100) {
            status = status.copy(battery = level)
            publish()
        }
    }

    private fun publish() {
        StylusStatusStore.publish(context, status)
        val address = status.address ?: return
        if (address == persistedAddress) return
        persistedAddress = address
        Settings.Secure.putString(
            context.contentResolver,
            PEN_ADDRESS_SETTING,
            address,
        )
        val dfu = dfuAddress(address) ?: return
        Settings.Secure.putString(
            context.contentResolver,
            PEN_DFU_ADDRESS_SETTING,
            dfu,
        )
    }

    private fun dfuAddress(address: String): String? {
        val mac = address.replace(":", "").toLongOrNull(16) ?: return null
        if (mac >= MAC_MAX) return null
        return String.format(Locale.US, "%012X", mac + 1).chunked(2).joinToString(":")
    }

    private fun ByteArray.unsignedShort(offset: Int): Int =
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)

    companion object {
        private const val TAG = "PianoStylus"
        private const val PAIRING_CHARGE_STATE = 4
        private const val PEN_ADDRESS_SETTING = "miui_bluetooth_store_pen_address"
        private const val PEN_DFU_ADDRESS_SETTING = "miui_bluetooth_store_pen_dfu_address"
        private const val HALL_STATUS_SETTING = "stylus_hall_status"
        private const val HALL_STATUS_DOCKED = 0
        private const val HALL_STATUS_UNDOCKED = 1
        private const val MAC_MAX = 0xFFFFFFFFFFFFL
        private const val PEN_BOND_REMOVE_COOLDOWN_MS = 5_000L
        private const val HID_STATE_CHANGED = "android.bluetooth.input.profile.action.CONNECTION_STATE_CHANGED"
        private val PENDING_STATES = setOf(StylusConnectionState.CONNECTING, StylusConnectionState.PAIRING)
        private fun uuid(value: String) = UUID.fromString("0000$value-0000-1000-8000-00805f9b34fb")
        private val BATTERY_SERVICE = uuid("180f")
        private val BATTERY = uuid("2a19")
        private val INFORMATION_SERVICE = uuid("180a")
        private val FIRMWARE = uuid("2a28")
        private val PNP_ID = uuid("2a50")
        private val CLIENT_CONFIG = uuid("2902")
        private val MIPP_SERVICE = UUID.fromString("0000fe10-aa6c-462a-964a-7f2ed5b3e512")
        private val MIPP_COMMAND = UUID.fromString("0000fe11-aa6c-462a-964a-7f2ed5b3e512")
    }
}
