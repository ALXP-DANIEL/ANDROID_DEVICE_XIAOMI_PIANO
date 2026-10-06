/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.app.AlertDialog;
import android.os.Bundle;

import androidx.preference.Preference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import androidx.preference.PreferenceCategory;
import androidx.preference.SeekBarPreference;
import androidx.preference.SwitchPreferenceCompat;

import org.lineageos.pianoparts.keyboard.PadKeyboardManager;

public class KeyboardSettingsFragment extends SettingsBasePreferenceFragment
        implements Preference.OnPreferenceChangeListener, PadKeyboardManager.Listener {

    private PadKeyboardManager mKeyboard;
    private Preference mNotConnected;
    private SwitchPreferenceCompat mAuto;
    private SeekBarPreference mBrightness;
    private SwitchPreferenceCompat mTouchpad;
    private Preference mShortcuts;
    private PreferenceCategory mAbout;
    private Preference mModel;
    private Preference mKeyboardVersion;
    private Preference mTouchpadVersion;
    private Preference mMcuVersion;
    private Preference mUpdateKeyboard;
    private Preference mUpdateTouchpad;
    private String mProgress;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        getPreferenceManager().setStorageDeviceProtected();
        getPreferenceManager().setSharedPreferencesName(PadKeyboardManager.PREFS_NAME);
        setPreferencesFromResource(R.xml.keyboard_settings, rootKey);
        mKeyboard = PadKeyboardManager.get(requireContext());
        mNotConnected = findPreference("keyboard_not_connected");
        mAuto = findPreference(PadKeyboardManager.KEY_BACKLIGHT_AUTO);
        mBrightness = findPreference(PadKeyboardManager.KEY_BACKLIGHT_LEVEL);
        mTouchpad = findPreference(PadKeyboardManager.KEY_TOUCHPAD);
        mShortcuts = findPreference("keyboard_shortcuts");
        mAbout = findPreference("keyboard_about");
        mModel = findPreference("keyboard_model");
        mKeyboardVersion = findPreference("keyboard_version");
        mTouchpadVersion = findPreference("touchpad_version");
        mMcuVersion = findPreference("mcu_version");
        mUpdateKeyboard = findPreference("keyboard_update");
        mUpdateTouchpad = findPreference("touchpad_update");
        mAuto.setOnPreferenceChangeListener(this);
        mBrightness.setOnPreferenceChangeListener(this);
        mTouchpad.setOnPreferenceChangeListener(this);
        mUpdateKeyboard.setOnPreferenceClickListener(p -> confirmUpdate(false));
        mUpdateTouchpad.setOnPreferenceClickListener(p -> confirmUpdate(true));
    }

    @Override
    public void onResume() {
        super.onResume();
        mKeyboard.addListener(this);
        updateState();
    }

    @Override
    public void onPause() {
        mKeyboard.removeListener(this);
        super.onPause();
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // Apply once the preference has stored the new value.
        getListView().post(() -> {
            mKeyboard.applySettings();
            updateState();
        });
        return true;
    }

    private boolean confirmUpdate(boolean touchpad) {
        String version = touchpad ? mKeyboard.getAvailableTouchpadUpdate()
                : mKeyboard.getAvailableKeyboardUpdate();
        if (version == null || mKeyboard.isUpdating()) {
            return true;
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(touchpad ? R.string.touchpad_update_title : R.string.keyboard_update_title)
                .setMessage(getString(R.string.keyboard_update_warning, version))
                .setPositiveButton(R.string.keyboard_update_start, (dialog, which) -> {
                    if (mKeyboard.startUpdate(touchpad)) {
                        mProgress = getString(R.string.keyboard_update_progress, 0);
                        updateState();
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        return true;
    }

    private void updateState() {
        boolean connected = mKeyboard.isConnected();
        boolean updating = mKeyboard.isUpdating();
        mNotConnected.setVisible(!connected);
        boolean backlight = mKeyboard.hasBacklight();
        mAuto.setVisible(!connected || backlight);
        mBrightness.setVisible(!connected || backlight);
        mAuto.setEnabled(connected && !updating);
        mBrightness.setEnabled(connected && !updating && !mAuto.isChecked());
        mTouchpad.setVisible(!connected || mKeyboard.hasTouchpad());
        mTouchpad.setEnabled(connected && !updating);
        // Android's physical keyboard page closes at once without a keyboard.
        mShortcuts.setEnabled(connected);

        mAbout.setVisible(connected);
        mModel.setSummary(valueOrUnknown(mKeyboard.getPid()));
        mKeyboardVersion.setSummary(valueOrUnknown(mKeyboard.getKeyboardVersion()));
        mTouchpadVersion.setVisible(mKeyboard.hasTouchpad());
        mTouchpadVersion.setSummary(valueOrUnknown(mKeyboard.getTouchpadVersion()));
        mMcuVersion.setSummary(valueOrUnknown(mKeyboard.getMcuVersion()));

        updateUpdatePreference(mUpdateKeyboard, mKeyboard.getAvailableKeyboardUpdate(),
                connected, updating);
        updateUpdatePreference(mUpdateTouchpad, mKeyboard.getAvailableTouchpadUpdate(),
                connected && mKeyboard.hasTouchpad(), updating);
    }

    private void updateUpdatePreference(Preference preference, String version,
            boolean visible, boolean updating) {
        preference.setVisible(visible);
        if (updating && mProgress != null) {
            preference.setSummary(mProgress);
            preference.setEnabled(false);
        } else if (version != null) {
            preference.setSummary(getString(R.string.keyboard_update_available, version));
            preference.setEnabled(true);
        } else {
            preference.setSummary(R.string.keyboard_update_none);
            preference.setEnabled(false);
        }
    }

    private CharSequence valueOrUnknown(String value) {
        return value != null ? value : getString(R.string.keyboard_unknown);
    }

    @Override
    public void onKeyboardChanged() {
        if (isAdded()) {
            updateState();
        }
    }

    @Override
    public void onUpdateProgress(boolean touchpad, int percent) {
        mProgress = getString(R.string.keyboard_update_progress, percent);
        if (isAdded()) {
            updateState();
        }
    }

    @Override
    public void onUpdateFinished(boolean touchpad, boolean success) {
        mProgress = null;
        if (isAdded()) {
            new AlertDialog.Builder(requireContext())
                    .setMessage(success ? R.string.keyboard_update_success
                            : R.string.keyboard_update_failed)
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
            updateState();
        }
    }
}
