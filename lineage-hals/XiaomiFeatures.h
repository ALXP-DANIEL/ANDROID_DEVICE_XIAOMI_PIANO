/*
 * SPDX-License-Identifier: Apache-2.0
 */

#pragma once

#include <cstdint>

namespace xiaomi {

// IDisplayFeature.setFeature(displayId, featureId, value, cookie), as PianoParts
// calls it. The features are the ones stock HyperOS uses for these modes.
enum DisplayFeatureId : int32_t {
    FEATURE_PAPER_MODE = 31,
};
bool setDisplayFeature(DisplayFeatureId feature, int32_t value);

// ITouchFeature.setTouchMode(touchId, mode, value).
enum TouchMode : int32_t {
    TOUCH_MODE_GAME = 0,
};
bool setTouchMode(TouchMode mode, int32_t value);

}  // namespace xiaomi
