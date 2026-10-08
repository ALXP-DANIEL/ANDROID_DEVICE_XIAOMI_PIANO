/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.os.Handler;
import android.os.RemoteException;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

/**
 * Like the stock MiuiStylusGameModeManager, tell the touch panel when the app
 * in front is a game that takes pen input or a drawing app, so the firmware
 * uses the matching pen tuning. The app lists come from the stock config.
 */
class StylusAppModeController {

    private static final String TAG = "PianoParts.StylusAppMode";

    private static final String CONFIG = "/odm/etc/touch/stylus_game_config.json";
    private static final int MODE_STYLUS_GAME = 33;
    private static final int MODE_STYLUS_PAINT = 36;

    interface TouchModeSetter {
        void setTouchMode(int mode, int value);
    }

    private final Handler mHandler;
    private final TouchModeSetter mSetter;
    private final Set<String> mGames = new HashSet<>();
    private final Set<String> mPaintApps = new HashSet<>();
    private Boolean mGame;
    private Boolean mPaint;

    private final TaskStackListener mListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            ComponentName top = taskInfo.topActivity;
            if (top != null) {
                String packageName = top.getPackageName();
                mHandler.post(() -> update(packageName));
            }
        }
    };

    StylusAppModeController(Handler handler, TouchModeSetter setter) {
        mHandler = handler;
        mSetter = setter;
    }

    void start() {
        if (!loadConfig()) {
            return;
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to watch the app in front", e);
        }
    }

    private boolean loadConfig() {
        try {
            JSONObject config = new JSONObject(new String(
                    Files.readAllBytes(Paths.get(CONFIG)), StandardCharsets.UTF_8));
            if (!config.optBoolean("enable", false)) {
                return false;
            }
            addAll(config.optJSONArray("gameWhiteList"), mGames);
            addAll(config.optJSONArray("paintWhiteList"), mPaintApps);
            return true;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "No stylus app config", e);
            return false;
        }
    }

    private static void addAll(JSONArray array, Set<String> out) throws JSONException {
        if (array == null) {
            return;
        }
        for (int i = 0; i < array.length(); i++) {
            out.add(array.getString(i));
        }
    }

    private void update(String packageName) {
        // Like stock, only tell the panel when a result changes.
        boolean game = mGames.contains(packageName);
        if (mGame == null || mGame != game) {
            mGame = game;
            mSetter.setTouchMode(MODE_STYLUS_GAME, game ? 1 : 0);
        }
        boolean paint = mPaintApps.contains(packageName);
        if (mPaint == null || mPaint != paint) {
            mPaint = paint;
            mSetter.setTouchMode(MODE_STYLUS_PAINT, paint ? 1 : 0);
        }
    }
}
