/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.Context;
import android.provider.Settings;
import android.util.Log;

import java.io.FileWriter;
import java.io.IOException;

import lineageos.health.HealthInterface;
import lineageos.providers.LineageSettings;

/**
 * The HyperOS battery protection modes, as the Security app sets them:
 * normal charging, always stop at 80% (a smart_chg command) or smart
 * protection, which holds at 80% and fills up before the usual unplug time.
 * Smart protection is Lineage charging control in its automatic mode, which
 * drives the same smart_night node.
 */
final class BatteryProtection {

    private static final String TAG = "PianoPartsBattery";
    private static final String SMART_CHG_PATH =
            "/sys/devices/platform/soc/soc:smart_charge/smart_chg";

    // Stock security_pc_secure_protect_mode_key values.
    static final String SETTING_MODE = "security_pc_secure_protect_mode_key";
    static final int MODE_NORMAL = 0;
    static final int MODE_SMART = 1;
    static final int MODE_ALWAYS = 2;

    private static final int LIMIT = 80;
    private static final int CMD_PROTECT = 0x11;
    private static final int CMD_PROTECT_OFF = 0x10;

    private BatteryProtection() {
    }

    static int getMode(Context context) {
        return Settings.System.getInt(context.getContentResolver(), SETTING_MODE, MODE_NORMAL);
    }

    static void setMode(Context context, int mode) {
        Settings.System.putInt(context.getContentResolver(), SETTING_MODE, mode);
        apply(context);
    }

    /** Sets the hardware for the saved mode; the kernel forgets it on reboot. */
    static void apply(Context context) {
        int mode = getMode(context);
        write(mode == MODE_ALWAYS ? (LIMIT << 16) | CMD_PROTECT : CMD_PROTECT_OFF);
        LineageSettings.System.putInt(context.getContentResolver(),
                LineageSettings.System.CHARGING_CONTROL_ENABLED, mode == MODE_SMART ? 1 : 0);
        if (mode == MODE_SMART) {
            LineageSettings.System.putInt(context.getContentResolver(),
                    LineageSettings.System.CHARGING_CONTROL_MODE, HealthInterface.MODE_AUTO);
        }
    }

    private static void write(int value) {
        try (FileWriter writer = new FileWriter(SMART_CHG_PATH)) {
            writer.write(Integer.toString(value));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write smart_chg", e);
        }
    }
}
