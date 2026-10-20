/* SPDX-License-Identifier: GPL-3.0-or-later */
package org.alxp.parts

import android.Manifest.permission.POST_NOTIFICATIONS
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity

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

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.alxp_settings, rootKey)
        val context = requireContext()
        NativeIntegration.init(context)

        for (feature in Feature.entries) {
            findPreference<SwitchPreferenceCompat>(feature.key)!!.apply {
                isChecked = feature.isOn(context)
                onPreferenceChangeListener = Preference.OnPreferenceChangeListener { _, value ->
                    if (value as Boolean && context.checkSelfPermission(POST_NOTIFICATIONS) !=
                        PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(POST_NOTIFICATIONS), 0)
                    }
                    feature.setOn(context, value)
                    true
                }
            }
        }

        findPreference<SwitchPreferenceCompat>(Feature.AIRPODS_SWITCHING.key)!!.apply {
            if (!NativeIntegration.isAvailable()) {
                isEnabled = false
                setSummary(R.string.airpods_unavailable)
            }
        }
        val name = context.getSystemService(BluetoothManager::class.java).adapter.name
        findPreference<SwitchPreferenceCompat>(Feature.IPHONE_NOTIFICATIONS.key)!!.summary =
            getString(R.string.iphone_summary, name)
    }
}
