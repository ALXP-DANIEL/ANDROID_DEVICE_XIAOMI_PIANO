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
// Modified for ALXPParts: only the audio switching logic of LibrePods'
// AirPodsService.

package org.alxp.parts.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import android.view.KeyEvent
import androidx.preference.PreferenceManager
import org.alxp.parts.NativeIntegration
import org.alxp.parts.R
import org.alxp.parts.bluetooth.Aacp

class AirPodsService : Service(), Aacp.Listener {

    companion object {
        private const val TAG = "AlxpSwitching"
        private const val CHANNEL = "switching"

        private const val LOST_OWNERSHIP_GRACE_MS = 3_000L
        private const val SESSION_SETTLE_MS = 1_500L
        private const val KEY_ADDRESS = "airpods_address"
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var adapter: BluetoothAdapter
    private lateinit var audio: AudioManager
    private val aacp = Aacp(this)

    private var device: BluetoothDevice? = null
    private var pendingTakeOver: String? = null
    private var localMac = ""
    private var musicActive = false
    private var recentlyLostOwnership = false

    private val clearLostOwnership = Runnable { recentlyLostOwnership = false }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        adapter = getSystemService(BluetoothManager::class.java).adapter
        audio = getSystemService(AudioManager::class.java)
        NativeIntegration.init(this)
        localMac = adapter.address ?: ""

        startForeground(1, notification(null),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)

        registerReceiver(bluetoothReceiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }, RECEIVER_NOT_EXPORTED)
        audio.registerAudioPlaybackCallback(playbackCallback, handler)
        getSystemService(TelephonyManager::class.java)
            .registerTelephonyCallback(mainExecutor, callListener)

        findConnectedAirPods()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        unregisterReceiver(bluetoothReceiver)
        audio.unregisterAudioPlaybackCallback(playbackCallback)
        getSystemService(TelephonyManager::class.java).unregisterTelephonyCallback(callListener)
        try { aacp.socket?.close() } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    // --- Connection ---------------------------------------------------------

    private fun isAirPods(device: BluetoothDevice) = device.uuids?.contains(Aacp.UUID) == true

    private fun savedAirPods(): BluetoothDevice? {
        val address = prefs.getString(KEY_ADDRESS, null)
        return adapter.bondedDevices.firstOrNull { it.address == address }
            ?: adapter.bondedDevices.firstOrNull { isAirPods(it) }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val d = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,
                BluetoothDevice::class.java) ?: return
            if (!isAirPods(d)) return
            when (intent.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> connect(d)
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> if (d == device) {
                    try { aacp.socket?.close() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun findConnectedAirPods() {
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                proxy.connectedDevices.firstOrNull { isAirPods(it) }?.let { connect(it) }
                adapter.closeProfileProxy(profile, proxy)
            }
            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun connect(target: BluetoothDevice) {
        if (aacp.socket?.isConnected == true) return
        Thread({
            val socket = try {
                Aacp.createSocket(adapter, target).also { it.connect() }
            } catch (e: Exception) {
                Log.w(TAG, "AACP connection to ${target.address} failed", e)
                return@Thread
            }
            device = target
            aacp.socket = socket
            aacp.startSession()
            prefs.edit().putString(KEY_ADDRESS, target.address).apply()
            handler.post { updateNotification(target) }
            // The AirPods list their devices shortly after the session starts.
            handler.postDelayed({
                pendingTakeOver?.let { reason ->
                    pendingTakeOver = null
                    takeOver(reason)
                }
            }, SESSION_SETTLE_MS)
            Log.i(TAG, "Connected to ${target.address}")
            val buffer = ByteArray(1024)
            try {
                while (socket.isConnected) {
                    val read = socket.inputStream.read(buffer)
                    if (read < 0) break
                    if (read > 0) {
                        val packet = buffer.copyOf(read)
                        handler.post { aacp.receive(packet) }
                    }
                }
            } catch (e: Exception) {
                Log.i(TAG, "AACP connection closed: ${e.message}")
            }
            handler.post {
                aacp.disconnected()
                aacp.socket = null
                updateNotification(null)
            }
        }, "AlxpAacp").start()
    }

    // --- Switching ----------------------------------------------------------

    /** Asks the AirPods to move to the Pad, as an Apple device would. */
    fun takeOver(reason: String) {
        if (!NativeIntegration.identityActive()) {
            Log.d(TAG, "Not taking over for $reason: Apple identity is off")
            return
        }
        if (aacp.socket?.isConnected != true) {
            // Another device took the AirPods and they dropped the Pad: bring
            // the link back first, then ask once the control channel is up.
            val target = device ?: savedAirPods() ?: return
            Log.i(TAG, "Reconnecting ${target.address} to take over for $reason")
            pendingTakeOver = reason
            setAudioConnected(target, true)
            return
        }
        val target = device ?: return
        val source = aacp.audioSource
        val otherIsPlaying = source != null && source.type != Aacp.SourceType.NONE &&
            !source.mac.equals(localMac, ignoreCase = true)
        if (aacp.owns && !otherIsPlaying) return
        Log.i(TAG, "Taking over for $reason")
        aacp.setOwns(true)
        aacp.sendMediaInformation(localMac, reason == "music")
        aacp.sendHijack(localMac)
        setAudioConnected(target, true)
        if (reason == "music") {
            // A2DP needs a moment, and the AirPods pause playback on the move.
            handler.postDelayed({ mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY) }, 1500)
        }
    }

    override fun onOwnershipChanged(owns: Boolean) {
        if (owns) return
        Log.i(TAG, "Another device took the AirPods")
        lostOwnership()
    }

    override fun onOwnershipTaken(sender: String, reverseTapped: Boolean) {
        Log.i(TAG, "$sender took the AirPods (reverse: $reverseTapped)")
        aacp.setOwns(false)
        lostOwnership()
    }

    override fun onAudioSourceChanged(source: Aacp.AudioSource) {
        if (localMac.isNotEmpty() && source.type != Aacp.SourceType.NONE &&
            !source.mac.equals(localMac, ignoreCase = true)) {
            aacp.setOwns(false)
        }
    }

    override fun onConnectedDevicesChanged(added: List<Aacp.ConnectedDevice>) {
        added.filterNot { it.mac.equals(localMac, ignoreCase = true) }
            .forEach { aacp.sendNewDevice(localMac, it.mac) }
    }

    private fun lostOwnership() {
        recentlyLostOwnership = true
        handler.removeCallbacks(clearLostOwnership)
        handler.postDelayed(clearLostOwnership, LOST_OWNERSHIP_GRACE_MS)
        if (audio.isMusicActive) mediaKey(KeyEvent.KEYCODE_MEDIA_PAUSE)
        device?.let { setAudioConnected(it, false) }
    }

    /** Connects or drops the Pad's A2DP and headset links to the AirPods. */
    private fun setAudioConnected(target: BluetoothDevice, connected: Boolean) {
        val policy = if (connected) 100 else 0
        for (profile in intArrayOf(BluetoothProfile.A2DP, BluetoothProfile.HEADSET)) {
            adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                    try {
                        proxy.javaClass.getMethod("setConnectionPolicy",
                            BluetoothDevice::class.java, Int::class.javaPrimitiveType)
                            .invoke(proxy, target, policy)
                        if (connected) {
                            proxy.javaClass.getMethod("connect", BluetoothDevice::class.java)
                                .invoke(proxy, target)
                        }
                    } catch (e: ReflectiveOperationException) {
                        Log.w(TAG, "Profile $p policy change failed", e)
                    } finally {
                        adapter.closeProfileProxy(p, proxy)
                    }
                }
                override fun onServiceDisconnected(p: Int) {}
            }, profile)
        }
    }

    private fun mediaKey(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    // --- Triggers -----------------------------------------------------------

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
            val media = configs.any {
                it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MUSIC ||
                    it.audioAttributes.contentType == AudioAttributes.CONTENT_TYPE_MOVIE
            }
            val active = media && audio.isMusicActive
            if (active == musicActive) return
            musicActive = active
            aacp.sendMediaInformation(localMac, active)
            if (active && !recentlyLostOwnership) {
                takeOver("music")
            }
        }
    }

    private val callListener = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            if (state != TelephonyManager.CALL_STATE_IDLE) {
                takeOver("call")
            }
        }
    }

    // --- Notification -------------------------------------------------------

    private fun notification(connected: BluetoothDevice?): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.notification_channel), NotificationManager.IMPORTANCE_MIN))
        val text = if (connected != null) {
            getString(R.string.notification_connected, connected.alias ?: connected.name)
        } else {
            getString(R.string.notification_waiting)
        }
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_alxp_features)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(connected: BluetoothDevice?) {
        getSystemService(NotificationManager::class.java).notify(1, notification(connected))
    }
}
