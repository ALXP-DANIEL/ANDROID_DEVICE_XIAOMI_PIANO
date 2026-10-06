/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.pianoparts.R

/**
 * Connection, battery and firmware come from [StylusStatusStore]; the button
 * actions keep the stock secure settings consumed by KeyHandler.
 */
class StylusSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var connection: Preference
    private lateinit var battery: Preference
    private lateinit var notConnected: Preference
    private lateinit var buttonsCategory: PreferenceCategory
    private lateinit var firmware: Preference

    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshStatus()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val resources = context.resources
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        notConnected = Preference(context).apply {
            summary = getString(R.string.stylus_not_connected)
            isSelectable = false
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(notConnected)

        buttonsCategory = PreferenceCategory(context).apply {
            title = getString(R.string.stylus_button_actions_title)
        }
        preferenceScreen.addPreference(buttonsCategory)

        buttonsCategory.addPreference(
            createSecureSwitch(
                Settings.Secure.STYLUS_HANDWRITING_ENABLED,
                getString(R.string.stylus_handwriting_title),
                getString(R.string.stylus_handwriting_summary),
            ),
        )
        buttonsCategory.addPreference(
            createSecureSwitch(
                Settings.Secure.STYLUS_BUTTONS_ENABLED,
                getString(R.string.stylus_buttons_title),
                getString(R.string.stylus_buttons_summary),
            ),
        )

        buttonsCategory.addPreference(
            createActionPreference(
                StylusSettingsContract.PRIMARY_BUTTON_ACTION,
                getString(R.string.stylus_primary_button_title),
                resources.getTextArray(R.array.stylus_button_actions),
                resources.getTextArray(R.array.stylus_button_values),
                StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
            ),
        )
        buttonsCategory.addPreference(
            createActionPreference(
                StylusSettingsContract.SECONDARY_BUTTON_ACTION,
                getString(R.string.stylus_secondary_button_title),
                resources.getTextArray(R.array.stylus_button_actions),
                resources.getTextArray(R.array.stylus_button_values),
                StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
            ),
        )

        buttonsCategory.addPreference(
            createActionPreference(
                StylusSettingsContract.PINCH_ACTION,
                getString(R.string.stylus_pinch_title),
                resources.getTextArray(R.array.stylus_button_actions),
                resources.getTextArray(R.array.stylus_button_values),
                StylusSettingsContract.DEFAULT_PINCH_ACTION,
            ),
        )
        buttonsCategory.addPreference(
            SwitchPreferenceCompat(context).apply {
                key = StylusSettingsContract.SCREEN_OFF_QUICK_NOTE
                title = getString(R.string.stylus_screen_off_quick_note_title)
                summary = getString(R.string.stylus_screen_off_quick_note_summary)
                isPersistent = false
                isChecked = Settings.Secure.getInt(
                    context.contentResolver,
                    StylusSettingsContract.SCREEN_OFF_QUICK_NOTE,
                    if (StylusSettingsContract.DEFAULT_SCREEN_OFF_QUICK_NOTE) 1 else 0,
                ) != 0
                setOnPreferenceChangeListener { _, newValue ->
                    Settings.Secure.putInt(
                        context.contentResolver,
                        StylusSettingsContract.SCREEN_OFF_QUICK_NOTE,
                        if (newValue as Boolean) 1 else 0,
                    )
                }
            },
        )

        val deviceInfoCategory = PreferenceCategory(context).apply {
            title = getString(R.string.stylus_device_info_title)
        }
        preferenceScreen.addPreference(deviceInfoCategory)

        connection = Preference(context).apply {
            key = KEY_CONNECTION
            title = getString(R.string.stylus_connection_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(connection)

        battery = Preference(context).apply {
            key = KEY_BATTERY
            title = getString(R.string.stylus_battery_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(battery)

        firmware = Preference(context).apply {
            key = KEY_FIRMWARE
            title = getString(R.string.stylus_firmware_title)
            isSelectable = false
        }
        deviceInfoCategory.addPreference(firmware)
    }

    override fun onResume() {
        super.onResume()
        StylusStatusStore.preferences(requireContext())
            .registerOnSharedPreferenceChangeListener(statusListener)
        refreshStatus()
        refreshActionPreference(
            StylusSettingsContract.PINCH_ACTION,
            StylusSettingsContract.DEFAULT_PINCH_ACTION,
        )
        refreshActionPreference(
            StylusSettingsContract.PRIMARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_PRIMARY_BUTTON_ACTION,
        )
        refreshActionPreference(
            StylusSettingsContract.SECONDARY_BUTTON_ACTION,
            StylusSettingsContract.DEFAULT_SECONDARY_BUTTON_ACTION,
        )
    }

    override fun onPause() {
        StylusStatusStore.preferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(statusListener)
        super.onPause()
    }

    private fun createSecureSwitch(
        key: String,
        title: String,
        summary: String,
    ): SwitchPreferenceCompat = SwitchPreferenceCompat(requireContext()).apply {
        this.key = key
        this.title = title
        this.summary = summary
        isPersistent = false
        isChecked = Settings.Secure.getInt(requireContext().contentResolver, key, 1) != 0
        setOnPreferenceChangeListener { _, newValue ->
            Settings.Secure.putInt(
                requireContext().contentResolver,
                key,
                if (newValue as Boolean) 1 else 0,
            )
        }
    }

    private fun createActionPreference(
        key: String,
        title: String,
        entries: Array<CharSequence>,
        entryValues: Array<CharSequence>,
        defaultValue: String,
    ): ListPreference = ListPreference(requireContext()).apply {
        this.key = key
        this.title = title
        dialogTitle = title
        this.entries = entries
        this.entryValues = entryValues
        isPersistent = false
        value = StylusSettingsContract.sanitizeAction(getSecure(key), defaultValue)
        updateSummary(this, value)
        setOnPreferenceChangeListener { preference, newValue ->
            val action = newValue as? String
                ?: return@setOnPreferenceChangeListener false
            if (action !in StylusSettingsContract.Action.SUPPORTED) {
                return@setOnPreferenceChangeListener false
            }
            if (!putSecure(key, action)) {
                return@setOnPreferenceChangeListener false
            }
            updateSummary(preference as ListPreference, action)
            true
        }
    }

    private fun refreshActionPreference(key: String, defaultValue: String) {
        val preference = findPreference<ListPreference>(key) ?: return
        val action = StylusSettingsContract.sanitizeAction(
            getSecure(key),
            defaultValue,
        )
        preference.value = action
        updateSummary(preference, action)
    }

    private fun updateSummary(preference: ListPreference, action: String) {
        val index = preference.findIndexOfValue(action)
        preference.summary = if (index >= 0) preference.entries[index] else null
    }

    private fun refreshStatus() {
        val status = StylusStatusStore.snapshot(requireContext())
        val connected = status.state == StylusConnectionState.CONNECTED
        notConnected.isVisible = !connected
        buttonsCategory.isEnabled = connected
        connection.summary = getString(
            when (status.state) {
                StylusConnectionState.CONNECTED -> R.string.stylus_connected
                StylusConnectionState.CONNECTING -> R.string.stylus_connecting
                StylusConnectionState.PAIRING -> R.string.stylus_pairing
                StylusConnectionState.BLUETOOTH_OFF -> R.string.stylus_bluetooth_off
                StylusConnectionState.CONNECTION_FAILED -> R.string.stylus_connection_failed
                StylusConnectionState.DISCONNECTED -> R.string.stylus_disconnected
            },
        )
        battery.summary = when {
            status.battery == null -> getString(R.string.stylus_unavailable)
            status.docked -> getString(R.string.stylus_charging_battery, status.battery)
            else -> getString(R.string.stylus_battery_percent, status.battery)
        }
        firmware.summary = status.firmwareRevision ?: getString(R.string.stylus_unavailable)
    }

    private fun getSecure(key: String): String? =
        Settings.Secure.getString(requireContext().contentResolver, key)

    private fun putSecure(key: String, value: String): Boolean =
        Settings.Secure.putString(requireContext().contentResolver, key, value)

    private companion object {
        const val KEY_CONNECTION = "stylus_connection"
        const val KEY_BATTERY = "stylus_battery"
        const val KEY_FIRMWARE = "stylus_firmware"
    }
}
