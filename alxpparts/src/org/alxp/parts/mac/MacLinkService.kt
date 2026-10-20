/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts.mac

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import org.alxp.parts.Feature
import org.alxp.parts.R
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.core.content.FileProvider

/**
 * Serves ALXP for Mac on the local network: shared clipboard and the Pad as a
 * second Mac display. Advertised over Bonjour as _alxp._tcp.
 */
class MacLinkService : Service() {

    companion object {
        private const val TAG = "AlxpMacLink"
        private const val CHANNEL = "mac_link"
        private const val PAIR_CHANNEL = "mac_pairing"
        private const val ACTION_PAIR = "org.alxp.parts.MAC_PAIR"
        private const val PAIR_TIMEOUT_S = 60L

        /** The connected Mac, for the display activity. */
        @Volatile var link: MacLink? = null
            private set

        /** Receives the Mac's video while the display activity is open. */
        @Volatile var videoSink: ((ByteArray) -> Unit)? = null
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var notifications: NotificationManager
    private lateinit var clipboard: ClipboardManager
    private var server: ServerSocket? = null
    private var registration: NsdManager.RegistrationListener? = null
    private var pairing: CountDownLatch? = null
    private var pairingAccepted = false
    private var lastRemoteClip: String? = null
    private var displayStart: ByteArray? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = getSystemService(NotificationManager::class.java)
        clipboard = getSystemService(ClipboardManager::class.java)
        notifications.createNotificationChannel(NotificationChannel(CHANNEL,
            getString(R.string.mac_channel), NotificationManager.IMPORTANCE_MIN))
        notifications.createNotificationChannel(NotificationChannel(PAIR_CHANNEL,
            getString(R.string.mac_pair_channel), NotificationManager.IMPORTANCE_HIGH))
        startForeground(3, status(getString(R.string.mac_waiting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        clipboard.addPrimaryClipChangedListener(clipListener)
        Thread(::serve, "AlxpMacLink").start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_PAIR) {
            pairingAccepted = intent.getBooleanExtra("accept", false)
            notifications.cancel(4)
            pairing?.countDown()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        clipboard.removePrimaryClipChangedListener(clipListener)
        registration?.let { getSystemService(NsdManager::class.java).unregisterService(it) }
        try { server?.close() } catch (_: Exception) {}
        link?.close()
        super.onDestroy()
    }

    // --- Connection ----------------------------------------------------------

    private fun serve() {
        val socket = try { ServerSocket(0) } catch (e: Exception) {
            Log.e(TAG, "Cannot listen", e)
            return
        }
        server = socket
        handler.post { advertise(socket.localPort) }
        while (!socket.isClosed) {
            val client = try { socket.accept() } catch (e: Exception) { break }
            client.tcpNoDelay = true
            try {
                session(client)
            } catch (e: Exception) {
                Log.i(TAG, "Mac session ended: ${e.message}")
            } finally {
                link?.close()
                link = null
                handler.post {
                    notifications.notify(3, status(getString(R.string.mac_waiting)))
                    SidecarActivity.closeAll()
                }
            }
        }
    }

    private fun advertise(port: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME) ?: "Pad"
            serviceType = "_alxp._tcp"
            this.port = port
        }
        registration = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.w(TAG, "Bonjour registration failed: $code")
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
        }
        getSystemService(NsdManager::class.java)
            .registerService(info, NsdManager.PROTOCOL_DNS_SD, registration)
    }

    private fun session(client: java.net.Socket) {
        val identity = MacLink.identity(this)
        val (key, macPublic, code) = MacLink.accept(client, identity)
        val prefs = getSharedPreferences("mac_link", MODE_PRIVATE)
        val macId = Base64.encodeToString(macPublic, Base64.NO_WRAP)
        if (prefs.getString("trusted", null) != macId) {
            if (!confirmPairing(code)) return
            prefs.edit().putString("trusted", macId).apply()
        }
        val session = MacLink(client, key)
        link = session
        session.send(MacLink.HELLO, (Settings.Global.getString(contentResolver,
            Settings.Global.DEVICE_NAME) ?: "Pad").toByteArray())
        handler.post { notifications.notify(3, status(getString(R.string.mac_connected))) }
        Log.i(TAG, "Mac connected")
        while (true) {
            val (type, payload) = session.receive()
            when (type) {
                MacLink.CLIP_TEXT -> if (Feature.MAC_CLIPBOARD.isOn(this)) {
                    handler.post { setClip(String(payload)) }
                }
                MacLink.CLIP_PNG -> if (Feature.MAC_CLIPBOARD.isOn(this)) {
                    handler.post { setImageClip(payload) }
                }
                MacLink.DISPLAY_START -> if (Feature.MAC_DISPLAY.isOn(this)) {
                    displayStart = payload
                    val buffer = ByteBuffer.wrap(payload)
                    val width = buffer.short.toInt() and 0xFFFF
                    val height = buffer.short.toInt() and 0xFFFF
                    handler.post { SidecarActivity.open(this, width, height) }
                }
                MacLink.VIDEO -> videoSink?.invoke(payload)
                MacLink.DISPLAY_STOP -> handler.post { SidecarActivity.closeAll() }
            }
        }
    }

    private fun confirmPairing(code: String): Boolean {
        pairingAccepted = false
        pairing = CountDownLatch(1)
        handler.post {
            val builder = Notification.Builder(this, PAIR_CHANNEL)
                .setSmallIcon(R.drawable.ic_feature_mac)
                .setContentTitle(getString(R.string.mac_pair_title))
                .setContentText(getString(R.string.mac_pair_text, code))
                .addAction(pairAction(true, getString(R.string.mac_pair_accept)))
                .addAction(pairAction(false, getString(R.string.mac_pair_reject)))
                .setTimeoutAfter(PAIR_TIMEOUT_S * 1000)
            notifications.notify(4, builder.build())
        }
        pairing!!.await(PAIR_TIMEOUT_S, TimeUnit.SECONDS)
        return pairingAccepted
    }

    private fun pairAction(accept: Boolean, label: String): Notification.Action {
        val intent = Intent(this, MacLinkService::class.java).setAction(ACTION_PAIR)
            .putExtra("accept", accept)
        val pending = PendingIntent.getService(this, if (accept) 1 else 2, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Action.Builder(null, label, pending).build()
    }

    // --- Clipboard -----------------------------------------------------------

    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener {
        if (!Feature.MAC_CLIPBOARD.isOn(this)) return@OnPrimaryClipChangedListener
        val session = link ?: return@OnPrimaryClipChangedListener
        val item = clipboard.primaryClip?.getItemAt(0) ?: return@OnPrimaryClipChangedListener
        val text = item.text?.toString()
        if (text != null) {
            if (text == lastRemoteClip) return@OnPrimaryClipChangedListener
            Thread { runCatching { session.send(MacLink.CLIP_TEXT, text.toByteArray()) } }.start()
            return@OnPrimaryClipChangedListener
        }
        val uri = item.uri ?: return@OnPrimaryClipChangedListener
        Thread {
            runCatching {
                val bitmap = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it) }
                val png = ByteArrayOutputStream()
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, png)
                session.send(MacLink.CLIP_PNG, png.toByteArray())
            }
        }.start()
    }

    private fun setClip(text: String) {
        lastRemoteClip = text
        clipboard.setPrimaryClip(ClipData.newPlainText("Mac", text))
    }

    private fun setImageClip(png: ByteArray) {
        val file = File(cacheDir, "clipboard/mac.png").apply { parentFile?.mkdirs() }
        file.writeBytes(png)
        val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
        lastRemoteClip = null
        clipboard.setPrimaryClip(ClipData.newUri(contentResolver, "Mac", uri))
    }

    private fun status(text: String): Notification = Notification.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_feature_mac)
        .setContentTitle(getString(R.string.mac_title))
        .setContentText(text)
        .setOngoing(true)
        .build()
}
