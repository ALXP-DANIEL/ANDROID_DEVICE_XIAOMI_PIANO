/*
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pianoparts.keyboard;

/**
 * The hinge angle between the tablet and the keyboard cover, from both
 * gravity vectors. Like stock, the keys only work between 45 and 185 degrees
 * (laptop positions); closed, folded flat behind or tent positions ignore them.
 */
final class KeyboardAngle {

    private static final float G = 9.8f;
    private static final float MOTION_TOLERANCE = 0.294f;
    private static final int WORK_LOWER = 45;
    private static final int WORK_UPPER = 185;

    private final float[] mPad = new float[3];
    private final float[] mKeyboard = new float[3];
    private boolean mPadReady;
    private boolean mKeyboardReady;
    private int mAngle = -1;

    void reset() {
        mPadReady = false;
        mKeyboardReady = false;
        mAngle = -1;
    }

    int getAngle() {
        return mAngle;
    }

    /** Until both vectors are known the keys keep working. */
    boolean isWorking() {
        return mAngle < 0 || (mAngle >= WORK_LOWER && mAngle < WORK_UPPER);
    }

    /** Returns true when the working state may have changed. */
    boolean onPadGravity(float[] values) {
        float norm = (float) Math.sqrt(values[0] * values[0] + values[1] * values[1]
                + values[2] * values[2]);
        if (Math.abs(norm - G) > MOTION_TOLERANCE) {
            // Moving: the accelerometer does not show gravity alone.
            return false;
        }
        System.arraycopy(values, 0, mPad, 0, 3);
        mPadReady = true;
        return update();
    }

    /** Parses the keyboard's G-sensor reply (12 bit signed axes). */
    boolean onKeyboardGravity(byte[] reply) {
        mKeyboard[0] = axis(reply[7], reply[6]) * G / 256f;
        mKeyboard[1] = -axis(reply[9], reply[8]) * G / 256f;
        mKeyboard[2] = -axis(reply[11], reply[10]) * G / 256f;
        mKeyboardReady = true;
        return update();
    }

    private static int axis(byte high, byte low) {
        int value = ((high << 4) & 0xFF0) | ((low >> 4) & 0x0F);
        return (value & 0x800) != 0 ? value - 4096 : value;
    }

    private boolean update() {
        if (!mPadReady || !mKeyboardReady) {
            return false;
        }
        int angle = calculate(mKeyboard, mPad);
        if (angle < 0) {
            return false;
        }
        boolean wasWorking = isWorking();
        mAngle = angle;
        return wasWorking != isWorking();
    }

    // Finds the rotation about the hinge (y axis) that brings the keyboard's
    // gravity vector onto the tablet's.
    private static int calculate(float[] keyboard, float[] pad) {
        float kNorm = norm(keyboard);
        float pNorm = norm(pad);
        if (kNorm == 0 || pNorm == 0) {
            return -1;
        }
        float kx = keyboard[0] / kNorm, ky = keyboard[1] / kNorm, kz = keyboard[2] / kNorm;
        float px = pad[0] / pNorm, py = pad[1] / pNorm, pz = pad[2] / pNorm;
        // With the hinge pointing at the ground the angle cannot be measured.
        if (Math.max(Math.abs(py), Math.abs(ky)) > 0.98f) {
            return -1;
        }
        float best = Float.MAX_VALUE;
        int bestAngle = 0;
        for (int i = 0; i <= 360; i++) {
            double radians = Math.toRadians(i);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            float d1 = cos * kx - sin * kz + px;
            float d2 = cos * kz + sin * kx + pz;
            float deviation = Math.abs(d1) + Math.abs(d2);
            if (deviation < best) {
                best = deviation;
                bestAngle = i;
            }
        }
        return bestAngle;
    }

    private static float norm(float[] v) {
        return (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
    }
}
