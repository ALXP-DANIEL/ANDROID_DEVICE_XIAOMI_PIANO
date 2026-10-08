/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.app.Application;
import android.compat.Compatibility;
import android.content.pm.ActivityInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;

import com.android.internal.compat.CompatibilityChangeConfig;
import com.android.internal.compat.IPlatformCompat;

import org.lineageos.pianoparts.keyboard.PadKeyboardManager;
import org.lineageos.pianoparts.stylus.StylusController;

import java.util.Set;  import lineageos.providers.LineageSettings;

import vendor.xiaomi.hw.touchfeature.ITouchFeature;

/**
 * Like HyperOS, tell the touch panel which way the screen is rotated so it
 * applies the matching edge palm rejection.
 */
public class PianoPartsApp extends Application {

    private static final String TAG = "PianoParts";

    private static final String TOUCH_FEATURE_SERVICE =
            "vendor.xiaomi.hw.touchfeature.ITouchFeature/default";
    private static final int TOUCH_ID = 0;
    private static final int MODE_PANEL_ORIENTATION = 8;

    private static final String DISPLAY_FEATURE_SERVICE =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature/default";
    private static final String DISPLAY_FEATURE_DESCRIPTOR =
            "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
    // IDisplayFeature.setFeature(displayId, featureId, value, cookie)
    private static final int TRANSACTION_SET_FEATURE = IBinder.FIRST_CALL_TRANSACTION + 6;
    private static final int FEATURE_TRUE_TONE = 32;
    private static final int FEATURE_COLOR_TEMP = 23;
    static final int FEATURE_SUNLIGHT_SCREEN = 12;

    private static final String CAMERA_PACKAGE = "com.android.camera";
    // CameraServiceProxy.OVERRIDE_CAMERA_ROTATE_AND_CROP_DEFAULTS
    private static final long CAMERA_ROTATE_AND_CROP_OVERRIDE = 189229956L;

    private static final String PREFS_NAME = "piano_parts";
    private static final String KEY_TRUE_TONE = "true_tone";
    private static final String SETTING_COLOR_MODE = "screen_optimize_mode";
    private static final String SETTING_COLOR_LEVEL = "screen_color_level";
    static final int[] COLOR_MODES = { 1, 2, 3 };
    private static final String KEY_SUNLIGHT_MODE = "sunlight_mode";

    private DisplayManager mDisplayManager;
    private ReadingModeController mReadingModeController;
    private SunlightModeController mSunlightModeController;
    private ITouchFeature mTouchFeature;
    private int mRotation = -1;

    private final DisplayManager.DisplayListener mDisplayListener =
            new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {}

                @Override
                public void onDisplayRemoved(int displayId) {}

                @Override
                public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) {
                        updateRotation();
                    }
                }
            };

    @Override
    public void onCreate() {
        super.onCreate();
        mDisplayManager = getSystemService(DisplayManager.class);
        mDisplayManager.registerDisplayListener(mDisplayListener,
                new Handler(Looper.getMainLooper()));
        updateRotation();
        applyColorScheme();
        if (isTrueTone()) {
            setDisplayFeature(FEATURE_TRUE_TONE, 1);
        }
        allowCameraLandscape();
        BatteryProtection.apply(this);
        // Like stock, keep the charging light on when full. Only set once,
        // so the Battery light setting stays the user's choice.
        if (LineageSettings.System.getString(getContentResolver(),
                LineageSettings.System.BATTERY_LIGHT_FULL_CHARGE_DISABLED) == null) {
            LineageSettings.System.putInt(getContentResolver(),
                    LineageSettings.System.BATTERY_LIGHT_FULL_CHARGE_DISABLED, 0);
        }
        new PenBatteryNotifier(this);
        new VolumeBoostNotifier(this);
        new TouchpadFlickManager(this).start();
        new StylusController(this, new Handler(Looper.getMainLooper())).start();
        new StylusAppModeController(new Handler(Looper.getMainLooper()),
                this::setTouchMode).start();
        PadKeyboardManager.get(this).start();
        mReadingModeController = new ReadingModeController(this);
        mSunlightModeController = new SunlightModeController(this);
        mSunlightModeController.setEnabled(isSunlightMode());
    }

    /**
     * MiuiCamera asks for portrait, which Android letterboxes on the landscape
     * tablet. HyperOS lets it use the whole screen, so let the camera ignore
     * its orientation request here. The app also rotates its own preview, so
     * turn off the camera service's automatic rotate-and-crop for it.
     */
    private void allowCameraLandscape() {
        try {
            IPlatformCompat compat = IPlatformCompat.Stub.asInterface(
                    ServiceManager.getService(Context.PLATFORM_COMPAT_SERVICE));
            compat.setOverrides(new CompatibilityChangeConfig(new Compatibility.ChangeConfig(
                    Set.of(ActivityInfo.OVERRIDE_ANY_ORIENTATION_TO_USER,
                            CAMERA_ROTATE_AND_CROP_OVERRIDE), Set.of())), CAMERA_PACKAGE);
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to let the camera use landscape", e);
        }
    }

    private SharedPreferences getPrefs() {
        Context context = createDeviceProtectedStorageContext();
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void setTouchMode(int mode, int value) {
        ITouchFeature touchFeature = getTouchFeature();
        if (touchFeature == null) {
            return;
        }
        try {
            touchFeature.setTouchMode(TOUCH_ID, mode, value);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set touch mode " + mode, e);
            mTouchFeature = null;
        }
    }

    boolean isTrueTone() {
        return getPrefs().getBoolean(KEY_TRUE_TONE, false);
    }

    void setTrueTone(boolean enabled) {
        getPrefs().edit().putBoolean(KEY_TRUE_TONE, enabled).apply();
        setDisplayFeature(FEATURE_TRUE_TONE, enabled ? 1 : 0);
    }

    /**
     * The HyperOS color scheme, as DisplayFeatureManagerService applies it:
     * screen_optimize_mode 1 (vivid), 2 (saturated) or 3 (original color)
     * selects screen effect 0, 1 or 2, with screen_color_level 1 (warm),
     * 2 (standard) or 3 (cool) as the value.
     */
    int getColorMode() {
        return Settings.System.getInt(getContentResolver(), SETTING_COLOR_MODE, COLOR_MODES[0]);
    }

    int getColorLevel() {
        return Settings.System.getInt(getContentResolver(), SETTING_COLOR_LEVEL, 2);
    }

    void setColorScheme(int mode, int level) {
        Settings.System.putInt(getContentResolver(), SETTING_COLOR_MODE, mode);
        Settings.System.putInt(getContentResolver(), SETTING_COLOR_LEVEL, level);
        applyColorScheme();
    }

    private void applyColorScheme() {
        int effect = switch (getColorMode()) {
            case 2 -> 1;
            case 3 -> 2;
            default -> 0;
        };
        setDisplayFeature(effect, getColorLevel());
        // Like stock, also set the level on the colour temperature feature.
        setDisplayFeature(FEATURE_COLOR_TEMP, getColorLevel());
    }

    ReadingModeController getReadingModeController() {
        return mReadingModeController;
    }

    boolean isSunlightMode() {
        return getPrefs().getBoolean(KEY_SUNLIGHT_MODE, false);
    }

    void setSunlightMode(boolean enabled) {
        getPrefs().edit().putBoolean(KEY_SUNLIGHT_MODE, enabled).apply();
        mSunlightModeController.setEnabled(enabled);
    }

    void setDisplayFeature(int feature, int value) {
        IBinder binder = ServiceManager.waitForDeclaredService(DISPLAY_FEATURE_SERVICE);
        if (binder == null) {
            Log.e(TAG, "DisplayFeature service is not available");
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DISPLAY_FEATURE_DESCRIPTOR);
            data.writeInt(Display.DEFAULT_DISPLAY);
            data.writeInt(feature);
            data.writeInt(value);
            data.writeInt(255);
            binder.transact(TRANSACTION_SET_FEATURE, data, reply, 0);
            reply.readException();
        } catch (RemoteException | RuntimeException e) {
            Log.e(TAG, "Failed to set display feature " + feature, e);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    private void updateRotation() {
        Display display = mDisplayManager.getDisplay(Display.DEFAULT_DISPLAY);
        if (display == null) {
            return;
        }
        int rotation = display.getRotation();
        if (rotation == mRotation) {
            return;
        }
        ITouchFeature touchFeature = getTouchFeature();
        if (touchFeature == null) {
            return;
        }
        try {
            // Surface.ROTATION_* values match the panel's orientation values.
            touchFeature.setTouchMode(TOUCH_ID, MODE_PANEL_ORIENTATION, rotation);
            mRotation = rotation;
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set the touch panel orientation", e);
            mTouchFeature = null;
        }
    }

    private ITouchFeature getTouchFeature() {
        if (mTouchFeature == null) {
            IBinder binder = ServiceManager.waitForDeclaredService(TOUCH_FEATURE_SERVICE);
            if (binder == null) {
                Log.e(TAG, "TouchFeature service is not available");
                return null;
            }
            mTouchFeature = ITouchFeature.Stub.asInterface(binder);
        }
        return mTouchFeature;
    }
}
