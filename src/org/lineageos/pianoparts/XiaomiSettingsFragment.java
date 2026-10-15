/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.os.Bundle;

import androidx.preference.Preference;
import androidx.preference.TwoStatePreference;

import com.android.settingslib.PrimarySwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public class XiaomiSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private static final String KEY_READING_MODE = "reading_mode";
    private static final String KEY_SUNLIGHT_MODE = "sunlight_mode";
    private static final String KEY_THERMAL_GAME = "thermal_game";
    private static final String KEY_BYPASS_CHARGING = "bypass_charging";

    private PianoPartsApp mApp;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.xiaomi_settings, rootKey);
        mApp = (PianoPartsApp) requireActivity().getApplication();
        for (String key : new String[] { KEY_READING_MODE, KEY_SUNLIGHT_MODE,
                KEY_THERMAL_GAME, KEY_BYPASS_CHARGING }) {
            findPreference(key).setOnPreferenceChangeListener(this);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // The Quick Settings tiles change the same state while this page is closed.
        ((PrimarySwitchPreference) findPreference(KEY_READING_MODE)).setChecked(
                mApp.getReadingModeController().isEnabled());
        setChecked(KEY_SUNLIGHT_MODE, mApp.isSunlightMode());
        setChecked(KEY_THERMAL_GAME, ThermalProfileTileService.isGameProfile());
        setChecked(KEY_BYPASS_CHARGING, BypassChargingTileService.isEnabled());
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        boolean enabled = (Boolean) newValue;
        switch (preference.getKey()) {
            case KEY_READING_MODE -> mApp.getReadingModeController().setEnabled(enabled);
            case KEY_SUNLIGHT_MODE -> mApp.setSunlightMode(enabled);
            case KEY_THERMAL_GAME -> ThermalProfileTileService.setGameProfile(enabled);
            case KEY_BYPASS_CHARGING -> BypassChargingTileService.setEnabled(enabled);
        }
        return true;
    }

    private void setChecked(String key, boolean checked) {
        ((TwoStatePreference) findPreference(key)).setChecked(checked);
    }
}
