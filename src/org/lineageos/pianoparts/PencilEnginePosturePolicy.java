/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts;

/**
 * AOSP-native, opt-in subset of the HyperOS PencilEngine app policy.
 *
 * No JNI, proprietary authorization, untrusted client Binder or BLE input
 * handling is involved. The only output is a requested stock TouchFeature
 * posture mode when a trusted foreground drawing app is using a ready pen.
 */
public final class PencilEnginePosturePolicy {
    public static final String PREFS_NAME = "piano_pencil_engine";
    public static final String KEY_ENABLED = "drawing_posture_enabled";
    public static final int TOUCH_MODE = 20036;
    public static final int UNCHANGED = -1;

    private Boolean lastRequested;

    /**
     * @return -1 for no change, otherwise the touch-controller mode value 0/1.
     */
    public int transition(boolean optedIn, boolean penReady, boolean interactive,
            boolean drawingForeground) {
        boolean wanted = optedIn && penReady && interactive && drawingForeground;
        if (lastRequested != null && lastRequested == wanted) return UNCHANGED;
        lastRequested = wanted;
        return wanted ? 1 : 0;
    }

    /** Force a new send after a known HAL restart or controller recreation. */
    public void invalidate() {
        lastRequested = null;
    }
}
