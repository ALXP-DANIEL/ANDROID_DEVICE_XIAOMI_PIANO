/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.ContentResolver;
import android.content.Context;
import android.hardware.input.InputManager;
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

    private final Context mContext;
    private final ContentResolver mResolver;
    private Boolean mTouchpadDown;
    private InputMonitor mMonitor;
    private InputEventReceiver mReceiver;

    TouchpadFlickManager(Context context) {
        mContext = context;
        mResolver = context.getContentResolver();
    }

    void start() {
        boolean enabled = loadProfile();
        Settings.Global.putInt(mResolver, ENABLE_FLICK_OPTIMIZATION, enabled ? 1 : 0);
        if (!enabled) {
            return;
        }
        try {
            mMonitor = mContext.getSystemService(InputManager.class)
                    .monitorGestureInput(TAG, Display.DEFAULT_DISPLAY);
            mReceiver = new InputEventReceiver(mMonitor.getInputChannel(),
                    Looper.getMainLooper()) {
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
        } catch (RuntimeException e) {
            Log.e(TAG, "Could not watch the touchpad", e);
        }
    }

    private void onDown(InputDevice device) {
        boolean touchpad = device != null && device.getVendorId() == XIAOMI_VENDOR_ID
                && device.getProductId() == TOUCHPAD_PRODUCT_ID;
        if (mTouchpadDown == null || mTouchpadDown != touchpad) {
            mTouchpadDown = touchpad;
            Settings.Global.putInt(mResolver, IS_TOUCHPAD_SCROLL, touchpad ? 1 : 0);
        }
    }

    /** Copies the four stock profile values to global settings, as stock does. */
    private boolean loadProfile() {
        File file = new File(PROFILE);
        if (!file.exists()) {
            return false;
        }
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
                    Settings.Global.putFloat(mResolver, key, Float.parseFloat(value));
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
