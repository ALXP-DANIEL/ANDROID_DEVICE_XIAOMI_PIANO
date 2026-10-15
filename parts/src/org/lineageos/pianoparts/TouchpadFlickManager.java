/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.util.Xml;
import android.view.Display;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.InputEventReceiver;
import android.view.InputMonitor;
import android.view.MotionEvent;

import org.xmlpull.v1.XmlPullParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Like the stock TouchpadScrollManager, publish the touchpad fling profile from
 * /odm/etc/flick_profile.xml and whether the last touch came from the Xiaomi
 * keyboard cover touchpad. The platform OverScroller then uses the stock
 * touchpad fling physics for that touchpad.
 */
class TouchpadFlickManager {

    private static final String TAG = "PianoParts.TouchpadFlick";

    private static final String PROFILE = "/odm/etc/flick_profile.xml";
    private static final String ENABLE_FLICK_OPTIMIZATION = "enable_flick_optimization";
    private static final String IS_TOUCHPAD_SCROLL = "is_touchpad_scroll";

    private static final int XIAOMI_VENDOR_ID = 0x15d9;
    private static final int TOUCHPAD_PRODUCT_ID = 161;

    private final ContentResolver mResolver;
    private final InputManager mInputManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private int mActiveTouchpadId = -1;
    private boolean mTouchpadDown;
    private boolean mStarted;
    private InputMonitor mMonitor;
    private InputEventReceiver mReceiver;

    private final InputManager.InputDeviceListener mDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {}

                @Override
                public void onInputDeviceRemoved(int deviceId) {
                    if (deviceId == mActiveTouchpadId) {
                        setTouchpadDown(false, -1);
                    }
                }

                @Override
                public void onInputDeviceChanged(int deviceId) {
                    if (deviceId == mActiveTouchpadId
                            && !isXiaomiTouchpad(mInputManager.getInputDevice(deviceId))) {
                        setTouchpadDown(false, -1);
                    }
                }
            };

    TouchpadFlickManager(Context context) {
        mResolver = context.getContentResolver();
        mInputManager = context.getSystemService(InputManager.class);
    }

    void start() {
        if (mStarted) {
            return;
        }
        // Global settings survive a process crash. Never inherit a stale
        // touchpad-down flag from the previous PianoParts process.
        if (!putInt(IS_TOUCHPAD_SCROLL, 0)) {
            return;
        }
        mTouchpadDown = false;
        mActiveTouchpadId = -1;
        boolean enabled = loadProfile();
        if (!putInt(ENABLE_FLICK_OPTIMIZATION, 0) || !enabled || mInputManager == null) {
            return;
        }
        try {
            mMonitor = mInputManager.monitorGestureInput(TAG, Display.DEFAULT_DISPLAY);
            mReceiver = new InputEventReceiver(mMonitor.getInputChannel(),
                    mHandler.getLooper()) {
                @Override
                public void onInputEvent(InputEvent event) {
                    try {
                        if (event instanceof MotionEvent motion
                                && motion.getActionMasked() == MotionEvent.ACTION_DOWN) {
                            onDown(motion.getDevice());
                        }
                    } finally {
                        finishInputEvent(event, false);
                    }
                }
            };
            mInputManager.registerInputDeviceListener(mDeviceListener, mHandler);
            mStarted = true;
            if (!putInt(ENABLE_FLICK_OPTIMIZATION, 1)) {
                stop();
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not watch the touchpad", e);
            stop();
        }
    }

    private void onDown(InputDevice device) {
        // Match stock TouchpadScrollManager: change the fling source only on
        // ACTION_DOWN, never on ACTION_SCROLL, MOVE or pointer hover.
        boolean isTouchpad = isXiaomiTouchpad(device);
        setTouchpadDown(isTouchpad, isTouchpad ? device.getId() : -1);
    }

    private static boolean isXiaomiTouchpad(InputDevice device) {
        return device != null && device.getVendorId() == XIAOMI_VENDOR_ID
                && device.getProductId() == TOUCHPAD_PRODUCT_ID;
    }

    private void setTouchpadDown(boolean down, int deviceId) {
        mActiveTouchpadId = down ? deviceId : -1;
        if (mTouchpadDown != down) {
            if (putInt(IS_TOUCHPAD_SCROLL, down ? 1 : 0)) {
                mTouchpadDown = down;
            } else {
                // Avoid using stale per-touchpad physics when Settings is not writable.
                mTouchpadDown = false;
                mActiveTouchpadId = -1;
                putInt(ENABLE_FLICK_OPTIMIZATION, 0);
            }
        }
    }

    void stop() {
        mStarted = false;
        if (mInputManager != null) {
            mInputManager.unregisterInputDeviceListener(mDeviceListener);
        }
        if (mReceiver != null) {
            mReceiver.dispose();
            mReceiver = null;
        }
        if (mMonitor != null) {
            mMonitor.dispose();
            mMonitor = null;
        }
        setTouchpadDown(false, -1);
        putInt(IS_TOUCHPAD_SCROLL, 0);
        putInt(ENABLE_FLICK_OPTIMIZATION, 0);
    }

    private boolean putInt(String key, int value) {
        try {
            return Settings.Global.putInt(mResolver, key, value);
        } catch (RuntimeException e) {
            Log.w(TAG, "Could not update " + key, e);
            return false;
        }
    }

    /** Copies the four stock profile values to global settings, as stock does. */
    private boolean loadProfile() {
        File file = new File(PROFILE);
        if (!file.exists()) {
            return false;
        }
        // Parse and validate the complete profile before publishing any value.
        // Otherwise a malformed profile can leave half-updated global settings.
        final Map<String, Float> values = new HashMap<>();
        try (InputStream in = new FileInputStream(file)) {
            XmlPullParser parser = Xml.newPullParser();
            parser.setInput(in, null);
            for (int type = parser.getEventType(); type != XmlPullParser.END_DOCUMENT;
                    type = parser.next()) {
                if (type != XmlPullParser.START_TAG || !"param".equals(parser.getName())) {
                    continue;
                }
                String key = settingFor(parser.getAttributeValue(null, "name"));
                String value = parser.getAttributeValue(null, "value");
                if (key != null && value != null) {
                    float parsed = Float.parseFloat(value);
                    if (!Float.isFinite(parsed) || parsed <= 0f) {
                        return false;
                    }
                    values.put(key, parsed);
                }
            }
            if (values.size() != 4 || values.get("param_flick_start_vel_threshold")
                    <= values.get("param_flick_stop_vel_threshold")) {
                return false;
            }
            for (Map.Entry<String, Float> entry : values.entrySet()) {
                if (!Settings.Global.putFloat(mResolver, entry.getKey(), entry.getValue())) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Could not read " + PROFILE, e);
            return false;
        }
    }

    private static String settingFor(String name) {
        if (name == null) {
            return null;
        }
        return switch (name) {
            case "paramPixelSize" -> "param_pixel_size";
            case "paramFriction" -> "param_friction";
            case "paramFlickStopVelThreshold" -> "param_flick_stop_vel_threshold";
            case "paramFlickStartVelThreshold" -> "param_flick_start_vel_threshold";
            default -> null;
        };
    }
}
