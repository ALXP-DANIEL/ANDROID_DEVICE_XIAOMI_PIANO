/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;

import java.util.Random;

import lineageos.providers.LineageSettings;

/**
 * The HyperOS classic reading mode, as DisplayFeatureManagerService and
 * MiuiPaperContrastOverlay do it: warmth on the eye care feature, the paper
 * colors on the paper feature and a paper texture of grey noise drawn over
 * the screen. It uses the stock settings, and follows Lineage's reading mode
 * setting so the Lineage tile switches it too.
 */
public class ReadingModeController {

    static final String ENABLED = "screen_paper_mode_enabled";
    static final String LEVEL = "screen_paper_texture_level";
    static final String TEXTURE = "screen_texture_eyecare_level";
    static final String COLOR_TYPE = "screen_texture_color_type";

    // Stock piano values from device_features/piano.xml.
    static final int LEVEL_MIN = 50;
    static final int LEVEL_MAX = 255;
    static final int LEVEL_DEFAULT = LEVEL_MAX / 8 * 5;
    static final int TEXTURE_MIN = 0;
    static final int TEXTURE_MAX = 35;
    static final int TEXTURE_DEFAULT = 0;

    private static final int FEATURE_EYECARE = 3;
    private static final int FEATURE_PAPER_COLORS = 31;
    private static final int NOISE_SIZE = 256;

    private final PianoPartsApp mApp;
    private final ContentResolver mResolver;
    private final WindowManager mWindowManager;
    private View mTexture;

    public ReadingModeController(PianoPartsApp app) {
        mApp = app;
        mResolver = app.getContentResolver();
        mWindowManager = app.getSystemService(WindowManager.class);

        Handler handler = new Handler(Looper.getMainLooper());
        ContentObserver observer = new ContentObserver(handler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                if (LineageSettings.System.getUriFor(
                        LineageSettings.System.DISPLAY_READING_MODE).equals(uri)) {
                    putInt(ENABLED, isLineageEnabled() ? 1 : 0);
                    return;
                }
                apply();
            }
        };
        for (String key : new String[] { ENABLED, LEVEL, TEXTURE, COLOR_TYPE }) {
            mResolver.registerContentObserver(Settings.System.getUriFor(key), false, observer);
        }
        mResolver.registerContentObserver(LineageSettings.System.getUriFor(
                LineageSettings.System.DISPLAY_READING_MODE), false, observer);
        apply();
    }

    boolean isEnabled() {
        return getInt(ENABLED, 0) != 0;
    }

    void setEnabled(boolean enabled) {
        putInt(ENABLED, enabled ? 1 : 0);
    }

    private void apply() {
        boolean enabled = isEnabled();
        if (isLineageEnabled() != enabled) {
            LineageSettings.System.putInt(mResolver,
                    LineageSettings.System.DISPLAY_READING_MODE, enabled ? 1 : 0);
        }
        int texture = enabled ? getInt(TEXTURE, TEXTURE_DEFAULT) : 0;
        mApp.setDisplayFeature(FEATURE_PAPER_COLORS, enabled ? getInt(COLOR_TYPE, 0) : 0);
        mApp.setDisplayFeature(FEATURE_EYECARE, enabled ? getInt(LEVEL, LEVEL_DEFAULT) : 0);
        showTexture(texture);
    }

    // Stock draws grey noise, a little bluer, at an alpha of level / 255.
    private void showTexture(int level) {
        if (level <= 0) {
            if (mTexture != null) {
                mWindowManager.removeView(mTexture);
                mTexture = null;
            }
            return;
        }
        if (mTexture == null) {
            mTexture = new NoiseView(mApp);
            mWindowManager.addView(mTexture, layoutParams());
        }
        mTexture.setAlpha(level / 255f);
    }

    private static WindowManager.LayoutParams layoutParams() {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("PianoPaperTexture");
        lp.setFitInsetsTypes(0);
        lp.setTrustedOverlay();
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS;
        return lp;
    }

    private boolean isLineageEnabled() {
        return LineageSettings.System.getInt(mResolver,
                LineageSettings.System.DISPLAY_READING_MODE, 0) != 0;
    }

    private int getInt(String key, int def) {
        return Settings.System.getInt(mResolver, key, def);
    }

    private void putInt(String key, int value) {
        Settings.System.putInt(mResolver, key, value);
    }

    private static class NoiseView extends View {
        private final Paint mPaint = new Paint();

        NoiseView(Context context) {
            super(context);
            Random random = new Random();
            int[] pixels = new int[NOISE_SIZE * NOISE_SIZE];
            for (int i = 0; i < pixels.length; i++) {
                int grey = random.nextInt(256);
                pixels[i] = Color.rgb(grey, grey, Math.min(255, Math.round(grey * 1.1f)));
            }
            Bitmap noise = Bitmap.createBitmap(pixels, NOISE_SIZE, NOISE_SIZE,
                    Bitmap.Config.ARGB_8888);
            mPaint.setShader(new BitmapShader(noise, Shader.TileMode.REPEAT,
                    Shader.TileMode.REPEAT));
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawPaint(mPaint);
        }
    }
}
