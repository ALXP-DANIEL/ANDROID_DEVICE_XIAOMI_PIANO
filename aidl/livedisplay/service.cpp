/*
 * SPDX-License-Identifier: Apache-2.0
 */

#define LOG_TAG "vendor.lineage.livedisplay-service.piano"

#include "XiaomiFeatures.h"

#include <aidl/vendor/lineage/livedisplay/BnReadingEnhancement.h>
#include <aidl/vendor/lineage/touch/BnHighTouchPollingRate.h>
#include <android-base/logging.h>
#include <android/binder_manager.h>
#include <android/binder_process.h>

#include <atomic>

namespace {

using ::ndk::ScopedAStatus;

ScopedAStatus result(bool ok) {
    return ok ? ScopedAStatus::ok() : ScopedAStatus::fromExceptionCode(EX_ILLEGAL_STATE);
}

// The Xiaomi services cannot report these modes back, so remember what was
// set. Lineage applies the saved settings again at boot.
class ReadingEnhancement : public aidl::vendor::lineage::livedisplay::BnReadingEnhancement {
  public:
    ScopedAStatus getEnabled(bool* enabled) override {
        *enabled = mEnabled;
        return ScopedAStatus::ok();
    }
    // PianoParts follows Lineage's reading mode setting and applies the
    // stock effect (warmth, paper colors and texture), so only keep the state.
    ScopedAStatus setEnabled(bool enabled) override {
        mEnabled = enabled;
        return ScopedAStatus::ok();
    }

  private:
    std::atomic<bool> mEnabled = false;
};

// Stock HyperOS game touch mode: faster, more responsive touch reports.
class HighTouchPollingRate : public aidl::vendor::lineage::touch::BnHighTouchPollingRate {
  public:
    ScopedAStatus getEnabled(bool* enabled) override {
        *enabled = mEnabled;
        return ScopedAStatus::ok();
    }
    ScopedAStatus setEnabled(bool enabled) override {
        bool ok = xiaomi::setTouchMode(xiaomi::TOUCH_MODE_GAME, enabled ? 1 : 0);
        if (ok) mEnabled = enabled;
        return result(ok);
    }

  private:
    std::atomic<bool> mEnabled = false;
};

template <typename T>
void addService(const std::shared_ptr<T>& service) {
    const std::string instance = std::string(T::descriptor) + "/default";
    binder_status_t status =
            AServiceManager_addService(service->asBinder().get(), instance.c_str());
    CHECK_EQ(status, STATUS_OK) << "Failed to add " << instance;
}

}  // namespace

int main() {
    ABinderProcess_setThreadPoolMaxThreadCount(1);
    ABinderProcess_startThreadPool();

    addService(ndk::SharedRefBase::make<ReadingEnhancement>());
    addService(ndk::SharedRefBase::make<HighTouchPollingRate>());

    ABinderProcess_joinThreadPool();
    return EXIT_FAILURE;
}
