/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room AIDL compatibility declaration for stock HyperOS .44.
 * Exact transaction order: 1 register, 2 unregister, 3 trigger, 4 registerByPackage.
 * Declaring the interface does not install Xiaomi's privileged service or JNI.
 */
package com.xiaomi.touchservice.pencilengine;

import com.xiaomi.touchservice.pencilengine.IPencilEngineCallback;

interface IPencilEngine {
    void registerListener(IPencilEngineCallback callback);
    void unregisterListener();
    int triggerFunction(int function, int value);
    void registerListenerByPackageName(IPencilEngineCallback callback, String packageName);
}
