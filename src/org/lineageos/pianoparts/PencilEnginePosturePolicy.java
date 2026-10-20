/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts;

/**
 * Shared owner for optional drawing-app tuning and authorized engine requests.
 *
 * No JNI, proprietary authorization, untrusted client Binder or BLE input
 * handling is involved. The only output is a requested stock TouchFeature
 * posture mode when a ready pen has an optional foreground drawing-app
 * request or an authorized PencilEngine client's live global request.
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
        return transition(optedIn, penReady, interactive, drawingForeground, false);
    }

    /** A live authorized engine client also owns the stock global mode. */
    public int transition(boolean optedIn, boolean penReady, boolean interactive,
            boolean drawingForeground, boolean clientRequested) {
        boolean wanted = penReady && interactive
                && (clientRequested || (optedIn && drawingForeground));
        if (lastRequested != null && lastRequested == wanted) return UNCHANGED;
        lastRequested = wanted;
        return wanted ? 1 : 0;
    }

    /** Force a new send after a known HAL restart or controller recreation. */
    public void invalidate() {
        lastRequested = null;
    }
}
