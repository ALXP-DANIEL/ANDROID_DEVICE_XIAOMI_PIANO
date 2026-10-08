/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.Intent;
import android.os.Bundle;

import androidx.preference.PreferenceCategory;

import com.android.settingslib.widget.SelectorWithWidgetPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/**
 * The HyperOS battery protection page: normal, always protect or smart.
 * Smart protection gets a settings button for its schedule.
 */
public class BatteryProtectionFragment extends SettingsBasePreferenceFragment
        implements SelectorWithWidgetPreference.OnClickListener {

    private static final String KEY_MODES = "battery_protection_modes";
    private static final String KEY_PREFIX = "protect_";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.battery_protection_settings, rootKey);
        PreferenceCategory modes = findPreference(KEY_MODES);
        for (int i = 0; i < modes.getPreferenceCount(); i++) {
            ((SelectorWithWidgetPreference) modes.getPreference(i)).setOnClickListener(this);
        }
        SelectorWithWidgetPreference smart =
                findPreference(KEY_PREFIX + BatteryProtection.MODE_SMART);
        smart.setExtraWidgetOnClickListener(v -> startActivity(
                new Intent("org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS")));
    }

    @Override
    public void onResume() {
        super.onResume();
        update(BatteryProtection.getMode(requireContext()));
    }

    @Override
    public void onRadioButtonClicked(SelectorWithWidgetPreference preference) {
        int mode = Integer.parseInt(preference.getKey().substring(KEY_PREFIX.length()));
        BatteryProtection.setMode(requireContext(), mode);
        update(mode);
    }

    private void update(int mode) {
        PreferenceCategory modes = findPreference(KEY_MODES);
        for (int i = 0; i < modes.getPreferenceCount(); i++) {
            SelectorWithWidgetPreference choice =
                    (SelectorWithWidgetPreference) modes.getPreference(i);
            choice.setChecked(choice.getKey().equals(KEY_PREFIX + mode));
        }
    }
}
