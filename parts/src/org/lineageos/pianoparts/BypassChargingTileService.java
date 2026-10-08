/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Bypass charging, the HyperOS "side road charge": while plugged in, power
 * the tablet straight from the charger and let the battery rest. Stock
 * Security Center writes the bypass command (0x400) with 1 or 0 to smart_chg.
 */
public class BypassChargingTileService extends TileService {

    private static final String TAG = "PianoPartsBypass";

    private static final String SMART_CHG_PATH =
            "/sys/class/xm_power/charger/smart_charge/smart_chg";
    private static final int CMD_BYPASS = 0x400;
    private static final int STATE_BYPASS = 0x400;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        setEnabled(!isEnabled());
        updateTile();
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setState(isEnabled() ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }

    static void setEnabled(boolean enabled) {
        write(CMD_BYPASS | (enabled ? 1 : 0));
    }

    static boolean isEnabled() {
        try (BufferedReader reader = new BufferedReader(new FileReader(SMART_CHG_PATH))) {
            return (Integer.parseInt(reader.readLine().trim()) & STATE_BYPASS) != 0;
        } catch (IOException | NullPointerException | NumberFormatException e) {
            Log.e(TAG, "Failed to read smart_chg", e);
            return false;
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
