/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The HyperOS stylus battery popup (Settings stylus_changer_layout): a white
 * rounded card with a green or red level bar, a charging bolt and the level,
 * which slides in at the top of the screen and out again.
 */
class PenPopup {

    // Sizes, colours and timing from the stock Settings resources.
    private static final int WINDOW_WIDTH_DP = 182;
    private static final int WINDOW_HEIGHT_DP = 64;
    private static final float CORNER_DP = 17.78f;
    private static final int BAR_WIDTH_DP = 142;
    private static final float BAR_HEIGHT_DP = 11.55f;
    private static final float BAR_MARGIN_TOP_DP = 11.55f;
    private static final float TEXT_MARGIN_TOP_DP = 32.88f;
    private static final float TEXT_SIZE_SP = 14.22f;
    private static final int[] GREEN = {0xFF66CA2C, 0xFF90DE3B, 0xFF66CA2C};
    private static final int[] RED = {0xFFF22424, 0xFFFC6262, 0xFFF22424};
    private static final float[] GRADIENT_STOPS = {0f, 0.562f, 1f};
    private static final int LOW_LEVEL = 20;
    private static final long ANIMATION_MS = 280;
    private static final long SHOW_MS = 3000;
    private static final int MARGIN_TOP_DP = 24;

    private final Context mContext;
    private final WindowManager mWindowManager;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mHideRunnable = this::hide;

    private FrameLayout mView;
    private WindowManager.LayoutParams mParams;
    private ValueAnimator mAnimator;
    private LevelView mLevelView;
    private ImageView mBolt;
    private TextView mText;
    private TextView mPercent;

    PenPopup(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    void show(int level, boolean charging) {
        mHandler.post(() -> {
            if (mView == null) {
                createView();
            }
            applyTheme();
            mLevelView.setLevel(level);
            mBolt.setVisibility(charging ? View.VISIBLE : View.GONE);
            mText.setText(String.valueOf(level));
            mHandler.removeCallbacks(mHideRunnable);
            if (!mView.isAttachedToWindow()) {
                mParams = createLayoutParams();
                mWindowManager.addView(mView, mParams);
            }
            slideTo(dp(MARGIN_TOP_DP), false);
            mHandler.postDelayed(mHideRunnable, SHOW_MS);
        });
    }

    private void hide() {
        if (mView == null || !mView.isAttachedToWindow()) {
            return;
        }
        slideTo(-dp(WINDOW_HEIGHT_DP), true);
    }

    // Moves the whole window, so the card is never clipped by its own bounds.
    private void slideTo(int y, boolean remove) {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = ValueAnimator.ofInt(mParams.y, y);
        mAnimator.setDuration(ANIMATION_MS);
        mAnimator.setInterpolator(new PathInterpolator(0.25f, 0.1f, 0.25f, 1f));
        mAnimator.addUpdateListener(animation -> {
            if (!mView.isAttachedToWindow()) {
                return;
            }
            mParams.y = (int) animation.getAnimatedValue();
            mWindowManager.updateViewLayout(mView, mParams);
        });
        if (remove) {
            mAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(android.animation.Animator animation) {
                    if (mView.isAttachedToWindow()) {
                        mWindowManager.removeView(mView);
                    }
                }
            });
        }
        mAnimator.start();
    }

    private void createView() {
        mView = new FrameLayout(mContext);
        mView.setElevation(dp(8));

        mLevelView = new LevelView(mContext);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                dp(BAR_WIDTH_DP), dp(BAR_HEIGHT_DP), Gravity.CENTER_HORIZONTAL);
        barParams.topMargin = dp(BAR_MARGIN_TOP_DP);
        mView.addView(mLevelView, barParams);

        LinearLayout row = new LinearLayout(mContext);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout.LayoutParams rowParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL);
        rowParams.topMargin = dp(TEXT_MARGIN_TOP_DP);
        mView.addView(row, rowParams);

        mBolt = new ImageView(mContext);
        mBolt.setImageResource(R.drawable.ic_pen_charging);
        LinearLayout.LayoutParams boltParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        boltParams.setMarginEnd(dp(5.78f));
        row.addView(mBolt, boltParams);

        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        mText = new TextView(mContext);
        mText.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        mText.setTypeface(medium);
        row.addView(mText);
        mPercent = new TextView(mContext);
        mPercent.setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SIZE_SP);
        mPercent.setTypeface(medium);
        mPercent.setText("%");
        row.addView(mPercent);
    }

    private void applyTheme() {
        boolean night = (mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        GradientDrawable background = new GradientDrawable();
        background.setColor(night ? 0xFF242424 : 0xFFFFFFFF);
        background.setCornerRadius(dp(CORNER_DP));
        mView.setBackground(background);
        int textColor = night ? 0xFFFFFFFF : 0xFF000000;
        mText.setTextColor(textColor);
        mPercent.setTextColor(textColor);
        mBolt.setColorFilter(textColor);
        mLevelView.setTrackColor(night ? 0x1AFFFFFF : 0x1A000000);
    }

    private WindowManager.LayoutParams createLayoutParams() {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                dp(WINDOW_WIDTH_DP), dp(WINDOW_HEIGHT_DP),
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        // Start above the screen; show() slides it down.
        params.y = -dp(WINDOW_HEIGHT_DP);
        params.setTitle("PianoPenPopup");
        return params;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                mContext.getResources().getDisplayMetrics()));
    }

    /** The stock MiuiStylusLevelsView: a rounded track filled to the level. */
    private static class LevelView extends View {

        private final Paint mTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private int mLevel;

        LevelView(Context context) {
            super(context);
        }

        void setTrackColor(int color) {
            mTrackPaint.setColor(color);
            invalidate();
        }

        void setLevel(int level) {
            mLevel = Math.max(0, Math.min(100, level));
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float width = getWidth();
            float height = getHeight();
            float radius = height / 2f;
            mRect.set(0, 0, width, height);
            canvas.drawRoundRect(mRect, radius, radius, mTrackPaint);
            if (mLevel == 0) {
                return;
            }
            float fill = Math.max(height, width * mLevel / 100f);
            mFillPaint.setShader(new LinearGradient(0, 0, fill, 0,
                    mLevel <= LOW_LEVEL ? RED : GREEN, GRADIENT_STOPS, Shader.TileMode.CLAMP));
            mRect.set(0, 0, fill, height);
            canvas.drawRoundRect(mRect, radius, radius, mFillPaint);
        }
    }
}
