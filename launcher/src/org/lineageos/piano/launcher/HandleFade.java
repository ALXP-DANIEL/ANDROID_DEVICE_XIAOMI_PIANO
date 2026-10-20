/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.piano.launcher;

import android.animation.ObjectAnimator;
import android.view.View;

/**
 * Keeps the Pixel Launcher gesture handle translucent and hides it after a
 * while without gestures. Injected into StashedHandleView, which calls
 * attach() from its constructor and poke() whenever the launcher moves,
 * scales or fades the handle (a touch, swipe or app switch).
 */
public final class HandleFade {
    private static final float SHOWN_ALPHA = 0.5f;
    private static final long HIDE_DELAY_MS = 10_000;
    private static final long FADE_MS = 300;

    private static final int TAG_HIDE = 0x7f5a0001;
    private static final int TAG_ANIM = 0x7f5a0002;
    private static final int TAG_TARGET = 0x7f5a0003;

    private HandleFade() {}

    public static void attach(View handle) {
        handle.setTransitionAlpha(SHOWN_ALPHA);
        handle.setTag(TAG_TARGET, SHOWN_ALPHA);
        scheduleHide(handle);
    }

    public static void poke(View handle) {
        if (handle.getTag(TAG_HIDE) == null) {
            // Still inside the constructor.
            return;
        }
        fadeTo(handle, SHOWN_ALPHA);
        scheduleHide(handle);
    }

    private static void scheduleHide(View handle) {
        Runnable hide = (Runnable) handle.getTag(TAG_HIDE);
        if (hide == null) {
            hide = () -> fadeTo(handle, 0f);
            handle.setTag(TAG_HIDE, hide);
        }
        handle.removeCallbacks(hide);
        handle.postDelayed(hide, HIDE_DELAY_MS);
    }

    private static void fadeTo(View handle, float alpha) {
        Object target = handle.getTag(TAG_TARGET);
        if (target instanceof Float && (Float) target == alpha) {
            return;
        }
        handle.setTag(TAG_TARGET, alpha);
        ObjectAnimator running = (ObjectAnimator) handle.getTag(TAG_ANIM);
        if (running != null) {
            running.cancel();
        }
        ObjectAnimator anim = ObjectAnimator.ofFloat(handle, "transitionAlpha", alpha);
        anim.setDuration(FADE_MS);
        handle.setTag(TAG_ANIM, anim);
        anim.start();
    }
}
