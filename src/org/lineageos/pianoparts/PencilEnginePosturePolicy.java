/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts;

/** Owns stock posture requests from live PencilEngine clients, never app lists. */
public final class PencilEnginePosturePolicy {
    public static final int TOUCH_MODE = 20036;
    public static final int UNCHANGED = -1;
    private Boolean lastRequested;

    public int transition(boolean penReady, boolean interactive, boolean clientRequested) {
        boolean wanted = penReady && interactive && clientRequested;
        if (lastRequested != null && lastRequested == wanted) return UNCHANGED;
        lastRequested = wanted;
        return wanted ? 1 : 0;
    }

    public void invalidate() {
        lastRequested = null;
    }
}
