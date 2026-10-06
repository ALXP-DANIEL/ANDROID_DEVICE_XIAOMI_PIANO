/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Client copy of the interface served by vendor.xiaomi.hw.touchfeature-service,
 * as reverse-engineered by Evolution-X (hardware_xiaomi).
 */

package vendor.xiaomi.hw.touchfeature;

interface ITouchFeature {
    int getModeCurValueString(int touchId, int mode);
    int getModeValues(int touchId, int mode);
    int getTouchModeCurValue(int touchId, int mode);
    int getTouchModeDefValue(int touchId, int mode);
    int getTouchModeMaxValue(int touchId, int mode);
    int getTouchModeMinValue(int touchId, int mode);
    boolean resetTouchMode(int touchId, int mode);
    boolean setEdgeMode(int touchId, int mode, in int[] value, int length);
    void setTouchMode(int touchId, int mode, int value);
}
