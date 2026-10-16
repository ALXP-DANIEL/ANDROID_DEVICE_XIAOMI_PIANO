/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import org.lineageos.pianoparts.pencilengine.PencilEngineLease;
import android.os.PowerManager;
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
import java.util.List;
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
        boolean setTouchMode(int mode, int value);
    }

    private final Handler mHandler;
    private final TouchModeSetter mSetter;
    private final Context mContext;
    private final PowerManager mPower;
    private final SharedPreferences mPenStatus;
    private final PencilEnginePosturePolicy mPosturePolicy =
            new PencilEnginePosturePolicy();
    private final Set<String> mGames = new HashSet<>();
    private final Set<String> mPaintApps = new HashSet<>();
    private Boolean mGame;
    private Boolean mPaint;
    private boolean mStarted;
    private boolean mScreenWatchRegistered;
    private boolean mTaskWatchRegistered;
    private boolean mPostureHalReady;
    private final Runnable mLeaseRefresh = new Runnable() {
        @Override public void run() {
            try {
                refreshPosture();
            } catch (RuntimeException unavailable) {
                Log.w(TAG, "Posture state unavailable; clearing mode", unavailable);
                mPostureHalReady = false;
                mPosturePolicy.invalidate();
                try { mSetter.setTouchMode(PencilEnginePosturePolicy.TOUCH_MODE, 0); }
                catch (RuntimeException denied) { Log.w(TAG, "Cannot clear posture mode", denied); }
            } finally {
                mHandler.postDelayed(this, 500);
            }
        }
    };
    private final Runnable mRefreshTask = this::refreshTopTask;

    private final SharedPreferences.OnSharedPreferenceChangeListener mPenListener =
            (prefs, key) -> {
                if ("pen_initialized".equals(key) || "state".equals(key)) {
                    queueRefresh();
                }
            };
    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            mHandler.post(() -> {
                if (Intent.ACTION_SCREEN_ON.equals(intent.getAction())
                        || Intent.ACTION_USER_SWITCHED.equals(intent.getAction())) refreshTopTask();
                refreshPosture();
            });
        }
    };

    private final TaskStackListener mListener = new TaskStackListener() {
        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            ComponentName top = taskInfo.topActivity;
            mHandler.post(() -> update(top == null ? null : top.getPackageName()));
        }

        @Override
        public void onTaskStackChanged() {
            // Task-stack callbacks can burst during transitions; query once
            // when activity focus has settled rather than polling the HAL.
            mHandler.removeCallbacks(mRefreshTask);
            mHandler.postDelayed(mRefreshTask, 150L);
        }
    };

    StylusAppModeController(Context context, Handler handler, TouchModeSetter setter) {
        mContext = context.getApplicationContext();
        mHandler = handler;
        mSetter = setter;
        mPower = mContext.getSystemService(PowerManager.class);
        mPenStatus = mContext.createDeviceProtectedStorageContext()
                .getSharedPreferences("stylus_status", Context.MODE_PRIVATE);
    }

    private void queueRefresh() {
        mHandler.post(this::refreshPosture);
    }

    void start() {
        if (mStarted) return;
        // Engine clients remain supported when the optional game/app list is absent.
        loadConfig();
        mStarted = true;
        mPenStatus.registerOnSharedPreferenceChangeListener(mPenListener);
        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_USER_SWITCHED);
        try {
            mContext.registerReceiver(mScreenReceiver, screen, Context.RECEIVER_NOT_EXPORTED);
            mScreenWatchRegistered = true;
        } catch (RuntimeException e) {
            // Don't enable posture if we cannot guarantee screen-off cleanup.
            Log.w(TAG, "Cannot observe screen changes for app-aware pen posture", e);
        }
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mListener);
            mTaskWatchRegistered = true;
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to watch the app in front", e);
        }
        refreshTopTask();
        mHandler.post(mLeaseRefresh);
    }

    private void refreshTopTask() {
        if (!mStarted) return;
        try {
            ActivityManager manager = mContext.getSystemService(ActivityManager.class);
            List<ActivityManager.RunningTaskInfo> tasks = manager.getRunningTasks(1);
            ComponentName top = tasks.isEmpty() ? null : tasks.get(0).topActivity;
            update(top == null ? null : top.getPackageName());
        } catch (RuntimeException e) {
            // Never keep optional app tuning enabled when task state is unknown.
            Log.w(TAG, "Cannot determine the foreground drawing app", e);
            update(null);
        }
    }

    private void refreshPosture() {
        if (!mStarted) return;
        int boot = Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BOOT_COUNT, -1);
        boolean consumerReady = boot >= 0 && mScreenWatchRegistered && mTaskWatchRegistered
                && ActivityManager.getCurrentUser() == mContext.getUserId();
        boolean clientRequested = false;
        try {
            int uid = mContext.getPackageManager().getPackageUid("com.miui.notes", 0);
            clientRequested = consumerReady && PencilEngineLease.accepts(
                    Settings.Secure.getString(mContext.getContentResolver(), PencilEngineLease.POSTURE),
                    boot, uid, SystemClock.elapsedRealtime());
        } catch (android.content.pm.PackageManager.NameNotFoundException absent) {
            // No installed Notes UID can own a request.
        }
        boolean ready = mPenStatus.getBoolean("pen_initialized", false)
                && "CONNECTED".equals(mPenStatus.getString("state", null))
                && mPenStatus.getInt("vendor", -1) == 34
                && (mPenStatus.getInt("product", -1) == 20609
                    || mPenStatus.getInt("product", -1) == 12929);
        boolean interactive = consumerReady && mPower != null && mPower.isInteractive();
        int requested = mPosturePolicy.transition(ready, interactive, clientRequested);
        if (requested != PencilEnginePosturePolicy.UNCHANGED) {
            Log.i(TAG, "App-aware PencilEngine posture " + (requested == 1 ? "on" : "off"));
            mPostureHalReady = mSetter.setTouchMode(PencilEnginePosturePolicy.TOUCH_MODE, requested);
            if (!mPostureHalReady) mPosturePolicy.invalidate();
        }
        try {
            if (consumerReady && mPostureHalReady) {
                // Advertise only after a real HAL transaction, never merely app startup.
                Settings.Global.putInt(mContext.getContentResolver(), PencilEngineLease.POSTURE_READY, boot);
                Settings.Global.putLong(mContext.getContentResolver(),
                        PencilEngineLease.POSTURE_HEARTBEAT, SystemClock.elapsedRealtime());
            }
        } catch (RuntimeException denied) {
            Log.w(TAG, "Cannot publish PencilEngine posture readiness", denied);
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
        refreshPosture();
    }
}
