/* SPDX-License-Identifier: Apache-2.0 */
package org.lineageos.pianoparts.pencilengine;

/**
 * Names and transaction numbers confirmed in the HyperOS OS4.0.0.44 TouchService
 * IPencilEngine / IPencilEngineCallback DEX. This file does not implement Binder.
 */
public final class PencilEngineProtocol {
    public static final String DESCRIPTOR =
            "com.xiaomi.touchservice.pencilengine.IPencilEngine";
    public static final String CALLBACK_DESCRIPTOR =
            "com.xiaomi.touchservice.pencilengine.IPencilEngineCallback";

    public static final int TX_REGISTER_LISTENER = 1;
    public static final int TX_UNREGISTER_LISTENER = 2;
    public static final int TX_TRIGGER_FUNCTION = 3;
    public static final int TX_REGISTER_BY_PACKAGE = 4;
    public static final int CALLBACK_TX_ON_CHANGE = 1;

    public static final int FUNCTION_COLOR_PICKER = 0;
    public static final int FUNCTION_POSTURE = 1;
    public static final int FUNCTION_TOUCH_FILM = 2;

    // Stock PencilPostureController: ITouchFeature.setTouchMode(0, 20036, 0/1).
    // This is only a label; no touch-controller I/O is implemented here.
    public static final int POSTURE_TOUCH_MODE = 20036;

    private PencilEngineProtocol() {
    }
}
