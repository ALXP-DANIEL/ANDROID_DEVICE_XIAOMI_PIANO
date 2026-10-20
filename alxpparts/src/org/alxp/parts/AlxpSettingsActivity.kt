/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceFragmentCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import com.android.settingslib.widget.MainSwitchPreference
import org.alxp.parts.services.AirPodsService

class AlxpSettingsActivity : CollapsingToolbarBaseActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                    AlxpSettingsFragment())
                .commit()
        }
    }
}

class AlxpSettingsFragment : PreferenceFragmentCompat() {

    private val components by lazy {
        listOf(AirPodsService::class.java, BootReceiver::class.java)
            .map { ComponentName(requireContext(), it) }
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.alxp_settings, rootKey)
        NativeIntegration.init(requireContext())

        findPreference<MainSwitchPreference>("enabled")!!.apply {
            isChecked = isServiceEnabled()
            isEnabled = NativeIntegration.isAvailable()
            addOnSwitchChangeListener { _, checked -> setEnabled(checked) }
        }
    }

    private fun isServiceEnabled() = requireContext().packageManager
        .getComponentEnabledSetting(components[0]) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    private fun setEnabled(enabled: Boolean) {
        val context = requireContext().applicationContext
        val service = Intent(context, AirPodsService::class.java)
        if (!enabled) context.stopService(service)
        for (component in components) {
            context.packageManager.setComponentEnabledSetting(component,
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP)
        }
        NativeIntegration.setIdentity(enabled)
        restartBluetooth()
        if (enabled) context.startForegroundService(service)
    }

    /** Bluetooth only reads the Apple identity when it starts. */
    @Suppress("DEPRECATION")
    private fun restartBluetooth() {
        val adapter = requireContext().getSystemService(BluetoothManager::class.java).adapter
        if (!adapter.isEnabled) return
        adapter.disable()
        val handler = Handler(Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (adapter.state == android.bluetooth.BluetoothAdapter.STATE_OFF) {
                    adapter.enable()
                } else {
                    handler.postDelayed(this, 300)
                }
            }
        }, 300)
    }
}
