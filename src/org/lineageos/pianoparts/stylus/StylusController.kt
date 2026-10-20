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
import android.os.PowerManager
import android.hardware.input.InputManager
import android.database.ContentObserver
import android.provider.Settings
import android.util.Log
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import org.lineageos.pianoparts.stylus.firmware.FirmwareImage
import org.lineageos.pianoparts.stylus.firmware.PenFirmwareRepository
import org.lineageos.pianoparts.stylus.firmware.PenFirmwareGatt

/** Magnetic charger pairing, MIPP touch initialization and Bluetooth information. */
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
    private var mippReply: BluetoothGattCharacteristic? = null
    private var mippSession: MippSession? = null
    private val laserPresentation = PenLaserController(this.context, this.handler,
        { mode -> mippSession?.laser(mode) },
        { started && screenOn && status.penInitialized && firmwareAdapter == null })
    private var firmwareAdapter: PenFirmwareGatt? = null
    private var firmwareImage: FirmwareImage? = null
    private val firmwareLoader = Executors.newSingleThreadExecutor()
    private var firmwareCheckGeneration = 0
    private var freshPenIdentity = false
    private var freshPenRevision = false
    private var freshPenBattery = false
    private var firmwareBeginPending = false
    private var firmwareImageRevision: String? = null
    private var firmwareVerifying = false
    private var firmwareAddress: String? = null
    private val firmwareWakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PianoStylus:firmware")
    private val firmwareVerifyTimeout = Runnable { finishFirmware("failed") }
    private val firmwareReconnect = object : Runnable {
        override fun run() {
            if (!started || !firmwareVerifying) return
            if (adapter?.isEnabled == true && gatt == null && status.address == firmwareAddress) {
                gattAttempts = 0
                currentDevice()?.let { connect(it); if (gatt == null) openGatt(it) }
            }
            handler.postDelayed(this, 2_000L)
        }
    }
    private val firmwareReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) { dispatch {
            if (!started || closed) return@dispatch
            when (intent.action) {
                ACTION_CHECK_FIRMWARE -> checkFirmware()
                ACTION_UPDATE_FIRMWARE -> beginFirmware()
            }
        } }
    }
    private val shortcutMenu = PenShortcutMenu(this.context, this.handler) {
        started && screenOn && status.penInitialized
    }
    private val touchClient = PenTouchClient()
    private var screenOn = context.getSystemService(PowerManager::class.java).isInteractive
    private val inputManager = context.getSystemService(InputManager::class.java)
    private val penInputs = mutableMapOf<Int, Int>()
    private val controlsChanged = Runnable { applyPenControls() }
    private val inputListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = updatePenInput(id)
        override fun onInputDeviceChanged(id: Int) = updatePenInput(id)
        override fun onInputDeviceRemoved(id: Int) {
            penInputs.remove(id)?.let {
                touchClient.setMode(20, it)
                Settings.Secure.putInt(context.contentResolver, "setting_stylus_version", 0)
            }
        }
    }
    private val controlObserver = object : ContentObserver(this.handler) {
        override fun onChange(selfChange: Boolean) {
            handler.removeCallbacks(controlsChanged)
            handler.postDelayed(controlsChanged, 100L)
        }
    }
    private var mippMtuPending = false
    private var mippNegotiatedMtu = 0
    private var gattAttempts = 0
    private var gattPhase = "idle"
    private var batteryNotifyWrite: Runnable? = null
    private var hibernating = false
    private var penFps: Int? = null
    private val pendingWrites = ArrayDeque<PenWrite>()
    private var activeWrite: PenWrite? = null
    private data class PenWrite(
        val bytes: ByteArray,
        val opcode: Int,
        val complete: (ByteArray) -> Unit,
        var written: Boolean = false,
        var reply: ByteArray? = null,
    )
    private val reconnectGatt = Runnable {
        if (started && status.state == StylusConnectionState.CONNECTED && gatt == null) {
            currentDevice()?.let(::openGatt)
        }
    }
    private val sleepPen = Runnable { mippSession?.sleep(true) }
    private val pendingReads = ArrayDeque<BluetoothGattCharacteristic>()
    // The MAC and charging-state uevents can be delivered separately. Never
    // auto-pair a persisted address unless this dock session reports its MAC.
    private var chargerAddress: String? = null
    private var pairingTarget: String? = null
    private var pairingAttempts = 0
    private val pairingRetry = Runnable {
        if (started && chargeState == PAIRING_CHARGE_STATE && pairingTarget == status.address) {
            startPenPairing()
        }
    }

    private val chargingObserver = PenUEventObserver { event -> dispatch { onChargingEvent(event) } }
    private val scanner = adapter?.let {
        StylusPairingScanner(it, this.handler) { address ->
            if (started) {
                if (address == null) {
                    onPairingFailure("BLE scan did not find the charger-matched pen")
                } else {
                    pairScannedPen(address)
                }
            }
        }
    }
    private val connectionTimeout = Runnable {
        if (started && status.state in PENDING_STATES) {
            scanner?.close()
            if (chargeState == PAIRING_CHARGE_STATE &&
                currentDevice()?.bondState == BluetoothDevice.BOND_NONE) {
                onPairingFailure("Pen connection timed out")
            } else {
                updateState(StylusConnectionState.CONNECTION_FAILED)
            }
        }
    }
    private val gattTimeout = Runnable {
        closeGatt("operation timed out")
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
            dispatch {
                if (intent.action == StylusSettingsContract.ACTION_HAPTIC) {
                    if (PenControls.isTouchFilm(status) && mippSession?.initialized == true) {
                        PenControls.haptic(this@StylusController.context,
                            intent.getIntExtra(StylusSettingsContract.EXTRA_HAPTIC_TYPE, 0))?.let {
                            mippSession?.setting(it, 0, false)
                        }
                    }
                } else setLaserMode(intent.getBooleanExtra(StylusSettingsContract.EXTRA_LASER_ON, false))
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            dispatch {
                if (intent.action == Intent.ACTION_SCREEN_ON || intent.action == Intent.ACTION_SCREEN_OFF) {
                    screenOn = intent.action == Intent.ACTION_SCREEN_ON
                    if (PenControls.isTouchFilm(status)) mippSession?.screen(screenOn)
                    return@dispatch
                }
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
                            BluetoothDevice.BOND_BONDED -> {
                                handler.removeCallbacks(pairingRetry)
                                scanner?.close()
                                Log.i(TAG, "Pen bonded; connecting HID profile")
                                connect(device)
                            }
                            BluetoothDevice.BOND_BONDING -> updateState(StylusConnectionState.PAIRING)
                            BluetoothDevice.BOND_NONE -> {
                                scanner?.close()
                                closeGatt()
                                val previous = intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE,
                                )
                                if (previous == BluetoothDevice.BOND_BONDING &&
                                    chargeState == PAIRING_CHARGE_STATE) {
                                    onPairingFailure("LE bonding failed")
                                } else {
                                    updateState(StylusConnectionState.DISCONNECTED)
                                }
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
                    gattPhase = "service discovery"
                    armGattTimeout()
                    if (!client.discoverServices()) closeGatt("service discovery request rejected")
                } else if (result != BluetoothGatt.GATT_SUCCESS || state == BluetoothProfile.STATE_DISCONNECTED) {
                    closeGatt("connection callback status=$result state=$state")
                }
            }
        }

        override fun onServicesDiscovered(client: BluetoothGatt, result: Int) {
            dispatch {
                if (client !== gatt) return@dispatch
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    closeGatt("service discovery failed: $result")
                    return@dispatch
                }
                mippCommand = client.getService(MIPP_SERVICE)?.getCharacteristic(MIPP_COMMAND)
                mippReply = client.getService(MIPP_SERVICE)?.getCharacteristic(MIPP_REPLY)
                val battery = client.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY)
                val info = client.getService(INFORMATION_SERVICE)
                Log.i(TAG, "Pen GATT services: battery=${battery != null} " +
                    "MIPP_FE11=${mippCommand != null} MIPP_FE12=${mippReply != null}")
                listOfNotNull(battery, info?.getCharacteristic(FIRMWARE), info?.getCharacteristic(PNP_ID))
                    .forEach(pendingReads::add)
                val descriptor = battery?.getDescriptor(CLIENT_CONFIG)
                if (battery != null && descriptor != null &&
                    battery.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0 &&
                    client.setCharacteristicNotification(battery, true)
                ) {
                    // HyperOS .44 defers the battery CCCD write by 100ms after discovery.
                    // Keep this on the controller handler; never overlap it with GATT reads.
                    gattPhase = "battery CCCD deferred"
                    armGattTimeout()
                    val subscription = Runnable {
                        if (client !== gatt) return@Runnable
                        batteryNotifyWrite = null
                        gattPhase = "battery CCCD write"
                        val submitted = client.writeDescriptor(
                            descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        if (submitted == BluetoothStatusCodes.SUCCESS) {
                            armGattTimeout()
                        } else {
                            Log.w(TAG, "Pen battery CCCD request rejected: $submitted; trying MIPP")
                            readNextCharacteristic()
                        }
                    }
                    batteryNotifyWrite = subscription
                    handler.postDelayed(subscription, 100L)
                    return@dispatch
                }
                Log.w(TAG, "Pen battery CCCD unavailable; continuing with MIPP initialization")
                readNextCharacteristic()
            }
        }

        override fun onDescriptorWrite(client: BluetoothGatt, descriptor: BluetoothGattDescriptor, result: Int) {
            dispatch {
                if (client !== gatt || descriptor.uuid != CLIENT_CONFIG) return@dispatch
                firmwareAdapter?.takeUnless { firmwareVerifying }?.let {
                    it.onDescriptorWrite(descriptor.characteristic.uuid, result)
                    if (it.isPrepared) it.start()
                    return@dispatch
                }
                if (descriptor.characteristic.uuid == MIPP_REPLY) {
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        closeGatt("MIPP FE12 notification descriptor failed: $result")
                    } else {
                        // Android may negotiate the shared MTU before service discovery.
                        // MIPP must never start until its reply notifications are ready.
                        if (mippNegotiatedMtu >= 35) {
                            handler.removeCallbacks(gattTimeout)
                            startMipp()
                        } else {
                            mippMtuPending = true
                            gattPhase = "MTU negotiation"
                            armGattTimeout()
                            if (!client.requestMtu(512)) closeGatt("MTU request rejected")
                        }
                    }
                } else {
                    if (result != BluetoothGatt.GATT_SUCCESS) {
                        Log.w(TAG, "Pen characteristic notification descriptor failed: $result; continuing")
                    }
                    readNextCharacteristic()
                }
            }
        }

        override fun onMtuChanged(client: BluetoothGatt, mtu: Int, result: Int) {
            dispatch {
                if (client !== gatt) return@dispatch
                firmwareAdapter?.takeUnless { firmwareVerifying }?.let {
                    it.onMtuChanged(mtu, result); return@dispatch
                }
                if (result == BluetoothGatt.GATT_SUCCESS) mippNegotiatedMtu = mtu
                if (!mippMtuPending) return@dispatch
                mippMtuPending = false
                handler.removeCallbacks(gattTimeout)
                if (result != BluetoothGatt.GATT_SUCCESS || mtu < 35) {
                    closeGatt("MTU negotiation failed: status=$result mtu=$mtu")
                }
                else startMipp()
            }
        }

        override fun onCharacteristicWrite(
            client: BluetoothGatt, characteristic: BluetoothGattCharacteristic, result: Int,
        ) {
            dispatch {
                if (client !== gatt) return@dispatch
                firmwareAdapter?.takeUnless { firmwareVerifying }?.let {
                    it.onCharacteristicWrite(characteristic.uuid, result); return@dispatch
                }
                if (characteristic.uuid != MIPP_COMMAND) return@dispatch
                val write = activeWrite ?: return@dispatch
                if (result != BluetoothGatt.GATT_SUCCESS) {
                    Log.w(TAG, "Pen write failed: $result")
                    closeGatt()
                } else {
                    write.written = true
                    finishWrite()
                }
            }
        }

        override fun onCharacteristicRead(
            client: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            result: Int,
        ) {
            dispatch {
                if (client !== gatt) return@dispatch
                firmwareAdapter?.takeUnless { firmwareVerifying }?.let {
                    it.onCharacteristicRead(characteristic.uuid, value, result); return@dispatch
                }
                if (firmwareBeginPending && characteristic.uuid == BATTERY) {
                    firmwareBeginPending = false
                    handler.removeCallbacks(gattTimeout)
                    val battery = value.firstOrNull()?.toInt()?.and(0xff)
                    freshPenBattery = result == BluetoothGatt.GATT_SUCCESS && battery != null && battery in 0..100
                    if (freshPenBattery) { setBattery(battery!!); checkFirmware(true) }
                    else { status = status.copy(updateState = "not_ready"); publish() }
                    writeNext()
                    return@dispatch
                }
                if (pendingReads.peek()?.uuid != characteristic.uuid) return@dispatch
                if (result == BluetoothGatt.GATT_SUCCESS) {
                    when (characteristic.uuid) {
                        BATTERY -> value.firstOrNull()?.let {
                            val level = it.toInt() and 0xff
                            if (level in 0..100) { freshPenBattery = true; setBattery(level) }
                        }
                        FIRMWARE -> {
                            status = status.copy(
                                firmwareRevision = value.toString(Charsets.UTF_8)
                                    .trimEnd('\u0000').takeIf(String::isNotBlank),
                            )
                            freshPenRevision = status.firmwareRevision != null
                        }
                        PNP_ID -> if (value.size >= 7) {
                            status = status.copy(vendorId = value.unsignedShort(1), productId = value.unsignedShort(3))
                            freshPenIdentity = true
                        }
                    }
                    verifyFirmwareIdentity()
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
                if (client !== gatt) return@dispatch
                firmwareAdapter?.takeUnless { firmwareVerifying }?.let {
                    it.onNotification(characteristic.uuid, value); return@dispatch
                }
                if (characteristic.uuid == BATTERY) {
                    value.firstOrNull()?.let { setBattery(it.toInt() and 0xff) }
                } else if (characteristic.uuid == MIPP_REPLY) {
                    mippSession?.onNotification(value)
                    val write = activeWrite
                    if (write != null && value.isNotEmpty() &&
                        (value[0].toInt() and 255) == write.opcode
                    ) {
                        write.reply = value.copyOf()
                        finishWrite()
                    }
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
                        addAction(Intent.ACTION_SCREEN_ON)
                        addAction(Intent.ACTION_SCREEN_OFF)
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
                context.registerReceiver(firmwareReceiver, IntentFilter().apply {
                    addAction(ACTION_CHECK_FIRMWARE); addAction(ACTION_UPDATE_FIRMWARE)
                }, null, handler, Context.RECEIVER_NOT_EXPORTED)
                context.registerReceiver(
                    laserReceiver,
                    IntentFilter(StylusSettingsContract.ACTION_LASER_MODE).apply {
                        addAction(StylusSettingsContract.ACTION_HAPTIC)
                    },
                    null,
                    handler,
                    Context.RECEIVER_NOT_EXPORTED,
                )
                touchClient.setMode(20, -1)
                inputManager.registerInputDeviceListener(inputListener, handler)
                inputManager.inputDeviceIds.forEach(::updatePenInput)
                PenControls.keys.forEach {
                    context.contentResolver.registerContentObserver(Settings.System.getUriFor(it), false, controlObserver)
                }
                shortcutMenu.start()
                laserPresentation.start()
                chargingObserver.start()
                Log.i(TAG, "Pen charger observer active; Bluetooth enabled=${adapter?.isEnabled == true}")
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
            firmwareCheckGeneration++
            firmwareLoader.shutdownNow()
            firmwareAdapter?.cancel()
            if (firmwareAdapter != null || firmwareVerifying) finishFirmware("failed")
            handler.removeCallbacks(connectionTimeout)
            handler.removeCallbacks(pairingRetry)
            chargerAddress = null
            shortcutMenu.close()
            laserPresentation.close()
            chargingObserver.close()
            context.contentResolver.unregisterContentObserver(controlObserver)
            handler.removeCallbacks(controlsChanged)
            inputManager.unregisterInputDeviceListener(inputListener)
            penInputs.clear()
            touchClient.setMode(20, -1)
            if (receiverRegistered) {
                context.unregisterReceiver(receiver)
                context.unregisterReceiver(laserReceiver)
                context.unregisterReceiver(firmwareReceiver)
                receiverRegistered = false
            }
            scanner?.close()
            pairingTarget = null
            pairingAttempts = 0
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
        val enteredPairing = event.chargeState == PAIRING_CHARGE_STATE &&
            chargeState != PAIRING_CHARGE_STATE
        event.address?.let { chargerAddress = it }
        if (event.chargeState != null && event.chargeState != chargeState) {
            chargeState = event.chargeState
            Log.i(TAG, "Pen charger state=$chargeState")
            if (chargeState != PAIRING_CHARGE_STATE) {
                scanner?.close()
                handler.removeCallbacks(pairingRetry)
                chargerAddress = null
                pairingTarget = null
                pairingAttempts = 0
                if (status.state in PENDING_STATES &&
                    currentDevice()?.bondState == BluetoothDevice.BOND_NONE) {
                    updateState(StylusConnectionState.DISCONNECTED)
                }
            }
        }
        if (chargeState == PAIRING_CHARGE_STATE && (event.address != null || enteredPairing)) {
            // A charger-state event may arrive after the MAC event. Only use
            // a MAC seen in this dock session, never an older saved address.
            (event.address ?: chargerAddress)?.let(::onPenAddress)
        }
        event.docked?.let(::setDocked)
        if (chargeState == PAIRING_CHARGE_STATE) event.battery?.let(::setBattery)
        event.panelState?.let {
            mippSession?.onPanelState(it)
        }
        event.fps?.let {
            penFps = it
            mippSession?.onFps(it)
        }
        event.placementError?.let { status = status.copy(placementError = it) }
        event.hibernation?.let {
            hibernating = it
            val sleepPending = handler.hasCallbacks(sleepPen)
            handler.removeCallbacks(sleepPen)
            if (it) handler.postDelayed(sleepPen, 5_000L)
            else if (!sleepPending) mippSession?.sleep(false)
        }
        publish()
    }

    private fun onPenAddress(address: String) {
        if (pairingTarget != address) {
            Log.i(TAG, "Pen charger supplied a valid MAC; preparing LE pairing")
            handler.removeCallbacks(pairingRetry)
            pairingTarget = address
            pairingAttempts = 0
        }
        if (firmwareAdapter != null && address != firmwareAddress) {
            firmwareAdapter?.cancel()
            finishFirmware("failed")
        }
        if (address != status.address) {
            firmwareCheckGeneration++
            firmwareImage = null
            firmwareImageRevision = null
        }
        if (address != status.address) gattAttempts = 0
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
                startPenPairing()
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
        if (docked && !status.docked) {
            gattAttempts = 0
            if (status.state == StylusConnectionState.CONNECTED && gatt == null) {
                currentDevice()?.let(::openGatt)
            }
        }
        status = status.copy(docked = docked)
        mippSession?.setDocked(docked)
        val value = if (docked) HALL_STATUS_DOCKED else HALL_STATUS_UNDOCKED
        if (value != hallStatus) {
            hallStatus = value
            Settings.System.putInt(context.contentResolver, HALL_STATUS_SETTING, value)
        }
    }

    private fun onAdapterState(state: Int) {
        if (state == BluetoothAdapter.STATE_ON) {
            Log.i(TAG, "Bluetooth ready; resuming charger-matched pen connection")
            // A previous Bluetooth-off state must not stick when no pen address
            // is known: reconnect() returns early in that case.
            if (status.state == StylusConnectionState.BLUETOOTH_OFF) {
                updateState(StylusConnectionState.DISCONNECTED)
            }
            requestProfile()
            reconnect()
        } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            scanner?.close()
            handler.removeCallbacks(pairingRetry)
            pairingAttempts = 0
            closeGatt()
            gattAttempts = 0
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
        } else if (chargeState == PAIRING_CHARGE_STATE && pairingTarget == device.address) {
            startPenPairing()
        } else {
            updateState(StylusConnectionState.DISCONNECTED)
        }
    }

    private fun startPenPairing() {
        val address = status.address ?: return
        if (adapter?.isEnabled != true || chargeState != PAIRING_CHARGE_STATE ||
            pairingTarget != address || scanner?.isScanning(address) == true ||
            handler.hasCallbacks(pairingRetry)) return
        val device = currentDevice() ?: return
        when (device.bondState) {
            BluetoothDevice.BOND_BONDED -> { connect(device); return }
            BluetoothDevice.BOND_BONDING -> {
                updateState(StylusConnectionState.PAIRING)
                return
            }
        }
        if (pairingAttempts >= MAX_PAIRING_ATTEMPTS) {
            updateState(StylusConnectionState.CONNECTION_FAILED)
            return
        }
        pairingAttempts++
        Log.i(TAG, "Scanning for pen ($pairingAttempts/$MAX_PAIRING_ATTEMPTS)")
        updateState(StylusConnectionState.CONNECTING)
        // A new 20-second scan needs its own connection deadline. Repeated
        // charger uevents cannot extend it or consume the retry budget.
        handler.removeCallbacks(connectionTimeout)
        handler.postDelayed(connectionTimeout, 30_000L)
        scanner?.start(address) ?: onPairingFailure("Bluetooth LE scanner unavailable")
    }

    private fun onPairingFailure(reason: String) {
        if (!started || chargeState != PAIRING_CHARGE_STATE ||
            pairingTarget != status.address || adapter?.isEnabled != true) return
        if (currentDevice()?.bondState != BluetoothDevice.BOND_NONE) return
        handler.removeCallbacks(connectionTimeout)
        if (pairingAttempts >= MAX_PAIRING_ATTEMPTS) {
            Log.w(TAG, "$reason; retry limit reached, redock to retry")
            updateState(StylusConnectionState.CONNECTION_FAILED)
        } else {
            Log.w(TAG, "$reason; retrying discovery after $PAIRING_RETRY_MS ms")
            updateState(StylusConnectionState.CONNECTING)
            handler.removeCallbacks(pairingRetry)
            handler.postDelayed(pairingRetry, PAIRING_RETRY_MS)
        }
    }

    private fun pairScannedPen(address: String) {
        if (address != status.address || chargeState != PAIRING_CHARGE_STATE) return
        val device = currentDevice() ?: return
        if (device.bondState == BluetoothDevice.BOND_NONE) {
            Log.i(TAG, "Pen advertisement matched; requesting LE bond")
            if (device.createBond(BluetoothDevice.TRANSPORT_LE)) {
                updateState(StylusConnectionState.PAIRING)
            } else {
                onPairingFailure("LE createBond request failed")
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
        handler.removeCallbacks(pairingRetry)
        scanner?.close()
        pairingAttempts = 0
        Log.i(TAG, "Pen HID connected; starting GATT initialization")
        status = status.copy(name = device.name)
        updateState(StylusConnectionState.CONNECTED)
        if (status.battery == null) setBattery(device.batteryLevel)
        if (gatt == null) openGatt(device)
    }

    private fun openGatt(device: BluetoothDevice) {
        mippMtuPending = false
        mippNegotiatedMtu = 0
        gattPhase = "connecting"
        freshPenIdentity = false
        freshPenBattery = false
        freshPenRevision = false
        if (gattAttempts >= 3) {
            Log.w(TAG, "Pen initialization retry limit reached; dock again to retry")
            return
        }
        gattAttempts++
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        if (gatt != null) armGattTimeout() else closeGatt()
    }

    private fun readNextCharacteristic() {
        handler.removeCallbacks(gattTimeout)
        val client = gatt ?: return
        while (pendingReads.isNotEmpty()) {
            gattPhase = "reading ${pendingReads.first.uuid}"
            if (client.readCharacteristic(pendingReads.first)) {
                armGattTimeout()
                return
            }
            Log.w(TAG, "Pen GATT characteristic read request rejected: ${pendingReads.first.uuid}")
            pendingReads.removeFirst()
        }
        val reply = mippReply ?: run { closeGatt("MIPP FE12 missing"); return }
        val command = mippCommand ?: run { closeGatt("MIPP FE11 missing"); return }
        val descriptor = reply.getDescriptor(CLIENT_CONFIG) ?: run {
            closeGatt("MIPP FE12 notification descriptor missing")
            return
        }
        if (reply.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0 ||
            command.properties and BluetoothGattCharacteristic.PROPERTY_WRITE == 0 ||
            !client.setCharacteristicNotification(reply, true)
        ) {
            closeGatt("MIPP notification/write properties or local subscription rejected")
            return
        }
        gattPhase = "MIPP FE12 CCCD write"
        armGattTimeout()
        val result = client.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        if (result != BluetoothStatusCodes.SUCCESS) closeGatt("MIPP FE12 CCCD request rejected: $result")
    }

    private fun startMipp() {
        gattPhase = "MIPP initialization"
        // Retry generation notification if the touch HAL was unavailable at Parts startup.
        inputManager.inputDeviceIds.forEach(::updatePenInput)
        val address = PenUEventObserver.normalizeAddress(adapter?.address)
        if (address == null || address == "02:00:00:00:00:00") {
            Log.w(TAG, "Cannot initialize pen without the tablet Bluetooth address")
            closeGatt()
            return
        }
        val bytes = address.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val session = MippSession(bytes, ::queueWrite, touchClient::setMode, ::closeGatt,
            setFirmware = { touchClient.setFirmware(status.firmwareRevision) },
            onFeatures = { features -> status = status.copy(features = features); publish() },
            onReady = {
                status = status.copy(penInitialized = true, penError = 0)
                publish()
                if (PenControls.isTouchFilm(status)) mippSession?.screen(screenOn)
                applyPenControls()
                // Keep a failed transfer visible after reconnect until the user checks again.
                if (firmwareAdapter == null && status.updateState != "complete" &&
                    status.updateState != "failed") checkFirmware()
            },
            onDocked = { setDocked(it); publish() },
            onError = { status = status.copy(penError = it); publish() },
            onFpsApplied = {
                try {
                    Settings.Secure.putInt(context.contentResolver, "miui_bluetooth_store_fps", it)
                } catch (failure: RuntimeException) {
                    // The parameter ACK already succeeded; a status-store failure
                    // must not interrupt the serialized pen transport.
                    Log.w(TAG, "Cannot publish acknowledged pen FPS", failure)
                }
            },
        )
        mippSession = session
        penFps?.let(session::onFps)
        val readActive = status.vendorId == 34 &&
            (status.productId == 0x5081 || status.productId == 0x4d80 &&
                firmwareAtLeast(status.firmwareRevision, 1, 0, 12))
        session.start(status.docked, readActive)
    }

    private fun firmwareAtLeast(value: String?, major: Int, minor: Int, patch: Int): Boolean {
        val numbers = value?.split(".")?.map { it.toIntOrNull() ?: return false } ?: return false
        if (numbers.size != 3) return false
        for ((actual, required) in numbers.zip(listOf(major, minor, patch))) {
            if (actual != required) return actual > required
        }
        return true
    }

    private fun updatePenInput(id: Int) {
        val type = XiaomiStylusDevice.typeOf(inputManager.getInputDevice(id))
        if (type == 0) {
            penInputs.remove(id)?.let { touchClient.setMode(20, it) }
            return
        }
        if (type != 0 && penInputs[id] != type) {
            if (touchClient.setMode(20, type or 16)) penInputs[id] = type
            Settings.Secure.putInt(context.contentResolver, "setting_stylus_version", type)
        }
    }

    private fun applyPenControls() {
        val session = mippSession ?: return
        if (!session.initialized) return
        if (PenControls.isTouchFilm(status)) {
            touchClient.setMode(39, if (PenControls.value(context, PenControls.HOVER, 1) != 0) 1 else 0)
        }
        for (control in PenControls.commands(context, status)) {
            session.setting(control.bytes, control.replyLength, control.successStatus)
        }
    }

    private fun queueWrite(bytes: ByteArray, opcode: Int, complete: (ByteArray) -> Unit) {
        if (firmwareAdapter != null && !firmwareVerifying) return
        if (mippSession?.initialized == true && bytes.isNotEmpty()) {
            // Stock replaces queued runtime settings by opcode; never alter the initializer.
            pendingWrites.removeIf { it.bytes.firstOrNull() == bytes[0] }
        }
        if (pendingWrites.size >= 32) {
            Log.w(TAG, "Pen command queue overflow")
            closeGatt()
            return
        }
        pendingWrites.add(PenWrite(bytes, opcode, complete))
        writeNext()
    }

    private fun writeNext() {
        if (firmwareBeginPending || activeWrite != null || pendingWrites.isEmpty()) return
        val client = gatt ?: return
        val command = mippCommand ?: return
        val write = pendingWrites.removeFirst()
        activeWrite = write
        armGattTimeout()
        if (client.writeCharacteristic(command, write.bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) !=
            BluetoothStatusCodes.SUCCESS
        ) closeGatt()
    }

    private fun finishWrite() {
        val write = activeWrite ?: return
        val reply = write.reply ?: if (write.opcode == -1) byteArrayOf() else return
        if (!write.written) return
        handler.removeCallbacks(gattTimeout)
        activeWrite = null
        val wasInitialized = mippSession?.initialized == true
        write.complete(reply)
        if (!wasInitialized && mippSession?.initialized == true) {
            gattAttempts = 0
            if (hibernating) {
                handler.removeCallbacks(sleepPen)
                handler.postDelayed(sleepPen, 5_000L)
            }
        }
        writeNext()
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
        mippSession?.laser(on)
    }

    private fun closeGatt(reason: String = "caller requested close") {
        if (gatt != null) Log.w(TAG, "Pen GATT closing: phase=$gattPhase " +
            "reason=$reason attempt=$gattAttempts")
        batteryNotifyWrite?.let { handler.removeCallbacks(it) }
        batteryNotifyWrite = null
        gattPhase = "idle"
        mippMtuPending = false
        mippNegotiatedMtu = 0
        laserPresentation.closePresentation()
        firmwareCheckGeneration++
        freshPenIdentity = false
        freshPenRevision = false
        freshPenBattery = false
        firmwareBeginPending = false
        if (!firmwareVerifying) {
            firmwareAdapter?.let { adapter ->
                firmwareAdapter = null
                adapter.disconnected()
            }
        }
        handler.removeCallbacks(reconnectGatt)
        handler.removeCallbacks(sleepPen)
        shortcutMenu.hide()
        mippSession?.close()
        mippSession = null
        status = status.copy(penInitialized = false, features = null)
        publish()
        mippReply = null
        pendingWrites.clear()
        activeWrite = null
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
        if (started && status.state == StylusConnectionState.CONNECTED && gattAttempts < 3) {
            handler.postDelayed(reconnectGatt, 2_000L)
        }
    }

    private fun currentDevice(): BluetoothDevice? = status.address?.let { adapter?.getRemoteDevice(it) }

    private fun checkFirmware(startUpdate: Boolean = false) {
        if (!started || firmwareAdapter != null) return
        val generation = ++firmwareCheckGeneration
        val client = gatt
        val snapshot = status
        firmwareImage = null
        firmwareLoader.execute {
            var image: FirmwareImage? = null
            val state = if (!FirmwareImage.supports(snapshot.vendorId ?: -1, snapshot.productId ?: -1)) "unsupported" else {
                try {
                    image = PenFirmwareRepository.find(snapshot.vendorId ?: -1, snapshot.productId ?: -1, snapshot.firmwareRevision)
                    if (image == null) "unavailable" else "available"
                } catch (exception: Exception) {
                    Log.w(TAG, "Bundled pen firmware validation failed", exception)
                    "invalid"
                }
            }
            handler.post {
                if (!started || generation != firmwareCheckGeneration || client !== gatt ||
                    snapshot.address != status.address || snapshot.firmwareRevision != status.firmwareRevision ||
                    snapshot.vendorId != status.vendorId || snapshot.productId != status.productId) return@post
                firmwareImage = image
                firmwareImageRevision = snapshot.firmwareRevision
                status = status.copy(updateState = state, updateVersion = image?.manifest?.targetVersion, updateProgress = 0)
                publish()
                if (startUpdate) startFirmware()
            }
        }
    }

    private fun beginFirmware() {
        if (firmwareAdapter != null || firmwareBeginPending || firmwareImage == null) return
        val client = gatt ?: return
        if (!freshPenIdentity || !freshPenRevision || !status.penInitialized ||
            activeWrite != null || pendingWrites.isNotEmpty() || pendingReads.isNotEmpty()) return
        val battery = client.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY) ?: return
        freshPenBattery = false
        firmwareBeginPending = true
        handler.postDelayed(gattTimeout, 15_000L)
        if (!client.readCharacteristic(battery)) {
            firmwareBeginPending = false
            handler.removeCallbacks(gattTimeout)
            status = status.copy(updateState = "not_ready"); publish()
        }
    }

    private fun startFirmware() {
        if (firmwareAdapter != null) return
        val image = firmwareImage ?: return
        val client = gatt ?: return
        if (status.state != StylusConnectionState.CONNECTED || !status.penInitialized ||
            status.vendorId != image.manifest.vendorId || status.productId != image.manifest.productId ||
            (status.battery ?: -1) < 20 ||
            status.penError != 0 ||
            !freshPenIdentity || !freshPenBattery || !freshPenRevision ||
            status.firmwareRevision != firmwareImageRevision ||
            activeWrite != null || pendingWrites.isNotEmpty() || pendingReads.isNotEmpty()
        ) {
            status = status.copy(updateState = "not_ready"); publish(); return
        }
        firmwareAddress = status.address
        firmwareVerifying = false
        handler.removeCallbacks(gattTimeout)
        handler.removeCallbacks(sleepPen)
        handler.removeCallbacks(controlsChanged)
        shortcutMenu.hide()
        mippSession?.close(); mippSession = null
        laserPresentation.closePresentation()
        status = status.copy(penInitialized = false, updateState = "updating", updateProgress = 0)
        publish()
        firmwareWakeLock.acquire(240_000L)
        val update = PenFirmwareGatt.create(client, handler, image, object : PenFirmwareGatt.Listener {
            override fun progress(percent: Int) {
                status = status.copy(updateProgress = percent); publish()
            }
            override fun finished(state: PenFirmwareGatt.State, reason: String) {
                Log.i(TAG, "Pen firmware $state: $reason")
                when (state) {
                    PenFirmwareGatt.State.VERIFY_REVISION -> {
                        firmwareVerifying = true
                        status = status.copy(updateState = "verifying"); publish()
                        handler.postDelayed(firmwareVerifyTimeout, 60_000L)
                        gattAttempts = 0
                        closeGatt()
                        handler.postDelayed(firmwareReconnect, 2_000L)
                    }
                    PenFirmwareGatt.State.COMPLETE -> finishFirmware("complete")
                    else -> {
                        finishFirmware("failed")
                        handler.post { if (started) { gattAttempts = 0; closeGatt() } }
                    }
                }
            }
        })
        firmwareAdapter = update
        try { update.prepare(image.manifest.vendorId, image.manifest.productId, status.battery ?: -1, true) }
        catch (exception: RuntimeException) {
            Log.w(TAG, "Pen firmware preparation failed", exception)
            firmwareAdapter = null; finishFirmware("failed"); closeGatt()
        }
    }

    private fun finishFirmware(state: String) {
        firmwareAdapter = null
        firmwareVerifying = false
        firmwareAddress = null
        handler.removeCallbacks(firmwareVerifyTimeout)
        handler.removeCallbacks(firmwareReconnect)
        if (firmwareWakeLock.isHeld) firmwareWakeLock.release()
        status = status.copy(updateState = state)
        publish()
    }

    private fun verifyFirmwareIdentity() {
        if (!firmwareVerifying || !freshPenIdentity || !freshPenRevision || !freshPenBattery) return
        if (status.address != firmwareAddress || status.vendorId != firmwareImage?.manifest?.vendorId ||
            status.productId != firmwareImage?.manifest?.productId) {
            finishFirmware("failed")
        } else firmwareAdapter?.verifyRevision(status.firmwareRevision)
    }

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
        const val ACTION_CHECK_FIRMWARE = "org.lineageos.pianoparts.stylus.CHECK_FIRMWARE"
        const val ACTION_UPDATE_FIRMWARE = StylusSettingsContract.ACTION_UPDATE_FIRMWARE
        private const val TAG = "PianoStylus"
        private const val PAIRING_CHARGE_STATE = 4
        private const val PEN_ADDRESS_SETTING = "miui_bluetooth_store_pen_address"
        private const val PEN_DFU_ADDRESS_SETTING = "miui_bluetooth_store_pen_dfu_address"
        private const val HALL_STATUS_SETTING = "stylus_hall_status"
        private const val HALL_STATUS_DOCKED = 0
        private const val HALL_STATUS_UNDOCKED = 1
        private const val MAC_MAX = 0xFFFFFFFFFFFFL
        private const val PEN_BOND_REMOVE_COOLDOWN_MS = 5_000L
        private const val MAX_PAIRING_ATTEMPTS = 3
        private const val PAIRING_RETRY_MS = 1_500L
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
        private val MIPP_REPLY = UUID.fromString("0000fe12-aa6c-462a-964a-7f2ed5b3e512")
        private val MIPP_COMMAND = UUID.fromString("0000fe11-aa6c-462a-964a-7f2ed5b3e512")
    }
}
