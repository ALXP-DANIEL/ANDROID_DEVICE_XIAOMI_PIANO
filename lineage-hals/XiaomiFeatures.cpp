/*
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.livedisplay-service.piano"

#include "XiaomiFeatures.h"

#include <android-base/logging.h>
#include <android/binder_auto_utils.h>
#include <android/binder_ibinder.h>
#include <android/binder_manager.h>
#include <android/binder_parcel.h>

#include <functional>

namespace xiaomi {

namespace {

// Neither Xiaomi interface ships headers, so call them by descriptor and
// transaction code, like PianoParts does.
constexpr const char* kDisplayFeature =
        "vendor.xiaomi.hardware.displayfeature_aidl.IDisplayFeature";
constexpr transaction_code_t kSetFeature = FIRST_CALL_TRANSACTION + 6;

constexpr const char* kTouchFeature = "vendor.xiaomi.hw.touchfeature.ITouchFeature";
constexpr transaction_code_t kSetTouchMode = FIRST_CALL_TRANSACTION + 8;

void* onCreate(void* args) {
    return args;
}

void onDestroy(void*) {}

binder_status_t onTransact(AIBinder*, transaction_code_t, const AParcel*, AParcel*) {
    return STATUS_UNKNOWN_TRANSACTION;
}

AIBinder_Class* clientClass(const char* descriptor) {
    return AIBinder_Class_define(descriptor, onCreate, onDestroy, onTransact);
}

bool call(const char* descriptor, AIBinder_Class* clazz, transaction_code_t code,
          const std::function<binder_status_t(AParcel*)>& write) {
    const std::string instance = std::string(descriptor) + "/default";
    ndk::SpAIBinder binder(AServiceManager_waitForService(instance.c_str()));
    if (binder.get() == nullptr) {
        LOG(ERROR) << instance << " is not available";
        return false;
    }
    if (!AIBinder_associateClass(binder.get(), clazz)) {
        LOG(ERROR) << "Unexpected interface for " << instance;
        return false;
    }

    AParcel* in = nullptr;
    if (AIBinder_prepareTransaction(binder.get(), &in) != STATUS_OK) {
        return false;
    }
    if (write(in) != STATUS_OK) {
        AParcel_delete(in);
        return false;
    }
    AParcel* out = nullptr;
    binder_status_t status = AIBinder_transact(binder.get(), code, &in, &out, 0);
    if (status != STATUS_OK) {
        LOG(ERROR) << "Call to " << instance << " failed: " << status;
        return false;
    }
    AStatus* result = nullptr;
    status = AParcel_readStatusHeader(out, &result);
    AParcel_delete(out);
    bool ok = status == STATUS_OK && AStatus_isOk(result);
    if (result != nullptr) {
        AStatus_delete(result);
    }
    if (!ok) {
        LOG(ERROR) << instance << " returned an error for transaction " << code;
    }
    return ok;
}

}  // namespace

bool setDisplayFeature(DisplayFeatureId feature, int32_t value) {
    static AIBinder_Class* clazz = clientClass(kDisplayFeature);
    return call(kDisplayFeature, clazz, kSetFeature, [&](AParcel* in) {
        binder_status_t s = AParcel_writeInt32(in, 0);  // default display
        if (s == STATUS_OK) s = AParcel_writeInt32(in, feature);
        if (s == STATUS_OK) s = AParcel_writeInt32(in, value);
        if (s == STATUS_OK) s = AParcel_writeInt32(in, 255);  // cookie
        return s;
    });
}

bool setTouchMode(TouchMode mode, int32_t value) {
    static AIBinder_Class* clazz = clientClass(kTouchFeature);
    return call(kTouchFeature, clazz, kSetTouchMode, [&](AParcel* in) {
        binder_status_t s = AParcel_writeInt32(in, 0);  // touch id
        if (s == STATUS_OK) s = AParcel_writeInt32(in, mode);
        if (s == STATUS_OK) s = AParcel_writeInt32(in, value);
        return s;
    });
}

}  // namespace xiaomi
