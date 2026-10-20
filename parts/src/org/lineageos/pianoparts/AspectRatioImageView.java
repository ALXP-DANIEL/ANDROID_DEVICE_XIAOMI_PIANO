/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts;

import android.content.Context;
import android.graphics.Outline;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;

/**
 * An image 2.6 times as wide as it is high, with rounded corners, as the
 * stock color scheme preview on tablets.
 */
public class AspectRatioImageView extends ImageView {

    private static final float RATIO = 2.6f;
    private static final float CORNER_DP = 16f;

    public AspectRatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float radius = CORNER_DP * getResources().getDisplayMetrics().density;
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        setMeasuredDimension(width, Math.round(width / RATIO));
    }
}
