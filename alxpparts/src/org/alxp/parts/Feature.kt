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
import org.alxp.parts.services.AirPodsService
import org.alxp.parts.services.AncsService

/** Each ALXP feature is one service, on while its component is enabled. */
enum class Feature(val key: String, private val service: Class<out Service>) {
    AIRPODS_SWITCHING("airpods_switching", AirPodsService::class.java),
    IPHONE_NOTIFICATIONS("iphone_notifications", AncsService::class.java);

    private fun component(context: Context) = ComponentName(context, service)

    fun isOn(context: Context) = context.packageManager.getComponentEnabledSetting(
        component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun start(context: Context) {
        context.startForegroundService(Intent(context, service))
    }

    fun setOn(context: Context, on: Boolean) {
        val app = context.applicationContext
        if (!on) app.stopService(Intent(app, service))
        app.packageManager.setComponentEnabledSetting(component(app),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
        if (this == AIRPODS_SWITCHING) {
            // Bluetooth reads the Apple identity only when it starts.
            NativeIntegration.init(app)
            NativeIntegration.setIdentity(on)
            restartBluetooth(app)
        }
        if (on) start(app)
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
