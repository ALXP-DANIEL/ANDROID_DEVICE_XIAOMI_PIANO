/*
 * Copyright (C) 2026 The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.stylus

import android.content.SharedPreferences
import android.content.Intent
import android.app.AlertDialog
import android.widget.Toast
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.provider.Settings
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.SwitchPreferenceCompat
import com.android.settingslib.widget.SettingsBasePreferenceFragment
import org.lineageos.pianoparts.R
import org.lineageos.pianoparts.AccessoryConnectionPreference

/**
 * Connection, battery and firmware come from [StylusStatusStore]; the button
 * actions keep the stock secure settings consumed by KeyHandler.
 */
class StylusSettingsFragment : SettingsBasePreferenceFragment() {

    private lateinit var connection: AccessoryConnectionPreference
    private lateinit var battery: Preference
    private lateinit var notConnected: Preference
    private lateinit var placementWarning: Preference
    private lateinit var buttonsCategory: PreferenceCategory
    private lateinit var deviceInfoCategory: PreferenceCategory
    private lateinit var firmware: Preference
    private lateinit var firmwareCheck: Preference
    private lateinit var firmwareUpdate: Preference
    private lateinit var penControls: PreferenceCategory

    private val statusListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        refreshStatus()
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = requireContext()
        val resources = context.resources
        preferenceScreen = preferenceManager.createPreferenceScreen(context)

        preferenceScreen.addPreference(Preference(context).apply {
            layoutResource = R.layout.accessory_pen_hero_preference
            order = 0
            setIcon(R.drawable.accessory_stylus_show_icon)
            setTitle(R.string.pen_category_title)
            setSummary(R.string.stylus_attach_guide)
            isSelectable = false
            isIconSpaceReserved = false
        })
        preferenceScreen.addPreference(Preference(context).apply {
            order = 30
            setTitle(R.string.stylus_palette_title)
            setSummary(R.string.stylus_palette_summary)
            setOnPreferenceClickListener { showPaletteEditor(); true }
        })
        notConnected = Preference(context).apply {
            order = 10
            summary = getString(R.string.stylus_not_connected)
            isSelectable = false
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(notConnected)
        placementWarning = Preference(context).apply {
            order = 11
            setTitle(R.string.stylus_attachment_warning_title)
            isSelectable = false
            isIconSpaceReserved = false
        }
        preferenceScreen.addPreference(placementWarning)

        buttonsCategory = PreferenceCategory(context).apply {
            order = 40
            title = getString(R.string.stylus_button_actions_title)
        }
        preferenceScreen.addPreference(buttonsCategory)

        penControls = PreferenceCategory(context).apply {
            order = 50
            title = getString(R.string.stylus_pen_controls)
        }
        preferenceScreen.addPreference(penControls)
        penControls.addPreference(createSystemSwitch(PenControls.DOUBLE_TAP,
            R.string.stylus_double_tap_enabled, 1))
        penControls.addPreference(createSystemSwitch(PenControls.PINCH,
            R.string.stylus_squeeze_enabled, 5))
        penControls.addPreference(createSystemSwitch(PenControls.SLIDE,
            R.string.stylus_slide_enabled, 1))
        penControls.addPreference(createSystemSwitch(PenControls.HOVER,
            R.string.stylus_hover_enabled, 1))
        penControls.addPreference(createSystemChoice(PenControls.PRESSURE,
            R.string.stylus_squeeze_pressure, R.array.stylus_pressure_levels, 0..4, 2))
        penControls.addPreference(createSystemChoice(PenControls.PINCH_FEEDBACK,
            R.string.stylus_squeeze_feedback, R.array.stylus_feedback_levels, 0..5, 3))
        penControls.addPreference(createSystemChoice(PenControls.WRITING_TYPE,
            R.string.stylus_writing_tool, R.array.stylus_writing_tools, 1..4, 1))
        penControls.addPreference(createSystemChoice(PenControls.WRITING_FEEDBACK,
            R.string.stylus_writing_feedback, R.array.stylus_feedback_levels, 0..5, 0))
        listOf(
            StylusSettingsContract.DOUBLE_TAP_ACTION to R.string.stylus_double_tap_action,
            StylusSettingsContract.SLIDE_UP_ACTION to R.string.stylus_slide_up_action,
            StylusSettingsContract.SLIDE_DOWN_ACTION to R.string.stylus_slide_down_action,
        ).forEach { (key, title) ->
            penControls.addPreference(createActionPreference(key, getString(title),
                resources.getTextArray(R.array.stylus_button_actions),
                resources.getTextArray(R.array.stylus_button_values), StylusSettingsContract.Action.NONE))
        }

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
                defaultPinchAction(),
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

        deviceInfoCategory = PreferenceCategory(context).apply {
            order = 20
            title = getString(R.string.stylus_device_info_title)
        }
        preferenceScreen.addPreference(deviceInfoCategory)

        connection = AccessoryConnectionPreference(context).apply {
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
        battery.isVisible = false

        firmware = Preference(context).apply {
            key = KEY_FIRMWARE
            title = getString(R.string.stylus_firmware_title)
            intent = Intent().setClassName(context.packageName, "${context.packageName}.StylusFirmwareActivity")
        }
        deviceInfoCategory.addPreference(firmware)
        firmwareCheck = Preference(context).apply {
            setTitle(R.string.stylus_firmware_check)
            setOnPreferenceClickListener {
                context.sendBroadcast(Intent(StylusController.ACTION_CHECK_FIRMWARE).setPackage(context.packageName))
                true
            }
        }
        deviceInfoCategory.addPreference(firmwareCheck)
        firmwareUpdate = Preference(context).apply {
            setTitle(R.string.stylus_firmware_update)
            setOnPreferenceClickListener {
                val state = StylusStatusStore.snapshot(context)
                if (state.updateState == "available") {
                    AlertDialog.Builder(context).setTitle(R.string.stylus_firmware_update)
                        .setMessage(getString(R.string.stylus_firmware_confirm, state.updateVersion))
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(R.string.stylus_firmware_update) { _, _ ->
                            context.sendBroadcast(Intent(StylusController.ACTION_UPDATE_FIRMWARE).setPackage(context.packageName))
                        }.show()
                }
                true
            }
        }
        deviceInfoCategory.addPreference(firmwareUpdate)
        // The stock-style detail page owns check/update actions.
        firmwareCheck.isVisible = false
        firmwareUpdate.isVisible = false
    }

    override fun onResume() {
        super.onResume()
        StylusStatusStore.preferences(requireContext())
            .registerOnSharedPreferenceChangeListener(statusListener)
        refreshStatus()
        requireContext().sendBroadcast(Intent(StylusController.ACTION_CHECK_FIRMWARE)
            .setPackage(requireContext().packageName))
        listOf(StylusSettingsContract.DOUBLE_TAP_ACTION, StylusSettingsContract.SLIDE_UP_ACTION,
            StylusSettingsContract.SLIDE_DOWN_ACTION).forEach {
            refreshActionPreference(it, StylusSettingsContract.Action.NONE)
        }
        refreshActionPreference(
            StylusSettingsContract.PINCH_ACTION,
            defaultPinchAction(),
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

    private fun createSystemSwitch(key: String, title: Int, enabledValue: Int): SwitchPreferenceCompat =
        SwitchPreferenceCompat(requireContext()).apply {
            this.key = key
            setTitle(title)
            isPersistent = false
            isChecked = PenControls.value(requireContext(), key, enabledValue) != 0
            setOnPreferenceChangeListener { _, value ->
                Settings.System.putInt(requireContext().contentResolver, key,
                    if (value as Boolean) enabledValue else 0)
            }
        }

    private fun createSystemChoice(
        key: String, title: Int, entries: Int, range: IntRange, defaultValue: Int,
    ): ListPreference = ListPreference(requireContext()).apply {
        this.key = key
        setTitle(title)
        setEntries(entries)
        entryValues = range.map(Int::toString).toTypedArray()
        isPersistent = false
        value = PenControls.value(requireContext(), key, defaultValue)
            .coerceIn(range.first, range.last).toString()
        updateSummary(this, value)
        if (key == StylusSettingsContract.PRIMARY_BUTTON_ACTION || key == StylusSettingsContract.SECONDARY_BUTTON_ACTION) {
            layoutResource = R.layout.accessory_action_preference
            setIcon(if (key == StylusSettingsContract.PRIMARY_BUTTON_ACTION)
                R.drawable.accessory_stylus_shortcut_actions_icon else R.drawable.accessory_stylus_screen_shot)
            isIconSpaceReserved = false
        }
        setOnPreferenceChangeListener { preference, newValue ->
            val number = (newValue as? String)?.toIntOrNull()
            if (number == null || number !in range ||
                !Settings.System.putInt(requireContext().contentResolver, key, number)
            ) return@setOnPreferenceChangeListener false
            // The stock writing-feedback command contains both tool and strength.
            if (key == PenControls.WRITING_FEEDBACK &&
                Settings.System.getString(requireContext().contentResolver, PenControls.WRITING_TYPE) == null
            ) Settings.System.putInt(requireContext().contentResolver, PenControls.WRITING_TYPE, 1)
            updateSummary(preference as ListPreference, number.toString())
            true
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
        refreshActionPreference(StylusSettingsContract.PINCH_ACTION, defaultPinchAction())
        // Unlike pen state persisted across process/device restarts, the
        // Bluetooth adapter is live. Never tell the user Bluetooth is off
        // merely because the pen controller has an old state snapshot.
        val bluetoothEnabled = requireContext()
            .getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled == true
        val connectionState = when {
            !bluetoothEnabled -> StylusConnectionState.BLUETOOTH_OFF
            status.state == StylusConnectionState.BLUETOOTH_OFF -> StylusConnectionState.DISCONNECTED
            else -> status.state
        }
        val connected = connectionState == StylusConnectionState.CONNECTED
        placementWarning.isVisible = status.placementError != 0
        placementWarning.summary = getString(R.string.stylus_attachment_warning,
            status.placementError)
        deviceInfoCategory.isVisible = connected
        notConnected.isVisible = !connected
        buttonsCategory.isEnabled = connected
        penControls.isVisible = PenControls.isTouchFilm(status)
        penControls.isEnabled = status.penInitialized
        connection.summary = getString(
            when (connectionState) {
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
        connection.setBattery(status.battery, battery.summary, connected && !status.penInitialized)
        firmware.summary = status.firmwareRevision ?: getString(R.string.stylus_unavailable)
        val busy = status.updateState in setOf("updating", "verifying")
        firmwareCheck.isEnabled = connected && !busy
        firmwareUpdate.isEnabled = connected && status.penInitialized &&
            status.updateState == "available" && (status.battery ?: -1) >= 20
        firmwareUpdate.summary = when (status.updateState) {
            "available" -> getString(R.string.stylus_firmware_available, status.updateVersion)
            "updating" -> getString(R.string.stylus_firmware_progress, status.updateProgress)
            "verifying" -> getString(R.string.stylus_firmware_verifying)
            "complete" -> getString(R.string.stylus_firmware_complete)
            "unsupported" -> getString(R.string.stylus_firmware_unsupported)
            "invalid", "failed" -> getString(R.string.stylus_firmware_failed)
            "not_ready" -> getString(R.string.stylus_firmware_not_ready)
            else -> getString(R.string.stylus_firmware_unavailable)
        }
        if (connected && !status.penInitialized && PenControls.isTouchFilm(status)) {
            connection.summary = getString(R.string.stylus_initializing)
        }
        if (status.penError != 0) {
            connection.summary = getString(when (status.penError) {
                1 -> R.string.stylus_battery_error
                2 -> R.string.stylus_temperature_error
                3 -> R.string.stylus_current_error
                else -> R.string.stylus_setting_rejected
            })
        }
    }

    private fun getSecure(key: String): String? =
        Settings.Secure.getString(requireContext().contentResolver, key)

    private fun showPaletteEditor() {
        val context = requireContext()
        val choices = PenPalette.candidates(context)
        val available = choices.map { it.id }.toSet()
        val selected = PenPalette.selected(context).filter(available::contains).toMutableList()
        val checked = BooleanArray(choices.size) { choices[it].id in selected }
        val dialog = AlertDialog.Builder(context)
            .setTitle(R.string.stylus_palette_title)
            .setMultiChoiceItems(choices.map { it.label }.toTypedArray(), checked) { dialog, index, enabled ->
                val id = choices[index].id
                if (enabled && selected.size >= PenPalette.MAX_ENTRIES) {
                    (dialog as AlertDialog).listView.setItemChecked(index, false)
                    Toast.makeText(context, R.string.stylus_palette_limit, Toast.LENGTH_SHORT).show()
                } else if (enabled) selected.add(id) else selected.remove(id)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.stylus_palette_reset) { _, _ -> PenPalette.reset(context) }
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (selected.isEmpty()) Toast.makeText(context, R.string.stylus_palette_empty, Toast.LENGTH_SHORT).show()
                else if (PenPalette.save(context, selected)) dialog.dismiss()
            }
        }
        dialog.show()
    }

    private fun defaultPinchAction(): String =
        if (PenControls.isTouchFilm(StylusStatusStore.snapshot(requireContext())))
            StylusSettingsContract.DEFAULT_PINCH_ACTION else StylusSettingsContract.Action.RECENTS

    private fun putSecure(key: String, value: String): Boolean =
        Settings.Secure.putString(requireContext().contentResolver, key, value)

    private companion object {
        const val KEY_CONNECTION = "stylus_connection"
        const val KEY_BATTERY = "stylus_battery"
        const val KEY_FIRMWARE = "stylus_firmware"
    }
}
