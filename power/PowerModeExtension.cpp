// SPDX-FileCopyrightText: The LineageOS Project
// SPDX-License-Identifier: Apache-2.0

#include <aidl/android/hardware/power/BnPower.h>

namespace aidl::google::hardware::power::impl::pixel {
using ::aidl::android::hardware::power::Mode;

// Enable device-specific modes only after validating the piano kernel ABI.
bool isDeviceSpecificModeSupported(Mode, bool*) {
    return false;
}

bool setDeviceSpecificMode(Mode, bool) {
    return false;
}
}  // namespace aidl::google::hardware::power::impl::pixel
