/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * The HyperOS custom color temperature picker: a color wheel, white in the
 * middle and red at the top, with a dot that is dragged to pick a color.
 * The wheel is drawn here instead of shipping the stock image.
 */
public class ColorPickerView extends View {

    public interface OnColorPickedListener {
        void onColorPicked(int rgb);
    }

    // Saturation at the edge of the wheel, matching the stock image.
    private static final float EDGE_SATURATION = 0.6f;

    private final Paint mBitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint mDotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mDotRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDotRadius;

    private Bitmap mWheel;
    private float mCenter;
    private float mRadius;
    private int mColor = Color.WHITE;
    private float mDotX;
    private float mDotY;
    private boolean mDragging;
    private OnColorPickedListener mListener;

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mDotRadius = 14 * density;
        mDotRing.setStyle(Paint.Style.STROKE);
        mDotRing.setStrokeWidth(4 * density);
        mDotRing.setColor(Color.WHITE);
        mDotRing.setShadowLayer(4 * density, 0, density, 0x55000000);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public void setOnColorPickedListener(OnColorPickedListener listener) {
        mListener = listener;
    }

    /** Moves the dot to the place of this color. */
    public void setColor(int rgb) {
        mColor = rgb | 0xff000000;
        placeDot();
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec);
        int max = Math.round(290 * getResources().getDisplayMetrics().density);
        int size = Math.min(width, max);
        setMeasuredDimension(width, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        mCenter = h / 2f;
        mRadius = h / 2f - mDotRadius;
        mWheel = drawWheel(Math.round(mRadius * 2));
        placeDot();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mWheel == null) {
            return;
        }
        float left = getWidth() / 2f - mRadius;
        canvas.drawBitmap(mWheel, left, mCenter - mRadius, mBitmapPaint);
        mDotFill.setColor(mColor);
        canvas.drawCircle(mDotX, mDotY, mDotRadius, mDotFill);
        canvas.drawCircle(mDotX, mDotY, mDotRadius, mDotRing);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }
        float x = event.getX();
        float y = event.getY();
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // Like stock, only the dot can be dragged.
                if (Math.hypot(x - mDotX, y - mDotY) > mDotRadius * 2.5f) {
                    return false;
                }
                mDragging = true;
                getParent().requestDisallowInterceptTouchEvent(true);
                // fall through
            case MotionEvent.ACTION_MOVE:
                if (!mDragging) {
                    return false;
                }
                moveDot(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                return true;
        }
        return false;
    }

    private void moveDot(float x, float y) {
        float cx = getWidth() / 2f;
        float dx = x - cx;
        float dy = y - mCenter;
        float distance = (float) Math.hypot(dx, dy);
        if (distance > mRadius) {
            dx *= mRadius / distance;
            dy *= mRadius / distance;
        }
        mDotX = cx + dx;
        mDotY = mCenter + dy;
        mColor = colorAt(dx / mRadius, dy / mRadius);
        invalidate();
        if (mListener != null) {
            mListener.onColorPicked(mColor & 0xffffff);
        }
    }

    private void placeDot() {
        float[] hsv = new float[3];
        Color.colorToHSV(mColor, hsv);
        double angle = Math.toRadians(hsv[0]);
        float r = Math.min(hsv[1] / EDGE_SATURATION, 1f) * mRadius;
        mDotX = getWidth() / 2f - (float) Math.sin(angle) * r;
        mDotY = mCenter - (float) Math.cos(angle) * r;
    }

    // dx and dy go from -1 to 1. Hue turns counter-clockwise from red at the top.
    private static int colorAt(float dx, float dy) {
        float hue = (float) Math.toDegrees(Math.atan2(-dx, -dy));
        if (hue < 0) {
            hue += 360;
        }
        float saturation = Math.min((float) Math.hypot(dx, dy), 1f) * EDGE_SATURATION;
        return Color.HSVToColor(new float[] { hue, saturation, 1f });
    }

    private static Bitmap drawWheel(int size) {
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        float half = size / 2f;
        int[] row = new int[size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float dx = (x + 0.5f - half) / half;
                float dy = (y + 0.5f - half) / half;
                row[x] = Math.hypot(dx, dy) <= 1 ? colorAt(dx, dy) : 0;
            }
            bitmap.setPixels(row, 0, size, 0, y, size, 1);
        }
        return bitmap;
    }
}
