/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

import android.accessibilityservice.AccessibilityService;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.ArrayList;
import java.util.List;

/** Handles the cover key that has no Android key-layout equivalent. */
public final class KeyboardKeyService extends AccessibilityService {
    private static final String TAG = "PianoPartsKeyboard";
    private static final int DND_SCAN_CODE = 193; // Linux KEY_F23.

    static void setEnabled(Context context, boolean enabled) {
        String component = new ComponentName(context, KeyboardKeyService.class).flattenToString();
        String current = Settings.Secure.getString(context.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        List<String> services = new ArrayList<>();
        if (current != null) {
            for (String entry : current.split(":")) {
                ComponentName name = ComponentName.unflattenFromString(entry);
                if (!entry.isEmpty() && !new ComponentName(context, KeyboardKeyService.class)
                        .equals(name)) services.add(entry);
            }
        }
        if (enabled) services.add(component);
        String updated = String.join(":", services);
        if (!updated.equals(current)) {
            Settings.Secure.putString(context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated);
        }
        if (enabled || services.isEmpty()) {
            Settings.Secure.putInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, enabled ? 1 : 0);
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        InputDevice device = event.getDevice();
        if (event.getKeyCode() != KeyEvent.KEYCODE_BUTTON_2
                || event.getScanCode() != DND_SCAN_CODE || device == null
                || device.getVendorId() != 0x15d9 || device.getProductId() != 0x00a3
                || !PadKeyboardManager.get(this).areKeysEnabled()) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            int filter = manager.getCurrentInterruptionFilter();
            if (filter == NotificationManager.INTERRUPTION_FILTER_UNKNOWN) return true;
            try {
                manager.setInterruptionFilter(
                        filter == NotificationManager.INTERRUPTION_FILTER_ALL
                                ? NotificationManager.INTERRUPTION_FILTER_PRIORITY
                                : NotificationManager.INTERRUPTION_FILTER_ALL,
                        true /* fromUser: physical cover key */);
            } catch (SecurityException e) {
                Log.e(TAG, "Cannot toggle Do Not Disturb", e);
            }
        }
        return true;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {}
    @Override public void onInterrupt() {}
}
