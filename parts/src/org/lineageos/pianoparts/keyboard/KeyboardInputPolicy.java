/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

/** Reconcile registered cover input devices without overriding the manager's fold/auth policy. */
final class KeyboardInputPolicy {
    interface DeviceState {
        boolean isEnabled(int id);
        void setEnabled(int id, boolean enabled);
    }

    static Boolean desired(int vendor, int product, boolean keysEnabled) {
        if (vendor != 0x15d9) return null;
        switch (product) {
            case 161: // Touchpad on covers that expose one.
            case 163: // Keyboard.
            case 164: // Consumer keys.
                return keysEnabled;
            case 162: // Stock disables this auxiliary HID device.
                return false;
            default:
                return null;
        }
    }

    static boolean reconcile(DeviceState state, int id, int vendor, int product,
            boolean keysEnabled) {
        Boolean enabled = desired(vendor, product, keysEnabled);
        if (enabled == null || state.isEnabled(id) == enabled) return false;
        state.setEnabled(id, enabled);
        return true;
    }
}
