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
 * Switches mi_thermald between its default profile and the game profile, as
 * the HyperOS game turbo does.
 */
public class ThermalProfileTileService extends TileService {

    private static final String TAG = "PianoPartsThermal";

    private static final String THERMAL_PROFILE_PATH =
            "/sys/class/thermal/thermal_message/sconfig";
    private static final int PROFILE_DEFAULT = 0;
    private static final int PROFILE_GAME = 19;

    @Override
    public void onStartListening() {
        super.onStartListening();
        updateTile(readProfile());
    }

    @Override
    public void onClick() {
        super.onClick();
        setGameProfile(!isGameProfile());
        updateTile(readProfile());
    }

    private void updateTile(int profile) {
        Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        boolean game = profile == PROFILE_GAME;
        tile.setState(game ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setSubtitle(getString(game
                ? R.string.thermal_profile_game : R.string.thermal_profile_default));
        tile.updateTile();
    }

    static boolean isGameProfile() {
        return readProfile() == PROFILE_GAME;
    }

    static void setGameProfile(boolean game) {
        writeProfile(game ? PROFILE_GAME : PROFILE_DEFAULT);
    }

    private static int readProfile() {
        try (BufferedReader reader = new BufferedReader(new FileReader(THERMAL_PROFILE_PATH))) {
            return Integer.parseInt(reader.readLine().trim());
        } catch (IOException | NullPointerException | NumberFormatException e) {
            Log.e(TAG, "Failed to read the thermal profile", e);
            return PROFILE_DEFAULT;
        }
    }

    private static void writeProfile(int profile) {
        try (FileWriter writer = new FileWriter(THERMAL_PROFILE_PATH)) {
            writer.write(Integer.toString(profile));
        } catch (IOException e) {
            Log.e(TAG, "Failed to write the thermal profile", e);
        }
    }
}
