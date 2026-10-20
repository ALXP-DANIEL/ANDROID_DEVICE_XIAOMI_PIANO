/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.PowerManager;

/**
 * Like HyperOS, raises the DisplayFeature sunlight screen level in bright
 * ambient light so the panel stays readable outdoors.
 */
class SunlightModeController implements SensorEventListener {

    // Stock sunlightScreen thresholds from mdss_dsi_*_mi.xml: level N is
    // used at or above the Nth threshold (lux).
    private static final int[] LUX_THRESHOLDS = {
            5000, 5200, 5400, 5600, 5800, 6000, 6300, 6500, 6800, 7100, 7500,
            7800, 8200, 8600, 9000, 10000, 12500, 15000, 17500, 20000, 22500, 25000,
    };
    // Hysteresis so the boost does not flicker around the first threshold.
    private static final float LUX_OFF = 4500f;
    private static final int LEVEL_OFF = 0;

    private final PianoPartsApp mApp;
    private final SensorManager mSensorManager;
    private final Sensor mLightSensor;

    private boolean mEnabled;
    private boolean mListening;
    private int mLevel = LEVEL_OFF;

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateListening();
        }
    };

    SunlightModeController(PianoPartsApp app) {
        mApp = app;
        mSensorManager = app.getSystemService(SensorManager.class);
        mLightSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        app.registerReceiver(mScreenReceiver, filter);
    }

    void setEnabled(boolean enabled) {
        mEnabled = enabled;
        updateListening();
    }

    private void updateListening() {
        boolean screenOn = mApp.getSystemService(PowerManager.class).isInteractive();
        boolean listen = mEnabled && screenOn && mLightSensor != null;
        if (listen == mListening) {
            return;
        }
        mListening = listen;
        if (listen) {
            mSensorManager.registerListener(this, mLightSensor,
                    SensorManager.SENSOR_DELAY_NORMAL);
        } else {
            mSensorManager.unregisterListener(this);
            setLevel(LEVEL_OFF);
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        float lux = event.values[0];
        int level = 0;
        while (level < LUX_THRESHOLDS.length && lux >= LUX_THRESHOLDS[level]) {
            level++;
        }
        if (level == LEVEL_OFF && mLevel != LEVEL_OFF && lux >= LUX_OFF) {
            // Keep the lowest level until the light drops below LUX_OFF.
            level = 1;
        }
        setLevel(level);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}

    private void setLevel(int level) {
        if (level == mLevel) {
            return;
        }
        mLevel = level;
        mApp.setDisplayFeature(PianoPartsApp.FEATURE_SUNLIGHT_SCREEN, level);
    }
}
