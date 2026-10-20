/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.keyboard;

/** HyperOS BackLightAnimator's duplicate-target and rapid alternating-light filter. */
final class KeyboardBacklightFilter {
    static final long UNCHANGED = -1;
    private int mTarget = -1;
    private long mRequestTime;
    private int mLighter;
    private int mDarker;

    void reset() {
        mTarget = -1;
        mRequestTime = 0;
        mLighter = 0;
        mDarker = 0;
    }

    // Returns the animation start delay, or UNCHANGED when no new animation is needed.
    long update(int target, long now) {
        if (target == mTarget) return UNCHANGED;
        int previous = mTarget;
        long gap = now - mRequestTime;
        mTarget = target;
        mRequestTime = now;
        if (gap < 1000) {
            if (target > previous) mLighter++;
            else if (target < previous) mDarker++;
            if (mLighter != 0 && mDarker != 0 && mLighter + mDarker > 3) return 1000;
        } else {
            mLighter = 0;
            mDarker = 0;
        }
        return 0;
    }
}
