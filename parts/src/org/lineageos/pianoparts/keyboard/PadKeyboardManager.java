/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.DialogInterface;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.input.InputManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;
import android.view.ContextThemeWrapper;
import android.view.InputDevice;
import android.view.WindowManager;

import org.lineageos.pianoparts.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The pogo pin keyboard cover, ported from the stock HyperOS keyboard
 * service: connection and wake up, keep alive, backlight (ambient light or
 * manual), caps lock light, touchpad switch, disabling the keys when the
 * cover is folded away, versions and firmware updates.
 */
public final class PadKeyboardManager implements KeyboardTransport.Listener {

    private static final String TAG = "PianoPartsKeyboard";

    public static final String PREFS_NAME = "piano_parts";
    public static final String KEY_BACKLIGHT_AUTO = "keyboard_backlight_auto";
    public static final String KEY_BACKLIGHT_LEVEL = "keyboard_backlight_level";
    public static final String KEY_TOUCHPAD = "keyboard_touchpad";

    // Input devices of the cover (vendor 0x15d9).
    private static final int VENDOR_ID = 0x15d9;
    private static final int PRODUCT_TOUCHPAD = 161;
    private static final int PRODUCT_UNUSED = 162;
    private static final int PRODUCT_KEYBOARD = 163;
    private static final int PRODUCT_CONSUMER = 164;

    // Stock keeps the keyboard awake while the screen is on.
    private static final long KEEP_AWAKE_MS = 12000;
    private static final long CAPS_POLL_MS = 500;

    // Keyboard types that have a touchpad (and a backlight).
    private static boolean hasTouchpad(byte type) {
        return type == 32 || type == 33 || type == 16;
    }

    // Stock ambient light (lux / 5, up to 50 lux) to backlight relation.
    private static final float[] BACKLIGHT_FOR_LUX =
            {0.6f, 0.8f, 1.0f, 0.8f, 0.7f, 0.5f, 0.4f, 0.3f, 0.2f, 0.1f, 0.0f};

    public interface Listener {
        void onKeyboardChanged();

        void onUpdateProgress(boolean touchpad, int percent);

        void onUpdateFinished(boolean touchpad, boolean success);
    }

    private static volatile PadKeyboardManager sInstance;

    private final Context mContext;
    private final KeyboardTransport mTransport;
    private final KeyboardUpdater mUpdater;
    private final Handler mHandler;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final InputManager mInputManager;
    private final PowerManager mPowerManager;
    private final SensorManager mSensorManager;
    private final KeyboardAngle mAngle = new KeyboardAngle();
    private final CopyOnWriteArrayList<Listener> mListeners = new CopyOnWriteArrayList<>();

    private volatile boolean mConnected;
    private volatile boolean mPogoFault;
    private volatile boolean mScreenOn = true;
    private volatile boolean mSleeping;
    // Off until the cover is attached at a working angle, so that the cover's
    // input devices, which exist even when it is detached, do not show a cursor.
    private volatile boolean mKeysEnabled = false;
    private boolean mInTabletMode;
    // Only trust an asserted hall state after the cover angle agrees. Retain
    // that evidence while gravity samples are unavailable (for example asleep).
    private boolean mHallFoldConfirmed;
    private volatile byte mKeyboardType = -1;
    private volatile byte mTouchpadType = -1;
    private volatile String mPid;
    private volatile String mKeyboardVersion;
    private volatile String mTouchpadVersion;
    private volatile String mMcuVersion;
    private volatile boolean mMcu2022;

    // Genuineness check. Rejected covers are ignored and never updated; like
    // stock, a cover that could not be checked after five tries is trusted.
    private static final long AUTH_START_DELAY_MS = 3000;
    private static final int AUTH_ATTEMPTS = 5;
    private static final long AUTH_RETRY_MS = 5000;
    private static final long AUTH_REPLY_MS = 400;
    private static final int AUTO_UPDATE_MIN_BATTERY = 15;
    private final Handler mAuthHandler;
    private final KeyboardAuth mAuth;
    private volatile CountDownLatch mAuthLatch;
    private volatile byte[] mAuthReply;
    private volatile boolean mAuthTrusted;
    private volatile boolean mRejected;
    private volatile boolean mAuthRunning;
    private int mAuthAttempts;
    private volatile int mAuthGeneration;
    private boolean mAutoKeyboardTried;
    private boolean mAutoTouchpadTried;

    private int mLastBacklight = -1;
    private int mActiveBacklight = -1;
    private ValueAnimator mBacklightAnimator;
    private Boolean mLastCaps;
    private File mCapsLed;

    private final Runnable mKeepAwake = new Runnable() {
        @Override
        public void run() {
            if (mConnected && mScreenOn) {
                mTransport.send(KeyboardProtocol.feature(KeyboardProtocol.CMD_POWER, (byte) 1));
                mHandler.postDelayed(this, KEEP_AWAKE_MS);
            }
        }
    };

    private final Runnable mCapsPoll = new Runnable() {
        @Override
        public void run() {
            if (mConnected && mScreenOn) {
                applyCapsLight(false);
                mHandler.postDelayed(this, CAPS_POLL_MS);
            }
        }
    };

    private final SensorEventListener mLightListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            int level = Math.round(Math.min(Math.max(event.values[0], 0f), 50f) / 5f);
            animateBacklight(Math.round(100f * BACKLIGHT_FOR_LUX[Math.min(level, 10)]));
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final SensorEventListener mAccelListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (mAngle.onPadGravity(event.values) || mHallFoldConfirmed) {
                updateKeysEnabled();
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean on = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            mHandler.post(() -> onScreenChanged(on));
        }
    };

    public static PadKeyboardManager get(Context context) {
        if (sInstance == null) {
            synchronized (PadKeyboardManager.class) {
                if (sInstance == null) {
                    sInstance = new PadKeyboardManager(context.getApplicationContext());
                }
            }
        }
        return sInstance;
    }

    private PadKeyboardManager(Context context) {
        mContext = context;
        mTransport = new KeyboardTransport(this);
        mUpdater = new KeyboardUpdater(mTransport);
        mHandler = mTransport.getHandler();
        mInputManager = context.getSystemService(InputManager.class);
        mPowerManager = context.getSystemService(PowerManager.class);
        mSensorManager = context.getSystemService(SensorManager.class);
        mScreenOn = mPowerManager.isInteractive();
        HandlerThread authThread = new HandlerThread("PianoPartsKeyboardAuth");
        authThread.start();
        mAuthHandler = new Handler(authThread.getLooper());
        mAuth = new KeyboardAuth(context, this::requestAuth);
    }

    /** Starts talking to the keyboard; called once from the application. */
    public void start() {
        KeyboardKeyService.setEnabled(mContext, false);
        mAuth.bind();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, filter);
        mInputManager.registerInputDeviceListener(mInputDeviceListener, mHandler);
        mInputManager.registerOnTabletModeChangedListener(mTabletModeListener, mHandler);
        mHandler.post(() -> {
            mInTabletMode = mInputManager.isInTabletMode() == InputManager.SWITCH_STATE_ON;
            Log.i(TAG, "Initial keyboard hall tablet mode: " + mInTabletMode);
        });
        mHandler.post(this::applyInputDeviceState);
        mHandler.post(() -> {
            if (!mTransport.connect()) {
                return;
            }
            // Like stock at boot: ask the MCU for the cover state and its version.
            mTransport.send(KeyboardProtocol.checkMcuStatus());
            mTransport.send(KeyboardProtocol.getVersion(KeyboardProtocol.ADDRESS_MCU));
        });
    }

    public void addListener(Listener listener) {
        mListeners.add(listener);
    }

    public void removeListener(Listener listener) {
        mListeners.remove(listener);
    }

    public boolean areKeysEnabled() {
        return mKeysEnabled;
    }

    public boolean isConnected() {
        return mConnected;
    }

    public boolean hasBacklight() {
        return mConnected && hasTouchpad(mKeyboardType);
    }

    public boolean hasTouchpad() {
        return mConnected && hasTouchpad(mKeyboardType);
    }

    public String getKeyboardVersion() {
        return mKeyboardVersion;
    }

    public String getTouchpadVersion() {
        return mTouchpadVersion;
    }

    public String getMcuVersion() {
        return mMcuVersion;
    }

    public String getPid() {
        return mPid;
    }

    /** Applies changed settings. */
    public void applySettings() {
        mHandler.post(() -> {
            mLastBacklight = -1;
            mActiveBacklight = -1;
            updateBacklightSource();
            applyTouchpad();
        });
    }

    // Firmware updates.

    // The stock images from odm/etc, installed where the platform may read them.
    private static final String FIRMWARE_DIR = "/system_ext/etc/piano_keyboard/";

    private String firmwareDir() {
        return FIRMWARE_DIR;
    }

    /** The newer keyboard image, or null when there is none. */
    public String getAvailableKeyboardUpdate() {
        KeyboardFirmware firmware = keyboardFirmware();
        return firmware != null && firmware.isNewerThan(mKeyboardVersion) ? firmware.version : null;
    }

    /** The newer touchpad image, or null when there is none. */
    public String getAvailableTouchpadUpdate() {
        KeyboardFirmware firmware = touchpadFirmware();
        return firmware != null && firmware.isNewerThan(mTouchpadVersion) ? firmware.version : null;
    }

    private KeyboardFirmware keyboardFirmware() {
        if (!mConnected || mPid == null || mKeyboardType < 0) {
            return null;
        }
        return KeyboardFirmware.loadKeyboard(firmwareDir() + "Keyboard_Upgrade_"
                + String.format("0x%02x", mKeyboardType) + "_" + mPid + ".bin",
                mKeyboardType, mMcu2022);
    }

    private KeyboardFirmware touchpadFirmware() {
        if (!mConnected || mPid == null || mTouchpadType < 0 || !hasTouchpad(mKeyboardType)) {
            return null;
        }
        return KeyboardFirmware.loadTouchpad(firmwareDir() + "TouchPad_Upgrade_"
                + String.format("0x%02x", mTouchpadType) + "_" + mPid + ".bin");
    }

    public boolean isUpdating() {
        return mUpdater.isRunning();
    }

    /** Starts a manual update; returns false when nothing can be flashed. */
    public boolean startUpdate(boolean touchpad) {
        KeyboardFirmware firmware = touchpad ? touchpadFirmware() : keyboardFirmware();
        if (firmware == null || mRejected || !mConnected || mUpdater.isRunning()) {
            return false;
        }
        PowerManager.WakeLock wakeLock = mPowerManager.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "PianoParts:KeyboardUpdate");
        wakeLock.acquire(10 * 60 * 1000L);
        // Like stock, only the keyboard update is announced, not the touchpad's.
        if (!touchpad) {
            showToast(R.string.keyboard_upgrade_start);
        }
        int generation = mAuthGeneration;
        mUpdater.start(firmware,
                () -> mConnected && !mRejected && generation == mAuthGeneration,
                new KeyboardUpdater.Callback() {
            @Override
            public void onProgress(int percent) {
                for (Listener listener : mListeners) {
                    mMainHandler.post(() -> listener.onUpdateProgress(touchpad, percent));
                }
            }

            @Override
            public void onFinished(boolean success) {
                if (wakeLock.isHeld()) {
                    wakeLock.release();
                }
                if (!touchpad) {
                    showToast(success ? R.string.keyboard_upgrade_success
                            : R.string.keyboard_upgrade_failed);
                }
                for (Listener listener : mListeners) {
                    mMainHandler.post(() -> listener.onUpdateFinished(touchpad, success));
                }
                // Read the versions again.
                mHandler.postDelayed(() -> mTransport.send(
                        KeyboardProtocol.getVersion(KeyboardProtocol.ADDRESS_KEYBOARD)), 3000);
                // Then the other part, if it needs it.
                mHandler.postDelayed(() -> maybeAutoUpdate(), 6000);
            }
        });
        return true;
    }

    // Replies, on the transport thread.

    @Override
    public void onReply(byte[] reply) {
        if (reply.length < 5) {
            return;
        }
        if (mUpdater.onReply(reply)) {
            return;
        }
        byte source = reply[2];
        byte command = reply[4];
        if (command >= KeyboardProtocol.CMD_AUTH_START
                && command <= KeyboardProtocol.CMD_AUTH_LAST) {
            CountDownLatch latch = mAuthLatch;
            mAuthReply = reply;
            if (latch != null) {
                latch.countDown();
            }
            return;
        }
        if (source == KeyboardProtocol.ADDRESS_MCU) {
            if (command == KeyboardProtocol.CMD_GET_VERSION && reply.length >= 23) {
                byte[] version = new byte[16];
                System.arraycopy(reply, 7, version, 0, 16);
                mMcuVersion = new String(version).trim().replace("\0", "");
                mMcu2022 = mMcuVersion.startsWith("XM2022");
                Log.i(TAG, "MCU version " + mMcuVersion);
                notifyChanged();
            }
            return;
        }
        switch (command) {
            case KeyboardProtocol.CMD_MCU_STATUS:
                if (reply.length > 18 && reply[7] == 0) {
                    onStatus(reply);
                }
                break;
            case KeyboardProtocol.CMD_GET_VERSION:
                onVersion(reply);
                break;
            case KeyboardProtocol.CMD_REQUEST:
                if (reply.length < 7) break;
                // The keyboard asks to be set up again.
                if (reply[6] == 0 || reply[6] == 1) {
                    onAttached();
                }
                if (reply[6] == 100) {
                    // The cover asks to be checked again.
                    mAuthTrusted = false;
                    mRejected = false;
                    scheduleAuth(true, 0);
                    updateKeysEnabled();
                }
                break;
            case KeyboardProtocol.CMD_RECOVER_STATUS:
                if (reply.length < 7) break;
                if (reply[5] == 1 && reply[6] == 0x36) {
                    showToast(R.string.keyboard_reset_success);
                }
                break;
            case KeyboardProtocol.CMD_SLEEP:
                if (reply.length < 7) break;
                if (reply[5] == 1) {
                    onSleepChanged(reply[6] == 0);
                }
                break;
            case KeyboardProtocol.CMD_G_SENSOR:
                if (reply.length >= 12
                        && (mAngle.onKeyboardGravity(reply) || mHallFoldConfirmed)) {
                    updateKeysEnabled();
                }
                break;
            case KeyboardProtocol.CMD_FEATURE_RESPONSE:
                if (reply.length < 8) break;
                if (reply[7] != 0) {
                    Log.w(TAG, "Keyboard rejected command " + reply[5] + ": " + reply[7]);
                }
                break;
        }
    }

    private void onStatus(byte[] reply) {
        boolean connected = reply[18] == 0 && (reply[9] & 0x63) == 0x23;
        if (reply[18] == 1) {
            Log.w(TAG, "Keyboard cover over current");
        }
        boolean pogoFault = reply[18] == 0 && (reply[9] & 0x63) == 0x43;
        if (pogoFault) {
            Log.w(TAG, "Keyboard cover attached, but the pogo pins are faulty");
            if (!mPogoFault) {
                showToast(R.string.keyboard_connect_failed);
            }
        }
        mPogoFault = pogoFault;
        if (connected == mConnected) {
            return;
        }
        Log.i(TAG, "Keyboard cover " + (connected ? "attached" : "detached"));
        mConnected = connected;
        mHallFoldConfirmed = false;
        KeyboardKeyService.setEnabled(mContext, connected);
        if (connected) {
            onAttached();
            // Like stock, wake the tablet when the cover is attached.
            mPowerManager.wakeUp(SystemClock.uptimeMillis(), PowerManager.WAKE_REASON_UNKNOWN,
                    "PianoParts:keyboard_attach");
        } else {
            // An in-flight Binder check cannot be removed from the auth queue.
            // Invalidate its result before a different cover can be attached.
            mAuthGeneration++;
            mUpdater.abort();
            mAuthHandler.removeCallbacksAndMessages(null);
            mAuthRunning = false;
            mAuthTrusted = false;
            mRejected = false;
            mAuthAttempts = 0;
            mAutoKeyboardTried = false;
            mAutoTouchpadTried = false;
            mHandler.removeCallbacks(mKeepAwake);
            mHandler.removeCallbacks(mCapsPoll);
            stopSensors();
            mKeyboardType = -1;
            mTouchpadType = -1;
            mKeyboardVersion = null;
            mTouchpadVersion = null;
            mLastBacklight = -1;
            mLastCaps = null;
        }
        // The cover's HID devices remain registered even when it is detached.
        // Apply the MCU connection state immediately instead of waiting for a
        // gravity reply, which stops arriving after detach.
        updateKeysEnabled();
        notifyChanged();
    }

    private void onAttached() {
        mTransport.send(KeyboardProtocol.getVersion(KeyboardProtocol.ADDRESS_KEYBOARD));
        scheduleAuth(true, AUTH_START_DELAY_MS);
        mSleeping = false;
        mLastBacklight = -1;
        mLastCaps = null;
        onScreenChanged(mScreenOn);
        applyTouchpad();
    }

    private void onVersion(byte[] reply) {
        if (reply.length < 15) {
            return;
        }
        // Which key code generation the cover sends: stock records it for its
        // key mapping.
        try {
            Settings.System.putInt(mContext.getContentResolver(), "keyboard_keycode",
                    reply[5] + 5 < 19 ? 0 : 1);
        } catch (SecurityException e) {
            Log.w(TAG, "Cannot record the keyboard key code version", e);
        }
        mKeyboardVersion = String.format("%02x%02x", reply[7], reply[6]);
        mPid = String.format("%02x%02x", reply[12], reply[11]);
        if (reply[5] != 5) {
            mTouchpadVersion = String.format("%02x%02x", reply[9], reply[8]);
            mTouchpadType = reply[14];
        }
        mKeyboardType = reply.length >= 18 ? reply[13] : 1;
        Log.i(TAG, "Keyboard " + mPid + " type " + mKeyboardType + " version " + mKeyboardVersion
                + ", touchpad version " + mTouchpadVersion);
        // The backlight depends on the keyboard type.
        mLastBacklight = -1;
        updateBacklightSource();
        notifyChanged();
    }

    private void onSleepChanged(boolean sleeping) {
        mSleeping = sleeping;
        if (!sleeping) {
            mLastBacklight = -1;
            mLastCaps = null;
            applyBacklight(mActiveBacklight);
            applyCapsLight(true);
        }
    }

    private void onScreenChanged(boolean on) {
        mScreenOn = on;
        mHandler.removeCallbacks(mKeepAwake);
        mHandler.removeCallbacks(mCapsPoll);
        if (!mConnected) {
            return;
        }
        if (on) {
            if (!mRejected) {
                mKeepAwake.run();
            }
            mCapsPoll.run();
            startAngle();
            updateBacklightSource();
            if (!mAuthTrusted && !mRejected && !mAuthRunning) {
                scheduleAuth(true, AUTH_START_DELAY_MS);
            }
        } else {
            stopSensors();
        }
    }

    // Backlight.

    private SharedPreferences prefs() {
        return mContext.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private void updateBacklightSource() {
        mSensorManager.unregisterListener(mLightListener);
        if (!hasBacklight() || !mScreenOn) {
            return;
        }
        if (prefs().getBoolean(KEY_BACKLIGHT_AUTO, true)) {
            Sensor light = mSensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
            if (light != null) {
                mSensorManager.registerListener(mLightListener, light,
                        SensorManager.SENSOR_DELAY_NORMAL, mHandler);
            }
        } else {
            cancelBacklightAnimation();
            applyBacklight(prefs().getInt(KEY_BACKLIGHT_LEVEL, 50));
        }
    }

    private void animateBacklight(int target) {
        mMainHandler.post(() -> {
            int from = mActiveBacklight < 0 ? target : mActiveBacklight;
            if (mBacklightAnimator != null) {
                mBacklightAnimator.cancel();
            }
            // Stock fades the backlight over two seconds.
            mBacklightAnimator = ValueAnimator.ofInt(from, target);
            mBacklightAnimator.setDuration(2000);
            mBacklightAnimator.addUpdateListener(animation -> {
                int value = (int) animation.getAnimatedValue();
                mHandler.post(() -> applyBacklight(value));
            });
            mBacklightAnimator.start();
        });
    }

    private void cancelBacklightAnimation() {
        mMainHandler.post(() -> {
            if (mBacklightAnimator != null) {
                mBacklightAnimator.cancel();
            }
        });
    }

    private void applyBacklight(int level) {
        if (level < 0) {
            return;
        }
        mActiveBacklight = level;
        if (!hasBacklight() || mSleeping || !mKeysEnabled) {
            return;
        }
        level = Math.max(0, Math.min(100, level));
        if (level != mLastBacklight) {
            mLastBacklight = level;
            mTransport.send(KeyboardProtocol.feature(KeyboardProtocol.CMD_BACKLIGHT, (byte) level));
        }
    }

    // Caps lock light: follow the LED Android sets on the keyboard input device.

    private void applyCapsLight(boolean force) {
        if (!mConnected || mSleeping) {
            return;
        }
        boolean on = readCapsLed();
        if (!force && Boolean.valueOf(on).equals(mLastCaps)) {
            return;
        }
        mLastCaps = on;
        byte command = mMcu2022 ? KeyboardProtocol.CMD_TIPS_LIGHT_2022
                : KeyboardProtocol.CMD_TIPS_LIGHT;
        byte value = mMcu2022 ? (byte) (on ? 1 : 0)
                : (on ? KeyboardProtocol.CAPS_ON : KeyboardProtocol.CAPS_OFF);
        mTransport.send(KeyboardProtocol.feature(command, value));
    }

    private boolean readCapsLed() {
        if (mCapsLed == null || !mCapsLed.exists()) {
            mCapsLed = findCapsLed();
            if (mCapsLed == null) {
                return false;
            }
        }
        try (BufferedReader reader = new BufferedReader(new FileReader(mCapsLed))) {
            String line = reader.readLine();
            return line != null && !line.trim().equals("0");
        } catch (IOException e) {
            mCapsLed = null;
            return false;
        }
    }

    private static File findCapsLed() {
        File[] leds = new File("/sys/class/leds").listFiles();
        if (leds == null) {
            return null;
        }
        for (File led : leds) {
            if (!led.getName().endsWith("::capslock")) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(
                    new FileReader(new File(led, "device/name")))) {
                String name = reader.readLine();
                if (name != null && name.contains("Xiaomi Keyboard")) {
                    return new File(led, "brightness");
                }
            } catch (IOException e) {
                // Not the keyboard cover.
            }
        }
        return null;
    }

    // Touchpad switch.

    private void applyTouchpad() {
        if (!mConnected) {
            return;
        }
        boolean enabled = prefs().getBoolean(KEY_TOUCHPAD, true) && mKeysEnabled;
        mTransport.send(KeyboardProtocol.feature(KeyboardProtocol.CMD_TOUCHPAD_ENABLE,
                (byte) (enabled ? 1 : 0)));
    }

    // Folded away: like stock, ignore the keys outside 45 to 185 degrees.

    private void startAngle() {
        Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (accel != null) {
            mSensorManager.registerListener(mAccelListener, accel,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        }
    }

    private void stopSensors() {
        mSensorManager.unregisterListener(mAccelListener);
        mSensorManager.unregisterListener(mLightListener);
        mAngle.reset();
        cancelBacklightAnimation();
    }

    private final InputManager.InputDeviceListener mInputDeviceListener =
            new InputManager.InputDeviceListener() {
                @Override
                public void onInputDeviceAdded(int deviceId) {
                    applyInputDeviceState(deviceId);
                }

                @Override
                public void onInputDeviceRemoved(int deviceId) {}

                @Override
                public void onInputDeviceChanged(int deviceId) {}
            };

    private void applyInputDeviceState() {
        for (int id : mInputManager.getInputDeviceIds()) {
            applyInputDeviceState(id);
        }
    }

    private void applyInputDeviceState(int id) {
        InputDevice device = mInputManager.getInputDevice(id);
        if (device == null || device.getVendorId() != VENDOR_ID) {
            return;
        }
        int product = device.getProductId();
        boolean enabled = mKeysEnabled;
        if (product == PRODUCT_KEYBOARD || product == PRODUCT_CONSUMER
                || product == PRODUCT_TOUCHPAD) {
            if (enabled) {
                mInputManager.enableInputDevice(id);
            } else {
                mInputManager.disableInputDevice(id);
            }
        } else if (product == PRODUCT_UNUSED) {
            mInputManager.disableInputDevice(id);
        }
    }

    private final InputManager.OnTabletModeChangedListener mTabletModeListener =
            (whenNanos, inTabletMode) -> {
                mInTabletMode = inTabletMode;
                Log.i(TAG, "Keyboard hall tablet mode: " + inTabletMode
                        + ", angle " + mAngle.getAngle());
                updateKeysEnabled();
            };

    private void updateKeysEnabled() {
        if (!mConnected || !mInTabletMode) {
            mHallFoldConfirmed = false;
        } else if (mAngle.getAngle() >= 0) {
            // Polarity is not yet verified on a real cover. A hall assertion
            // must agree with a measured folded angle before it can gate keys.
            mHallFoldConfirmed = !mAngle.isWorking();
        }
        boolean enabled = mConnected && mAngle.isWorking() && !mRejected
                && !mHallFoldConfirmed;
        if (enabled == mKeysEnabled) {
            return;
        }
        mKeysEnabled = enabled;
        Log.i(TAG, "Keyboard cover keys " + (enabled ? "enabled" : "ignored") + " at "
                + mAngle.getAngle() + " degrees");
        applyInputDeviceState();
        if (enabled) {
            mLastBacklight = -1;
            applyBacklight(mActiveBacklight);
        } else if (hasBacklight()) {
            mTransport.send(KeyboardProtocol.feature(KeyboardProtocol.CMD_BACKLIGHT, (byte) 0));
            mLastBacklight = 0;
        }
        applyTouchpad();
        notifyChanged();
    }

    private void showToast(int resId) {
        mMainHandler.post(() -> Toast.makeText(mContext, resId, Toast.LENGTH_SHORT).show());
    }

    // Genuineness check.

    /** Sends an authentication packet and waits for the cover's reply. */
    private byte[] requestAuth(byte[] packet) {
        CountDownLatch latch = new CountDownLatch(1);
        mAuthReply = null;
        mAuthLatch = latch;
        if (!mTransport.send(packet)) {
            return null;
        }
        try {
            latch.await(AUTH_REPLY_MS, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return mAuthReply;
    }

    private void scheduleAuth(boolean first, long delayMs) {
        if (first) {
            mAuthGeneration++;
            mAuthAttempts = 0;
            mAuthHandler.removeCallbacksAndMessages(null);
        }
        mAuthRunning = true;
        int generation = mAuthGeneration;
        mAuthHandler.postDelayed(() -> runAuth(first, generation), delayMs);
    }

    private void runAuth(boolean first, int generation) {
        if (generation != mAuthGeneration) {
            return;
        }
        if (!mConnected || !mScreenOn) {
            mAuthRunning = false;
            return;
        }
        int result = mAuth.check(first, mMcu2022);
        mHandler.post(() -> onAuthResult(result, generation));
    }

    private void onAuthResult(int result, int generation) {
        if (generation != mAuthGeneration) {
            Log.i(TAG, "Ignoring an obsolete keyboard authentication result");
            return;
        }
        if (!mConnected) {
            mAuthRunning = false;
            return;
        }
        switch (result) {
            case KeyboardAuth.RESULT_OK:
                Log.i(TAG, "Keyboard cover is genuine");
                mAuthRunning = false;
                mAuthTrusted = true;
                maybeAutoUpdate();
                break;
            case KeyboardAuth.RESULT_REJECT:
                Log.w(TAG, "Keyboard cover was rejected");
                mAuthRunning = false;
                mAuthTrusted = false;
                mRejected = true;
                mUpdater.abort();
                // Like stock, stop keeping a rejected cover awake.
                mHandler.removeCallbacks(mKeepAwake);
                updateKeysEnabled();
                showDialog(R.string.keyboard_identity_reject_message);
                break;
            case KeyboardAuth.RESULT_AGAIN:
                if (++mAuthAttempts < AUTH_ATTEMPTS) {
                    mAuthHandler.postDelayed(() -> runAuth(false, generation), AUTH_RETRY_MS);
                } else {
                    // Like stock, a cover that could not be checked five times
                    // is treated as genuine.
                    Log.w(TAG, "Keyboard cover could not be checked, trusting it like stock");
                    mAuthRunning = false;
                    mAuthTrusted = true;
                    maybeAutoUpdate();
                }
                break;
            default:
                Log.w(TAG, "Keyboard cover check failed: " + result);
                mAuthRunning = false;
                showDialog(R.string.keyboard_identity_transfer_error_message);
                break;
        }
        notifyChanged();
    }

    private void showDialog(int messageResId) {
        mMainHandler.post(() -> {
            AlertDialog dialog = new AlertDialog.Builder(new ContextThemeWrapper(mContext,
                    android.R.style.Theme_DeviceDefault_Light_Dialog_Alert))
                    .setMessage(messageResId)
                    .setPositiveButton(R.string.keyboard_identity_reject_confirm,
                            (DialogInterface d, int which) -> d.dismiss())
                    .create();
            dialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_DIALOG);
            dialog.show();
        });
    }

    /** Whether the cover is known to be genuine. */
    public boolean isGenuine() {
        return mAuthTrusted;
    }

    /**
     * Updates the keyboard and then the touchpad when their stock image is
     * newer, as stock does once the cover has been checked. Each part is tried
     * once per attachment.
     */
    private void maybeAutoUpdate() {
        if (!mAuthTrusted || mRejected || !mConnected || !mScreenOn || mUpdater.isRunning()) {
            return;
        }
        BatteryManager battery = mContext.getSystemService(BatteryManager.class);
        Intent status = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        boolean charging = status != null && status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        if (!charging && battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                < AUTO_UPDATE_MIN_BATTERY) {
            Log.i(TAG, "Battery too low to update the keyboard cover");
            return;
        }
        if (!mAutoKeyboardTried && getAvailableKeyboardUpdate() != null) {
            mAutoKeyboardTried = true;
            Log.i(TAG, "Updating the keyboard automatically");
            startUpdate(false);
        } else if (!mAutoTouchpadTried && getAvailableTouchpadUpdate() != null) {
            mAutoTouchpadTried = true;
            Log.i(TAG, "Updating the touchpad automatically");
            startUpdate(true);
        }
    }

    private void notifyChanged() {
        for (Listener listener : mListeners) {
            mMainHandler.post(listener::onKeyboardChanged);
        }
    }
}
