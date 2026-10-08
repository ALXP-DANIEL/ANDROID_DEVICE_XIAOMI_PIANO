/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import lineageos.hardware.LineageHardwareManager;
import lineageos.providers.LineageSettings;

/**
 * Toggles the touch panel game mode (faster touch reporting), as the HyperOS
 * game turbo does.
 */
public class TouchGameModeTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile();
    }

    @Override
    public void onClick() {
        super.onClick();
        boolean enabled = !isEnabled();
        // The same setting as Lineage's High touch polling rate switch.
        LineageSettings.System.putInt(getContentResolver(),
                LineageSettings.System.HIGH_TOUCH_POLLING_RATE_ENABLE, enabled ? 1 : 0);
        LineageHardwareManager.getInstance(this).set(
                LineageHardwareManager.FEATURE_HIGH_TOUCH_POLLING_RATE, enabled);
        updateTile();
    }

    private boolean isEnabled() {
        return LineageSettings.System.getInt(getContentResolver(),
                LineageSettings.System.HIGH_TOUCH_POLLING_RATE_ENABLE, 0) != 0;
    }

    private void updateTile() {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean enabled = isEnabled();
        tile.setState(enabled ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
