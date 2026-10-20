// SPDX-License-Identifier: GPL-3.0-or-later
// A virtual display through CoreGraphics' private CGVirtualDisplay classes
// (macOS 11+). The classes are looked up by name at run time, so nothing
// links against symbols missing from the public SDK.
#import <Foundation/Foundation.h>
#import <CoreGraphics/CoreGraphics.h>

NS_ASSUME_NONNULL_BEGIN

/// Creates a HiDPI display of `width` x `height` pixels at 60 Hz. Keep the
/// returned object alive while the display is wanted; releasing it removes the
/// display. Returns nil when the classes are unavailable.
id _Nullable ALXPVirtualDisplayCreate(NSString *name, unsigned int width, unsigned int height,
                                      double widthMillimeters, double heightMillimeters);

/// The CoreGraphics display ID of an object from ALXPVirtualDisplayCreate.
CGDirectDisplayID ALXPVirtualDisplayID(id display);

NS_ASSUME_NONNULL_END
