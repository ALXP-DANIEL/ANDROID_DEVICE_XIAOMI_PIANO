/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.app.WallpaperManager;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.widget.ImageView;
import android.widget.RadioGroup;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.LayoutPreference;
import com.android.settingslib.widget.SelectorWithWidgetPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/**
 * A copy of the HyperOS ScreenEffectFragment: a preview, the color modes, the
 * color temperature and adaptive colors (true tone).
 */
public class ColorSchemeFragment extends SettingsBasePreferenceFragment
        implements SelectorWithWidgetPreference.OnClickListener {

    private static final String KEY_PREVIEW = "color_preview";
    private static final String KEY_MODES = "screen_optimize";
    private static final String KEY_TRUE_TONE = "screen_truetone_pref";
    private static final String KEY_TEMPERATURE = "color_temperature";

    // Stock warm and cool colors, where the presets put the picker dot.
    private static final int WARM_RGB = 0xffd4b8;
    private static final int COOL_RGB = 0xb8d4ff;

    private ColorPickerView mPicker;
    private RadioGroup mTabs;
    private boolean mUpdatingTabs;

    private PianoPartsApp mApp;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.color_scheme_settings, rootKey);
        mApp = (PianoPartsApp) requireActivity().getApplication();

        LayoutPreference preview = findPreference(KEY_PREVIEW);
        ImageView image = preview.findViewById(R.id.color_preview_image);
        Drawable wallpaper = loadWallpaper();
        if (wallpaper != null) {
            image.setImageDrawable(wallpaper);
        }

        addChoices(KEY_MODES, R.array.color_mode_titles, R.array.color_mode_summaries,
                PianoPartsApp.COLOR_MODES);
        LayoutPreference temperature = findPreference(KEY_TEMPERATURE);
        mPicker = temperature.findViewById(R.id.color_picker);
        mTabs = temperature.findViewById(R.id.color_tabs);
        mPicker.setOnColorPickedListener(rgb -> {
            mApp.setColorScheme(mApp.getColorMode(), rgb);
            checkTab(R.id.color_custom);
        });
        mTabs.setOnCheckedChangeListener((group, id) -> {
            if (mUpdatingTabs) {
                return;
            }
            // Like stock, the Custom tab only selects; dragging the dot sets it.
            int level = id == R.id.color_warm ? 1 : id == R.id.color_cool ? 3
                    : id == R.id.color_standard ? 2 : 0;
            if (level != 0) {
                mApp.setColorScheme(mApp.getColorMode(), level);
                mPicker.setColor(levelToRgb(level));
            }
        });

        SwitchPreferenceCompat trueTone = findPreference(KEY_TRUE_TONE);
        trueTone.setOnPreferenceChangeListener((p, value) -> {
            mApp.setTrueTone((Boolean) value);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        updateChecked(KEY_MODES, mApp.getColorMode());
        updateLevels(mApp.getColorLevel());
        ((SwitchPreferenceCompat) findPreference(KEY_TRUE_TONE)).setChecked(mApp.isTrueTone());
    }

    @Override
    public void onRadioButtonClicked(SelectorWithWidgetPreference preference) {
        String category = preference.getParent().getKey();
        int value = Integer.parseInt(preference.getKey().substring(category.length() + 1));
        mApp.setColorScheme(value, mApp.getColorLevel());
        updateChecked(category, value);
    }

    private void updateLevels(int level) {
        mPicker.setColor(levelToRgb(level));
        checkTab(switch (level) {
            case 1 -> R.id.color_warm;
            case 2 -> R.id.color_standard;
            case 3 -> R.id.color_cool;
            default -> R.id.color_custom;
        });
    }

    private void checkTab(int id) {
        mUpdatingTabs = true;
        mTabs.check(id);
        mUpdatingTabs = false;
    }

    private static int levelToRgb(int level) {
        return switch (level) {
            case 1 -> WARM_RGB;
            case 2 -> 0xffffff;
            case 3 -> COOL_RGB;
            default -> level;
        };
    }

    private void addChoices(String categoryKey, int titles, int summaries, int[] values) {
        PreferenceCategory category = findPreference(categoryKey);
        CharSequence[] titleArray = getResources().getTextArray(titles);
        CharSequence[] summaryArray = summaries != 0
                ? getResources().getTextArray(summaries) : null;
        for (int i = 0; i < values.length; i++) {
            SelectorWithWidgetPreference choice =
                    new SelectorWithWidgetPreference(requireContext());
            choice.setKey(categoryKey + "_" + values[i]);
            choice.setTitle(titleArray[i]);
            if (summaryArray != null) {
                choice.setSummary(summaryArray[i]);
            }
            choice.setPersistent(false);
            choice.setOnClickListener(this);
            category.addPreference(choice);
        }
    }

    private void updateChecked(String categoryKey, int value) {
        PreferenceCategory category = findPreference(categoryKey);
        for (int i = 0; i < category.getPreferenceCount(); i++) {
            Preference choice = category.getPreference(i);
            if (choice instanceof SelectorWithWidgetPreference selector) {
                selector.setChecked(choice.getKey().equals(categoryKey + "_" + value));
            }
        }
    }

    // Stock shows a sample photo; use the wallpaper, which is the stock one here.
    private Drawable loadWallpaper() {
        try {
            return WallpaperManager.getInstance(requireContext()).getDrawable();
        } catch (RuntimeException e) {
            return null;
        }
    }
}
