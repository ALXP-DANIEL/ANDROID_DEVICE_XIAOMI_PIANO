/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import org.alxp.parts.mac.MacLinkService
import org.alxp.parts.services.AirPodsService
import org.alxp.parts.services.AncsService

/**
 * Each ALXP feature is a switch backed by a service. The service component is
 * enabled while any feature that uses it is on; the AirPods Bluetooth patch
 * checks that component directly.
 */
enum class Feature(val key: String, private val service: Class<out Service>) {
    AIRPODS_SWITCHING("airpods_switching", AirPodsService::class.java),
    IPHONE_NOTIFICATIONS("iphone_notifications", AncsService::class.java),
    MAC_CLIPBOARD("mac_clipboard", MacLinkService::class.java),
    MAC_DISPLAY("mac_display", MacLinkService::class.java);

    private fun prefs(context: Context) =
        context.getSharedPreferences("features", Context.MODE_PRIVATE)

    private fun component(context: Context) = ComponentName(context, service)

    private fun serviceEnabled(context: Context) = context.packageManager
        .getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun isOn(context: Context) = serviceEnabled(context) &&
        prefs(context).getBoolean(key, sharedBy().size == 1)

    /** Features that share this one's service. */
    private fun sharedBy() = entries.filter { it.service == service }

    fun start(context: Context) {
        context.startForegroundService(Intent(context, service))
    }

    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext
        prefs(app).edit().putBoolean(key, on).apply()
        val needed = sharedBy().any { prefs(app).getBoolean(it.key, false) }
        if (!needed) app.stopService(Intent(app, service))
        app.packageManager.setComponentEnabledSetting(component(app),
            if (needed) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        if (this == AIRPODS_SWITCHING) {
            // Bluetooth reads the Apple identity only when it starts.
            NativeIntegration.init(app)
            NativeIntegration.setIdentity(on)
            restartBluetooth(app)
        }
        if (needed) start(app)
    }

    @Suppress("DEPRECATION")
    private fun restartBluetooth(context: Context) {
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) return
        adapter.disable()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (adapter.state == BluetoothAdapter.STATE_OFF) adapter.enable()
                else handler.postDelayed(this, 300)
            }
        }, 300)
    }
}
