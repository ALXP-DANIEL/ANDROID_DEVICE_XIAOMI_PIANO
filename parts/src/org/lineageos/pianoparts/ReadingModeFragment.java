/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import static org.lineageos.pianoparts.ReadingModeController.COLOR_TYPE;
import static org.lineageos.pianoparts.ReadingModeController.LEVEL;
import static org.lineageos.pianoparts.ReadingModeController.LEVEL_DEFAULT;
import static org.lineageos.pianoparts.ReadingModeController.LEVEL_MAX;
import static org.lineageos.pianoparts.ReadingModeController.LEVEL_MIN;
import static org.lineageos.pianoparts.ReadingModeController.TEXTURE;
import static org.lineageos.pianoparts.ReadingModeController.TEXTURE_DEFAULT;
import static org.lineageos.pianoparts.ReadingModeController.TEXTURE_MAX;
import static org.lineageos.pianoparts.ReadingModeController.TEXTURE_MIN;

import android.os.Bundle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.SeekBarPreference;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/**
 * The HyperOS reading mode page: the switch, then the classic mode warmth,
 * paper texture and paper colors, with a reset.
 */
public class ReadingModeFragment extends SettingsBasePreferenceFragment {

    private ReadingModeController mController;
    private MainSwitchPreference mSwitch;
    private SeekBarPreference mLevel;
    private SeekBarPreference mTexture;
    private ListPreference mColors;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.reading_mode_settings, rootKey);
        mController = ((PianoPartsApp) requireActivity().getApplication())
                .getReadingModeController();

        mSwitch = findPreference("reading_enabled");
        mSwitch.addOnSwitchChangeListener((view, checked) -> {
            mController.setEnabled(checked);
            updateEnabled(checked);
        });

        mLevel = findPreference("reading_level");
        mLevel.setMin(LEVEL_MIN);
        mLevel.setMax(LEVEL_MAX);
        mLevel.setOnPreferenceChangeListener((p, value) -> putInt(LEVEL, (Integer) value));

        mTexture = findPreference("reading_texture");
        mTexture.setMin(TEXTURE_MIN);
        mTexture.setMax(TEXTURE_MAX);
        mTexture.setOnPreferenceChangeListener((p, value) -> putInt(TEXTURE, (Integer) value));

        mColors = findPreference("reading_colors");
        mColors.setOnPreferenceChangeListener((p, value) ->
                putInt(COLOR_TYPE, Integer.parseInt((String) value)));

        findPreference("reading_reset").setOnPreferenceClickListener(p -> {
            putInt(LEVEL, LEVEL_DEFAULT);
            putInt(TEXTURE, TEXTURE_DEFAULT);
            putInt(COLOR_TYPE, 0);
            update();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    private void update() {
        boolean enabled = mController.isEnabled();
        mSwitch.setChecked(enabled);
        mLevel.setValue(getInt(LEVEL, LEVEL_DEFAULT));
        mTexture.setValue(getInt(TEXTURE, TEXTURE_DEFAULT));
        mColors.setValue(String.valueOf(getInt(COLOR_TYPE, 0)));
        updateEnabled(enabled);
    }

    // Like stock, the effect can only be adjusted while reading mode is on.
    private void updateEnabled(boolean enabled) {
        findPreference("reading_effect").setEnabled(enabled);
    }

    private int getInt(String key, int def) {
        return Settings.System.getInt(requireContext().getContentResolver(), key, def);
    }

    private boolean putInt(String key, int value) {
        return Settings.System.putInt(requireContext().getContentResolver(), key, value);
    }
}
