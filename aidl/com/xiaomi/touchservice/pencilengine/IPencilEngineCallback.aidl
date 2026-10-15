/*
 * SPDX-License-Identifier: Apache-2.0
 * Stock HyperOS .44 PencilEngine callback: transaction 1 (synchronous).
 */
package com.xiaomi.touchservice.pencilengine;

interface IPencilEngineCallback {
    void onChange(int feature, int arg1, int arg2);
}
